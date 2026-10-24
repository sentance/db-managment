package dev.pgcp.cp.pg;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import dev.pgcp.cp.common.ApiException;

/**
 * PostgreSQL identifier handling. Identifiers cannot be bind parameters, so every identifier that reaches SQL text
 * goes through {@link #quote}. Names chosen through pgcp are additionally restricted by {@link #requireNewName}.
 */
public final class PgIdent {

    private static final Pattern NEW_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Set<String> RESERVED = Set.of("postgres", "template0", "template1", "public", "pgcp_admin",
            "none", "current_user", "session_user", "current_role", "all");

    private PgIdent() {
    }

    /** Validates a name for an object pgcp is about to create: lowercase, no quoting surprises, not reserved. */
    public static String requireNewName(String name, String what) {
        if (name == null || !NEW_NAME.matcher(name).matches()) {
            throw ApiException.badRequest(what + " name must match " + NEW_NAME.pattern());
        }
        if (RESERVED.contains(name) || name.startsWith("pg_")) {
            throw ApiException.badRequest(what + " name '" + name + "' is reserved");
        }
        return name;
    }

    /** Quotes any identifier (including ones read from catalogs) as a delimited identifier. */
    public static String quote(String ident) {
        if (ident == null || ident.isEmpty() || ident.indexOf('\0') >= 0
                || ident.getBytes(StandardCharsets.UTF_8).length > 63) {
            throw ApiException.badRequest("invalid identifier");
        }
        return '"' + ident.replace("\"", "\"\"") + '"';
    }

    public static boolean isSystemRole(String role) {
        return role.startsWith("pg_") || RESERVED.contains(role);
    }
}
