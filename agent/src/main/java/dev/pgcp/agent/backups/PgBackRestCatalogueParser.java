package dev.pgcp.agent.backups;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.pgcp.agent.api.BackupCatalogue;
import dev.pgcp.agent.config.AgentException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PgBackRestCatalogueParser {
    private static final Pattern LABEL = Pattern.compile("[0-9]{8}-[0-9]{6}F(?:_[0-9]{8}-[0-9]{6}[DI])?");
    private static final Pattern ARCHIVE_ID = Pattern.compile("[0-9]+-[0-9]+");
    private static final Pattern WAL = Pattern.compile("[0-9A-F]{24}");
    private final JsonMapper json;

    public PgBackRestCatalogueParser(JsonMapper json) { this.json = json; }

    public BackupCatalogue parse(String payload, String stanza) {
        try {
            JsonNode root = json.readTree(payload);
            require(root.isArray() && root.size() == 1);
            JsonNode s = root.get(0);
            require(stanza.equals(s.path("name").asString()));
            int status = code(s.path("status"));
            List<BackupCatalogue.Repository> repos = new ArrayList<>();
            require(s.path("repo").isArray() && !s.path("repo").isEmpty());
            for (JsonNode repo : s.path("repo")) {
                long key = number(repo.path("key"));
                require(key > 0 && key <= Integer.MAX_VALUE);
                String cipher = repo.path("cipher").asString();
                if (!Set.of("none", "aes-256-cbc").contains(cipher)) { cipher = "unknown"; }
                repos.add(new BackupCatalogue.Repository((int) key, code(repo.path("status")), cipher));
            }
            List<BackupCatalogue.Archive> archives = new ArrayList<>();
            if (s.has("archive")) {
                require(s.path("archive").isArray());
                for (JsonNode archive : s.path("archive")) {
                    String id = archive.path("id").asString();
                    require(ARCHIVE_ID.matcher(id).matches());
                    archives.add(new BackupCatalogue.Archive(id, wal(archive.path("min")), wal(archive.path("max"))));
                }
            }
            List<BackupCatalogue.Backup> backups = new ArrayList<>();
            if (s.has("backup")) {
                require(s.path("backup").isArray() && s.path("backup").size() <= 10_000);
                for (JsonNode b : s.path("backup")) {
                    String label = b.path("label").asString();
                    String type = b.path("type").asString();
                    require(LABEL.matcher(label).matches() && Set.of("full", "diff", "incr").contains(type));
                    long start = number(b.path("timestamp").path("start"));
                    long stop = number(b.path("timestamp").path("stop"));
                    require(start > 0 && stop >= start);
                    JsonNode error = b.path("error");
                    require(error.isMissingNode() || error.isBoolean() || error.isNull());
                    backups.add(new BackupCatalogue.Backup(label, type, Instant.ofEpochSecond(start),
                            Instant.ofEpochSecond(stop), number(b.path("info").path("size")),
                            number(b.path("info").path("repository").path("size")), error.asBoolean(false)));
                }
            }
            return new BackupCatalogue(stanza, status, List.copyOf(repos), List.copyOf(archives), List.copyOf(backups));
        } catch (RuntimeException e) {
            // Parser exceptions can contain source payloads, so never return or log them.
            throw new AgentException(AgentException.Kind.FAILED, "unrecognised pgBackRest catalogue format");
        }
    }

    private static int code(JsonNode n) {
        long code = number(n.path("code"));
        require(code <= Integer.MAX_VALUE);
        return (int) code;
    }

    private static long number(JsonNode n) {
        require(n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= 0);
        return n.asLong();
    }

    private static String wal(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) { return null; }
        String value = n.asString();
        require(WAL.matcher(value).matches());
        return value;
    }

    private static void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException("invalid catalogue"); }
    }
}
