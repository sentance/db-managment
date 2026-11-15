#!/usr/bin/env python3
"""Fixed-purpose backup worker. No shell, generic commands, plaintext dump files or raw diagnostics."""
import hashlib
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import time
from urllib.parse import urlparse

import psycopg2
from psycopg2 import sql

WORK = Path('/work')
CHILDREN = []
STAGE = 'initialisation'


def atomic(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value))
    temporary.replace(path)


def progress(percent, stage):
    global STAGE
    STAGE = stage
    atomic(WORK / 'progress.json', {'percent': percent, 'message': stage})


def run(argv, *, env=None, input_data=None):
    completed = subprocess.run(argv, input=input_data, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, env=env, timeout=600, check=False)
    if completed.returncode:
        raise RuntimeError('tool failed')
    return completed.stdout


def pipeline(first, second, output=None, first_env=None, second_env=None):
    a = subprocess.Popen(first, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=first_env)
    CHILDREN.append(a)
    try:
        b = subprocess.Popen(second, stdin=a.stdout, stdout=output or subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, env=second_env)
        CHILDREN.append(b)
        a.stdout.close()
        try:
            b_code = b.wait(timeout=3300)
            a_code = a.wait(timeout=30)
            if a_code or b_code:
                raise RuntimeError('pipeline failed')
        finally:
            if b.poll() is None:
                b.kill()
            b.wait()
            CHILDREN.remove(b)
    finally:
        if a.poll() is None:
            a.kill()
        a.wait()
        CHILDREN.remove(a)


class Storage:
    def __init__(self, config, allow_http):
        self.config = config
        endpoint = config.get('endpoint') or ''
        if endpoint:
            parsed = urlparse(endpoint)
            if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
                raise ValueError('invalid endpoint')
            if parsed.scheme != 'https' and not (allow_http and parsed.scheme == 'http'):
                raise ValueError('TLS is required')
        self.env = dict(os.environ, AWS_ACCESS_KEY_ID=config['accessKey'], AWS_SECRET_ACCESS_KEY=config['secretKey'],
                        AWS_DEFAULT_REGION=config['region'], AWS_EC2_METADATA_DISABLED='true', AWS_PAGER='',
                        AWS_MAX_ATTEMPTS='3', AWS_CONFIG_FILE='/tmp/aws-config')
        Path('/tmp/aws-config').write_text('[default]\ns3 =\n    addressing_style = path\n')
        self.base = ['aws', '--cli-connect-timeout', '10', '--cli-read-timeout', '60']
        if endpoint:
            self.base += ['--endpoint-url', endpoint]

    def uri(self, key):
        prefix = self.config['prefix'].strip('/') + '/'
        if not key.startswith(prefix) or '..' in key.split('/'):
            raise ValueError('object outside owned prefix')
        return 's3://' + self.config['bucket'] + '/' + key

    def upload(self, path, key):
        run(self.base + ['s3', 'cp', str(path), self.uri(key), '--only-show-errors'], env=self.env)

    def download(self, key, path):
        run(self.base + ['s3', 'cp', self.uri(key), str(path), '--only-show-errors'], env=self.env)

    def delete(self, key):
        run(self.base + ['s3', 'rm', self.uri(key), '--only-show-errors'], env=self.env)


def pg_environment(connection):
    env = dict(os.environ, PGHOST=connection['host'], PGPORT=str(connection['port']),
               PGDATABASE=connection['database'], PGUSER=connection['username'], PGPASSWORD=connection['password'],
               PGSSLMODE=connection['sslMode'], PGCONNECT_TIMEOUT='10', PGAPPNAME='pgcp-backup-worker')
    if connection.get('caCertificate'):
        Path('/tmp/source-ca.crt').write_text(connection['caCertificate'])
        env['PGSSLROOTCERT'] = '/tmp/source-ca.crt'
    return env


def connect(connection):
    env = pg_environment(connection)
    kwargs = dict(host=env['PGHOST'], port=env['PGPORT'], dbname=env['PGDATABASE'], user=env['PGUSER'],
                  password=env['PGPASSWORD'], sslmode=env['PGSSLMODE'], connect_timeout=10,
                  application_name='pgcp-backup-worker', options='-c statement_timeout=60000 -c row_security=off')
    if env.get('PGSSLROOTCERT'):
        kwargs['sslrootcert'] = env['PGSSLROOTCERT']
    return psycopg2.connect(**kwargs)


def inventory(cursor):
    cursor.execute("SELECT n.nspname,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                   "WHERE c.relkind IN ('r','p','m') AND n.nspname NOT IN ('pg_catalog','information_schema') "
                   "AND n.nspname NOT LIKE 'pg_toast%' ORDER BY 1,2")
    tables = cursor.fetchall()
    if len(tables) > 2000:
        raise ValueError('verification inventory too large')
    result = []
    for schema, name in tables:
        cursor.execute(sql.SQL('SELECT count(*) FROM {}.{}').format(sql.Identifier(schema), sql.Identifier(name)))
        result.append({'schema': schema, 'name': name, 'rows': cursor.fetchone()[0]})
    return result


def backup(request, storage):
    connection = request['connection']
    progress(10, 'checking source access and snapshot')
    with connect(connection) as source:
        source.set_session(isolation_level='REPEATABLE READ', readonly=True)
        with source.cursor() as cursor:
            cursor.execute("SELECT current_setting('server_version_num')::int / 10000, pg_export_snapshot()")
            major, snapshot = cursor.fetchone()
            if major != request['pgMajor']:
                raise ValueError('source version changed')
            cursor.execute('SELECT extname,extversion FROM pg_extension ORDER BY extname')
            extensions = [{'name': name, 'version': version} for name, version in cursor.fetchall()]
            tables = inventory(cursor)
            manifest = {'format': 1, 'operationId': request['operationId'], 'pgMajor': major,
                        'database': connection['database'], 'tables': tables, 'extensions': extensions,
                        'ownerPolicy': 'map-to-new-owner', 'createdAt': int(time.time()),
                        'pgDumpVersion': run(['pg_dump', '--version']).decode().strip(),
                        'ageVersion': run(['age', '--version']).decode().strip()}
            progress(25, 'streaming database export through encryption')
            ciphertext = WORK / 'dump.age'
            with ciphertext.open('wb') as output:
                pipeline(['pg_dump', '-Fc', '--snapshot', snapshot, '--lock-wait-timeout=10s'],
                         ['age', '-r', request['storage']['recipient']], output,
                         first_env=pg_environment(connection))
    with ciphertext.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    manifest['sha256'] = digest
    manifest['sizeBytes'] = ciphertext.stat().st_size
    encrypted_manifest = WORK / 'manifest.age'
    encrypted_manifest.write_bytes(run(['age', '-r', request['storage']['recipient']],
                                       input_data=json.dumps(manifest).encode()))
    artifact = request['artifactKey']
    marker = request['manifestKey']
    progress(65, 'uploading encrypted backup')
    try:
        storage.upload(ciphertext, artifact)
        progress(85, 'publishing encrypted recovery manifest')
        storage.upload(encrypted_manifest, marker)
    except BaseException:
        # Only this operation's two generated, owned keys may be removed.
        for key in (marker, artifact):
            try:
                storage.delete(key)
            except Exception:
                pass
        raise
    finally:
        ciphertext.unlink(missing_ok=True)
        encrypted_manifest.unlink(missing_ok=True)
    return {'artifactKey': artifact, 'manifestKey': marker, 'sha256': digest,
            'sizeBytes': str(manifest['sizeBytes']), 'manifest': json.dumps(manifest)}


def restore(request, storage):
    progress(10, 'fetching encrypted recovery manifest')
    identity = Path('/tmp/recovery-key')
    identity.write_text(request['storage']['identity'])
    encrypted_manifest = WORK / 'manifest.age'
    storage.download(request['manifestKey'], encrypted_manifest)
    if encrypted_manifest.stat().st_size > 16 * 1024 * 1024:
        raise ValueError('manifest too large')
    manifest = json.loads(run(['age', '-d', '-i', str(identity), str(encrypted_manifest)]))
    if manifest['format'] != 1 or manifest['pgMajor'] != request['pgMajor'] or manifest['sha256'] != request['sha256']:
        raise ValueError('manifest mismatch')
    progress(25, 'fetching and checking encrypted backup')
    ciphertext = WORK / 'dump.age'
    storage.download(request['artifactKey'], ciphertext)
    with ciphertext.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != request['sha256']:
            raise ValueError('backup integrity check failed')
    with connect(request['connection']) as target:
        target.autocommit = True
        with target.cursor() as cursor:
            cursor.execute("SELECT current_setting('server_version_num')::int / 10000")
            if cursor.fetchone()[0] != manifest['pgMajor']:
                raise ValueError('target version mismatch')
            cursor.execute("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                           "WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' "
                           "AND c.relkind IN ('r','p','m','f','S')")
            if cursor.fetchone()[0] != 0:
                raise ValueError('restore target is not empty')
            for extension in manifest['extensions']:
                cursor.execute('SELECT v.installed,v.trusted,e.default_version FROM pg_available_extension_versions v '
                               'JOIN pg_available_extensions e USING(name) WHERE v.name=%s AND v.version=%s',
                               (extension['name'], extension['version']))
                availability = cursor.fetchone()
                if availability is None:
                    raise ValueError('required extension version unavailable')
                if not availability[0] and (not availability[1] or availability[2] != extension['version']):
                    raise ValueError('extension requires an unsupported privileged or non-default installation')
                # pg_restore creates trusted extensions as the mapped owner, in their original schema.
                # Precreating them as postgres would break ownership/comments and custom schemas.
    progress(50, 'decrypting and restoring into isolated destination')
    pipeline(['age', '-d', '-i', str(identity), str(ciphertext)],
             ['pg_restore', '--exit-on-error', '--no-owner', '--no-acl', '--role', request['targetOwner'],
              '--dbname', request['connection']['database']], second_env=pg_environment(request['connection']))
    progress(85, 'verifying restored tables and row counts')
    with connect(request['connection']) as target:
        with target.cursor() as cursor:
            actual = inventory(cursor)
            if actual != manifest['tables']:
                raise ValueError('restored table verification failed')
    ciphertext.unlink(missing_ok=True)
    encrypted_manifest.unlink(missing_ok=True)
    return {'verified': 'true', 'tablesChecked': str(len(actual)), 'rowsChecked': str(sum(t['rows'] for t in actual)),
            'ownerPolicy': 'map-to-new-owner'}


def storage_test(request, storage):
    key = request['storage']['prefix'].strip('/') + '/connection-tests/' + request['operationId']
    test = WORK / 'connection-test'
    test.write_bytes(os.urandom(32))
    expected = test.read_bytes()
    try:
        storage.upload(test, key)
        storage.download(key, test)
        if test.read_bytes() != expected:
            raise ValueError('storage read-back failed')
    finally:
        storage.delete(key)
        test.unlink(missing_ok=True)
    return {'verified': 'true'}


def expire(request, storage):
    # Delete only the validated UUID pair. Never list or recursively remove a prefix.
    import re
    base = storage.config['prefix'] + '/'
    name = request['artifactKey'].removeprefix(base)
    if not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\.dump\.age', name) or request['artifactKey'] != base + name:
        raise ValueError('invalid owned artifact')
    if request['manifestKey'] != base + name.replace('.dump.age', '.manifest.age'):
        raise ValueError('invalid manifest pair')
    progress(20, 'expiring owned backup')
    storage.delete(request['manifestKey'])
    storage.delete(request['artifactKey'])
    return {'expired': 'true'}


def main():
    os.umask(0o077)
    # Staged files are ciphertext. Bound each file and the overall operation runtime.
    resource.setrlimit(resource.RLIMIT_FSIZE, (4 * 1024**3, 4 * 1024**3))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('deadline')))
    signal.alarm(3600)
    request = json.loads(Path('/run/pgcp/request.json').read_text())
    try:
        storage = Storage(request['storage'], request.get('allowHttpStorage', False))
        action = request['action']
        result = {'BACKUP': backup, 'RESTORE': restore, 'TEST_STORAGE': storage_test, 'EXPIRE': expire}[action](request, storage)
        atomic(WORK / 'result.json', {'ok': True, 'result': result})
        progress(100, 'completed')
    except BaseException:
        for child in CHILDREN:
            if child.poll() is None:
                child.kill()
        atomic(WORK / 'result.json', {'ok': False, 'error': 'Backup worker failed during ' + STAGE + '. Check source permissions, compatibility and storage access.'})
        sys.exit(1)


if __name__ == '__main__':
    main()
