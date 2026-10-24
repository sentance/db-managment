package dev.pgcp.cp.databases;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.cp.jobs.JobService;

@RestController
@RequestMapping("/api/instances/{instanceId}/databases")
public class DatabaseController {

    public record DropRequest(String confirmName, boolean terminateConnections) {
    }

    public record RenameRequest(String newName) {
    }

    private final DatabaseService databases;

    public DatabaseController(DatabaseService databases) {
        this.databases = databases;
    }

    @GetMapping
    public List<DatabaseService.DatabaseView> list(@PathVariable UUID instanceId) {
        return databases.list(instanceId);
    }

    @GetMapping("/{name}")
    public DatabaseService.DatabaseDetails details(@PathVariable UUID instanceId, @PathVariable String name) {
        return databases.details(instanceId, name);
    }

    /** The response carries the generated passwords exactly once. */
    @PostMapping
    public ResponseEntity<DatabaseService.CreateResult> create(@PathVariable UUID instanceId,
            @RequestBody DatabaseService.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(databases.create(instanceId, req));
    }

    @PostMapping("/{name}/drop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobService.Submitted drop(@PathVariable UUID instanceId, @PathVariable String name, @RequestBody DropRequest req) {
        return new JobService.Submitted(databases.drop(instanceId, name, req.confirmName(), req.terminateConnections()));
    }

    @PostMapping("/{name}/rename")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobService.Submitted rename(@PathVariable UUID instanceId, @PathVariable String name, @RequestBody RenameRequest req) {
        return new JobService.Submitted(databases.rename(instanceId, name, req.newName()));
    }

    @GetMapping("/{name}/connection")
    public ConnectionInfoService.ConnectionInfo connection(@PathVariable UUID instanceId, @PathVariable String name,
            @RequestParam(required = false) String role) {
        return databases.connection(instanceId, name, role);
    }
}
