#!/usr/bin/env python3
"""Exercise the assembled application with fresh admin, PostgreSQL and S3 fixtures only."""
import base64
import hashlib
import hmac
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()


def main():
    tag = uuid.uuid4().hex[:10]
    project = 'pgcp-smoke-' + tag
    parent = ROOT / '.local-data' / 'smoke-tests'
    parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(dir=parent)).resolve()
    cp, agent, meta = (project + '-' + s for s in ('cp', 'agent', 'meta'))
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    base = 'http://127.0.0.1:' + str(port)
    source_password = secrets.token_hex(16)
    overlay = work / 'compose.yml'
    source_init = ROOT / 'deploy/fixtures/init.sql'
    target_name = 'smoke-restore-' + tag
    target_id = None
    target_operation = None
    created_network = False
    compose = ['docker', 'compose', '-p', project, '-f', str(ROOT / 'deploy/compose.yml'), '-f', str(overlay)]
    for name in ('install.sh', '.env.example'):
        shutil.copy(ROOT / 'deploy' / name, work / name)
    run('bash', str(work / 'install.sh'))
    try:
        run('docker', 'network', 'inspect', 'pgcp-instances')
    except subprocess.CalledProcessError:
        run('docker', 'network', 'create', '--label', 'pgcp.smoke=' + tag, 'pgcp-instances')
        created_network = True
    try:
        overlay.write_text(f'''services:
  meta:
    container_name: {meta}
    networks:
      pgcp-internal:
        aliases: [pgcp-meta]
  agent:
    container_name: {agent}
    image: {project}-agent
    environment:
      PGCP_DATA_DIR: {work}/runtime
      PGCP_HOST_PROC: /proc
      PGCP_HOST_ROOT: /
      PGCP_AGENT_CONTROL_PLANE_CONTAINER: {cp}
      PGCP_BACKUPS_ALLOW_HTTP_STORAGE: 'true'
      PGCP_BACKUPS_STORAGE_NETWORK: {project}_default
    volumes: !override
      - /var/run/docker.sock:/var/run/docker.sock
      - {work}/runtime:{work}/runtime
      - {work}/certs/ca.crt:/etc/pgcp/certs/ca.crt:ro
      - {work}/certs/agent.crt:/etc/pgcp/certs/agent.crt:ro
      - {work}/certs/agent.key:/etc/pgcp/certs/agent.key:ro
    networks:
      pgcp-internal:
        aliases: [pgcp-agent]
  control-plane:
    container_name: {cp}
    image: {project}-cp
    environment:
      PGCP_COOKIE_SECURE: 'false'
      PGCP_BACKUPS_ALLOW_HTTP_STORAGE: 'true'
    volumes: !override
      - pgcp-cp-data:/var/lib/pgcp-cp
      - {work}/certs/ca.crt:/etc/pgcp/certs/ca.crt:ro
      - {work}/certs/control-plane.crt:/etc/pgcp/certs/control-plane.crt:ro
      - {work}/certs/control-plane.key:/etc/pgcp/certs/control-plane.key:ro
    ports: !override
      - '127.0.0.1:{port}:8080'
    networks:
      default: {{}}
  source:
    image: postgres:18-trixie
    environment:
      POSTGRES_DB: fixture
      POSTGRES_PASSWORD: {source_password}
    volumes:
      - source-data:/var/lib/postgresql
      - {source_init}:/docker-entrypoint-initdb.d/fixture.sql:ro
    healthcheck:
      test: [CMD-SHELL, 'pg_isready -U postgres -d fixture']
      interval: 2s
      timeout: 5s
      retries: 30
  storage:
    image: motoserver/moto:5.1.16
    environment:
      MOTO_PORT: '5000'
networks:
  pgcp-internal:
    name: {project}-internal
  pgcp-instances:
    name: pgcp-instances
    external: true
  default: {{}}
volumes:
  pgcp-cp-data:
    name: {project}-cp-data
  pgcp-meta-data:
    name: {project}-meta-data
  source-data: {{}}
secrets:
  meta_password:
    file: {work}/secrets/meta_password
  master_key:
    file: {work}/secrets/master.key
''')
        (work / 'runtime').mkdir(mode=0o700)
        print('Building and starting disposable application stack', flush=True)
        run(*compose, 'up', '-d', '--build', '--wait', '--wait-timeout', '180')
        cookies = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        totp = None
        used_step = -1

        def code():
            nonlocal used_step
            while int(time.time()) // 30 <= used_step:
                time.sleep(0.5)
            used_step = int(time.time()) // 30
            key = base64.b32decode(totp + '=' * (-len(totp) % 8))
            digest = hmac.new(key, struct.pack('>Q', used_step), hashlib.sha1).digest()
            offset = digest[-1] & 15
            return '%06d' % ((struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7fffffff) % 1000000)

        def api(method, path, body=None):
            token = next((c.value for c in cookies if c.name == 'XSRF-TOKEN'), '')
            headers = {'Content-Type': 'application/json', 'X-XSRF-TOKEN': urllib.parse.unquote(token)}
            request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
            try:
                with opener.open(request, timeout=300) as response:
                    data = response.read()
                    return json.loads(data) if data else None
            except urllib.error.HTTPError as error:
                payload = json.loads(error.read())
                if payload.get('code') == 'STEP_UP_REQUIRED':
                    api('POST', '/api/auth/step-up', {'code': code()})
                    return api(method, path, body)
                raise RuntimeError('API failed: ' + method + ' ' + path + ' (' + str(error.code) + ')') from None

        def await_job(job):
            deadline = time.monotonic() + 1200
            while time.monotonic() < deadline:
                state = api('GET', '/api/jobs/' + job)['job']
                if state['status'] == 'SUCCEEDED':
                    return state
                if state['status'] == 'FAILED':
                    raise RuntimeError('Job failed: ' + state['type'] + ': ' + str(state.get('error')))
                time.sleep(1)
            raise RuntimeError('Job deadline exceeded')

        api('GET', '/api/auth/status')
        admin_password = secrets.token_hex(24)
        setup = api('POST', '/api/auth/setup', {'token': run('docker', 'exec', cp, 'cat', '/var/lib/pgcp-cp/setup-token'), 'username': 'smoke', 'password': admin_password})
        totp = setup['totpSecret']
        api('POST', '/api/auth/setup/totp', {'code': code()})
        source = run(*compose, 'ps', '-q', 'source')
        source_name = run('docker', 'inspect', '--format', '{{.Name}}', source).lstrip('/')
        instance = api('POST', '/api/instances/external', {'name': 'smoke-source', 'containerName': source_name, 'host': source_name, 'port': 5432, 'username': 'postgres', 'password': source_password, 'sslMode': 'disable'})
        print('Creating recovery key and testing S3 destination', flush=True)
        destination = api('POST', '/api/backups/destinations', {'name': 'smoke storage', 'endpoint': 'http://storage:5000', 'region': 'us-east-1', 'bucket': 'pgcp-smoke', 'accessKey': 'test', 'secretKey': 'test'})['destination']
        digest = hashlib.sha256()
        for path in sorted((ROOT / 'images/backup-worker').iterdir()):
            if path.is_file():
                digest.update(path.name.encode() + b'\0' + path.read_bytes() + b'\0')
        image = 'pgcp-backup-worker:18-' + digest.hexdigest()[:12]
        run('docker', 'run', '--rm', '--network', project + '_default', '-e', 'AWS_ACCESS_KEY_ID=test', '-e', 'AWS_SECRET_ACCESS_KEY=test', '-e', 'AWS_DEFAULT_REGION=us-east-1', '--entrypoint', 'aws', image, '--endpoint-url', 'http://storage:5000', 's3api', 'create-bucket', '--bucket', 'pgcp-smoke')
        await_job(api('POST', '/api/backups/destinations/' + destination['id'] + '/test', {})['jobId'])
        print('Creating encrypted backup through application API', flush=True)
        backup = api('POST', '/api/backups/logical/' + instance['id'], {'database': 'fixture', 'destinationId': destination['id'], 'containerId': source})
        await_job(backup['jobId'])
        print('Scheduling a backup, stopping the panel and restarting the agent', flush=True)
        daily_time = time.strftime('%H:%M', time.gmtime(time.time() + 120))
        policy = api('POST', '/api/backups/policies', {'name': 'Offline schedule', 'instanceId': instance['id'], 'database': 'fixture',
            'destinationId': destination['id'], 'containerId': source, 'dailyTime': daily_time, 'timezone': 'UTC',
            'enabled': True, 'runImmediately': False, 'maxAgeHours': 26})
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            saved = next(p for p in api('GET', '/api/backups/policies') if p['id'] == policy['id'])
            if saved['syncState'] == 'APPLIED':
                assert saved['execution']['activeRunId'] is None, 'The panel must stop before the scheduled run starts'
                break
            time.sleep(1)
        else:
            raise RuntimeError('Policy was not acknowledged by the agent')
        run(*compose, 'stop', 'control-plane')
        run(*compose, 'restart', 'agent')
        events_path = str(work / 'runtime' / 'protection' / 'outbox')
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            files = run('docker', 'exec', agent, 'find', events_path, '-name', '*.json').splitlines()
            if files:
                assert len(files) == 1
                event = json.loads(run('docker', 'exec', agent, 'cat', files[0]))
                assert event['policyId'] == policy['id'] and event['status'] == 'SUCCEEDED'
                assert len(event['result']['sha256']) == 64
                break
            time.sleep(1)
        else:
            raise RuntimeError('Agent did not complete the backup while the panel was stopped')
        print('Replaying the durable result after another agent restart and panel restart', flush=True)
        run(*compose, 'restart', 'agent')
        assert len(run('docker', 'exec', agent, 'find', events_path, '-name', '*.json').splitlines()) == 1
        run(*compose, 'up', '-d', '--wait', '--wait-timeout', '120', 'control-plane')
        # Sessions are in memory, so authenticate again after the panel restart.
        api('GET', '/api/auth/status')
        api('POST', '/api/auth/login', {'username': 'smoke', 'password': admin_password, 'code': code()})
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            history = api('GET', '/api/backups/logical')
            scheduled = [b for b in history if b['id'] == event['backupId']]
            if scheduled:
                assert len(scheduled) == 1 and scheduled[0]['status'] == 'SUCCEEDED'
                assert len(history) == 2
                assert not scheduled[0]['jobId']
                break
            time.sleep(1)
        else:
            raise RuntimeError('Panel did not import the durable scheduled result')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if not run('docker', 'exec', agent, 'find', events_path, '-name', '*.json'):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Panel did not acknowledge the imported result')
        api('PUT', '/api/backups/policies/' + policy['id'] + '/enabled', {'enabled': False})
        run('docker', 'exec', '-u', 'postgres', source, 'psql', '-X', '-d', 'fixture', '-c', "insert into orders(description) values ('after backup')")
        print('Restoring through application API and verifying isolation', flush=True)
        restored = api('POST', '/api/backups/logical/' + backup['id'] + '/restore', {'instanceName': target_name, 'database': 'restored', 'owner': 'restored_owner'})
        target_operation = restored['id']
        await_job(restored['jobId'])
        record = next(r for r in api('GET', '/api/backups/restores') if r['id'] == restored['id'])
        target_id = record['targetInstanceId']
        assert record['result']['verified'] == 'true'
        target = 'pgcp-inst-' + target_name
        assert run('docker', 'exec', '-u', 'postgres', target, 'psql', '-X', '-d', 'restored', '-Atc', "select count(*),nextval('orders_id_seq') from orders") == '3|4'
        networks = json.loads(run('docker', 'inspect', '--format', '{{json .NetworkSettings.Networks}}', target))
        assert list(networks) == ['pgcp-restore-' + restored['id']]
        await_job(api('POST', '/api/instances/' + target_id + '/delete', {'confirmName': target_name, 'deleteVolume': True})['jobId'])
        print('ASSEMBLED LOGICAL BACKUP/RESTORE AND OFFLINE SCHEDULE SMOKE TEST PASSED', flush=True)
    finally:
        # Labels and a unique generated name limit fallback cleanup to this test's destination.
        if target_operation:
            target = 'pgcp-inst-' + target_name
            try:
                label = run('docker', 'inspect', '--format', '{{index .Config.Labels "pgcp.restore-operation"}}', target)
                if label == target_operation:
                    run('docker', 'rm', '-f', target)
                    run('docker', 'volume', 'rm', target + '-data')
            except subprocess.CalledProcessError:
                pass
        try:
            run(*compose, 'down', '--volumes', '--remove-orphans')
            if target_operation:
                run('docker', 'network', 'rm', 'pgcp-restore-' + target_operation)
        except subprocess.CalledProcessError:
            pass
        if created_network:
            run('docker', 'network', 'rm', 'pgcp-instances')
        # Agent-created files are root-owned on native Linux. Delete only this test's runtime mount.
        run('docker','run','--rm','--network','none','--mount','type=bind,src='+str(work)+',dst=/cleanup',
            '--entrypoint','rm','docker:29-cli','-rf','/cleanup/runtime')
        shutil.rmtree(work)


if __name__ == '__main__':
    main()
