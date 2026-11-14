package dev.pgcp.agent.backups;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.agent.api.BackupCatalogue;
import dev.pgcp.agent.api.BackupInspectionRequest;
import dev.pgcp.agent.docker.DockerGateway;

@RestController
public class BackupInspectionController {
    private final DockerGateway docker;
    private final PgBackRestCatalogueParser parser;

    public BackupInspectionController(DockerGateway docker, PgBackRestCatalogueParser parser) {
        this.docker = docker;
        this.parser = parser;
    }

    @PostMapping("/v1/backups/inspect")
    public BackupCatalogue inspect(@RequestBody BackupInspectionRequest req) {
        return parser.parse(docker.pgBackRestInfo(req), req.stanza());
    }
}
