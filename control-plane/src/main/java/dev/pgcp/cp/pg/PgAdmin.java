package dev.pgcp.cp.pg;

import static dev.pgcp.cp.pg.PgIdent.quote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.postgresql.PGConnection;

/**
 * Administrative SQL against managed clusters. All identifiers are quoted with {@link PgIdent#quote}; all values are
 * bind parameters. Passwords are sent as SCRAM verifiers computed client-side, so plaintext never reaches the server
 * log or pg_stat_statements.
 */
public final class PgAdmin {

    public enum Access {
        READ_ONLY,
        READ_WRITE
    }

    public record DatabaseInfo(String name, String owner, long sizeBytes, int connections, boolean allowConnections,
            String encoding, String collation) {
    }

    public record RoleInfo(String name, boolean canLogin, boolean superuser, boolean createDb, boolean createRole,
            int connectionLimit, List<String> memberOf, List<String> databases) {
    }

    public record Dependency(String database, String kind, String catalog, long count) {
    }

    public record Extension(String name, String version, String schema) {
    }

    private PgAdmin() {
    }

    public static int serverVersionNum(Connection c) throws SQLException {
        return queryInt(c, "select current_setting('server_version_num')::int");
    }

    public static String serverVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select version()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    public static boolean isSuperuser(Connection c) throws SQLException {
        return queryInt(c, "select case when rolsuper then 1 else 0 end from pg_roles where rolname = current_user") == 1;
    }

    public static boolean roleExists(Connection c, String role) throws SQLException {
        return exists(c, "select 1 from pg_roles where rolname = ?", role);
    }

    public static boolean databaseExists(Connection c, String db) throws SQLException {
        return exists(c, "select 1 from pg_database where datname = ?", db);
    }

    /** Creates a least-privilege login role if missing, sets its password, and lets the admin role act as it. */
    public static void ensureLoginRole(Connection c, String role, String password) throws SQLException {
        if (!roleExists(c, role)) {
            exec(c, "create role " + quote(role)
                    + " login nosuperuser nocreatedb nocreaterole noreplication nobypassrls inherit");
        }
        setPassword(c, role, password);
        grantRoleToCurrentUser(c, role);
    }

    /** The technical role of a managed instance: CREATEDB + CREATEROLE + monitoring, deliberately not superuser. */
    public static void ensureAdminRole(Connection superuser, String role, String password) throws SQLException {
        if (!roleExists(superuser, role)) {
            exec(superuser, "create role " + quote(role) + " login createdb createrole nosuperuser noreplication");
        }
        exec(superuser, "grant pg_monitor, pg_signal_backend to " + quote(role));
        setPassword(superuser, role, password);
    }

    public static void setPassword(Connection c, String role, String password) throws SQLException {
        c.unwrap(PGConnection.class).alterUserPassword(role, password.toCharArray(), "scram-sha-256");
    }

    /**
     * Makes the (non-superuser) admin role a member of a role it manages, so it can create databases owned by it,
     * set default privileges and drop its objects. PostgreSQL 16 changed CREATEROLE semantics and needs explicit options.
     */
    public static void grantRoleToCurrentUser(Connection c, String role) throws SQLException {
        if (isSuperuser(c)) {
            return;
        }
        if (exists(c, """
                select 1 from pg_auth_members m join pg_roles r on r.oid = m.roleid join pg_roles u on u.oid = m.member
                where r.rolname = ? and u.rolname = current_user
                """ + (serverVersionNum(c) >= 160000 ? " and m.set_option and m.inherit_option" : ""), role)) {
            return;
        }
        if (serverVersionNum(c) >= 160000) {
            exec(c, "grant " + quote(role) + " to current_user with inherit true, set true");
        } else {
            exec(c, "grant " + quote(role) + " to current_user");
        }
    }

    public static void createDatabase(Connection c, String db, String owner) throws SQLException {
        if (!databaseExists(c, db)) {
            exec(c, "create database " + quote(db) + " owner " + quote(owner));
        }
        // Least privilege: only explicitly granted roles may connect.
        exec(c, "revoke all on database " + quote(db) + " from public");
        exec(c, "grant connect, temporary on database " + quote(db) + " to " + quote(owner));
    }

    /** PostgreSQL 14 still gives PUBLIC CREATE on schema public and makes it owned by the bootstrap superuser. */
    public static void securePublicSchemaPg14(Connection superuserInDb, String owner) throws SQLException {
        exec(superuserInDb, "revoke create on schema public from public");
        exec(superuserInDb, "alter schema public owner to " + quote(owner));
    }

    /** Grants access on all schemas owned by the database owner (plus public), now and for future objects. */
    public static void grantAccess(Connection inDb, String db, String owner, String role, Access access) throws SQLException {
        exec(inDb, "grant connect on database " + quote(db) + " to " + quote(role));
        String tablePrivs = access == Access.READ_ONLY ? "select" : "select, insert, update, delete";
        String seqPrivs = access == Access.READ_ONLY ? "select" : "usage, select, update";
        asRole(inDb, owner, () -> {
            for (String schema : ownedSchemas(inDb, owner)) {
                String s = quote(schema);
                String r = quote(role);
                exec(inDb, "grant usage on schema " + s + " to " + r);
                exec(inDb, "grant " + tablePrivs + " on all tables in schema " + s + " to " + r);
                exec(inDb, "grant " + seqPrivs + " on all sequences in schema " + s + " to " + r);
                exec(inDb, "alter default privileges in schema " + s + " grant " + tablePrivs + " on tables to " + r);
                exec(inDb, "alter default privileges in schema " + s + " grant " + seqPrivs + " on sequences to " + r);
            }
        });
    }

    public static void revokeAccess(Connection inDb, String db, String owner, String role) throws SQLException {
        asRole(inDb, owner, () -> {
            for (String schema : ownedSchemas(inDb, owner)) {
                String s = quote(schema);
                String r = quote(role);
                exec(inDb, "alter default privileges in schema " + s + " revoke all on tables from " + r);
                exec(inDb, "alter default privileges in schema " + s + " revoke all on sequences from " + r);
                exec(inDb, "revoke all on all tables in schema " + s + " from " + r);
                exec(inDb, "revoke all on all sequences in schema " + s + " from " + r);
                exec(inDb, "revoke usage on schema " + s + " from " + r);
            }
        });
        exec(inDb, "revoke connect, temporary on database " + quote(db) + " from " + quote(role));
    }

    public static void setLogin(Connection c, String role, boolean login) throws SQLException {
        exec(c, "alter role " + quote(role) + (login ? " login" : " nologin"));
    }

    public static int terminateSessions(Connection c, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select count(pg_terminate_backend(pid)) from pg_stat_activity where usename = ? and pid <> pg_backend_pid()")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static int connectionCount(Connection c, String db) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select count(*) from pg_stat_activity where datname = ? and pid <> pg_backend_pid()")) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static void dropDatabase(Connection c, String db, boolean force) throws SQLException {
        exec(c, "drop database if exists " + quote(db) + (force ? " with (force)" : ""));
    }

    public static void renameDatabase(Connection c, String from, String to) throws SQLException {
        exec(c, "alter database " + quote(from) + " rename to " + quote(to));
    }

    /** Revokes privileges held by a role that owns nothing in this database, then the role can be dropped. */
    public static void dropOwnedPrivileges(Connection inDb, String role) throws SQLException {
        exec(inDb, "drop owned by " + quote(role));
    }

    public static void dropRole(Connection c, String role) throws SQLException {
        exec(c, "drop role if exists " + quote(role));
    }

    public static List<DatabaseInfo> databases(Connection c) throws SQLException {
        List<DatabaseInfo> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                select d.datname, pg_get_userbyid(d.datdba) as owner,
                       case when has_database_privilege(d.datname, 'CONNECT') then pg_database_size(d.datname) else -1 end as size,
                       (select count(*) from pg_stat_activity a where a.datname = d.datname) as connections,
                       d.datallowconn, pg_encoding_to_char(d.encoding) as encoding, d.datcollate
                from pg_database d where not d.datistemplate order by d.datname
                """)) {
            while (rs.next()) {
                out.add(new DatabaseInfo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getBoolean(5),
                        rs.getString(6), rs.getString(7)));
            }
        }
        return out;
    }

    public static List<Extension> extensions(Connection inDb) throws SQLException {
        List<Extension> out = new ArrayList<>();
        try (Statement s = inDb.createStatement(); ResultSet rs = s.executeQuery("""
                select e.extname, e.extversion, n.nspname from pg_extension e join pg_namespace n on n.oid = e.extnamespace
                order by e.extname
                """)) {
            while (rs.next()) {
                out.add(new Extension(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return out;
    }

    public static List<RoleInfo> roles(Connection c) throws SQLException {
        List<RoleInfo> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                select r.rolname, r.rolcanlogin, r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolconnlimit,
                       coalesce(array(select g.rolname from pg_auth_members m join pg_roles g on g.oid = m.roleid
                                      where m.member = r.oid order by 1), '{}') as member_of,
                       coalesce(array(select d.datname from pg_database d
                                      where not d.datistemplate and has_database_privilege(r.oid, d.oid, 'CONNECT')
                                      order by 1), '{}') as databases
                from pg_roles r where r.rolname !~ '^pg_' order by r.rolname
                """)) {
            while (rs.next()) {
                String[] memberOf = (String[]) rs.getArray(7).getArray();
                String[] databases = (String[]) rs.getArray(8).getArray();
                out.add(new RoleInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getInt(6), List.of(memberOf), List.of(databases)));
            }
        }
        return out;
    }

    /** Databases the role may connect to (explicit CONNECT privilege or ownership). */
    public static List<String> connectableDatabases(Connection c, String role) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select datname from pg_database
                where not datistemplate and datallowconn and has_database_privilege(?, datname, 'CONNECT') order by 1
                """)) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    public static List<String> loginRolesWithConnect(Connection c, String db) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select rolname from pg_roles
                where rolcanlogin and rolname !~ '^pg_' and has_database_privilege(oid, (select oid from pg_database where datname = ?), 'CONNECT')
                order by 1
                """)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** Everything that would block DROP ROLE, grouped by database: owned objects and granted privileges. */
    public static List<Dependency> dependencies(Connection c, String role) throws SQLException {
        List<Dependency> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select coalesce(d.datname, '(cluster)') as database,
                       case s.deptype when 'o' then 'OWNER' when 'a' then 'PRIVILEGE' when 'r' then 'POLICY'
                                      when 'i' then 'INITIAL_PRIVILEGE' else s.deptype::text end as kind,
                       s.classid::regclass::text as catalog, count(*) as n
                from pg_shdepend s left join pg_database d on d.oid = s.dbid
                where s.refclassid = 'pg_authid'::regclass
                  and s.refobjid = (select oid from pg_roles where rolname = ?)
                group by 1, 2, 3 order by 1, 2, 3
                """)) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Dependency(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        }
        return out;
    }

    private static List<String> ownedSchemas(Connection inDb, String owner) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = inDb.prepareStatement("""
                select nspname from pg_namespace
                where (nspowner = (select oid from pg_roles where rolname = ?) or nspname = 'public')
                  and nspname !~ '^pg_' and nspname <> 'information_schema' order by 1
                """)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    /** Runs statements as another role so grants and default privileges are recorded with that role as grantor. */
    private static void asRole(Connection c, String role, SqlAction action) throws SQLException {
        exec(c, "set role " + quote(role));
        try {
            action.run();
        } finally {
            exec(c, "reset role");
        }
    }

    private static boolean exists(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
