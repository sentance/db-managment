package dev.pgcp.agent.backups;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import tools.jackson.databind.json.JsonMapper;

/** Agent-owned daily schedules and durable result delivery. The panel is not part of the execution path. */
@Service
public class BackupPolicyService {
    record Active(UUID id, long revision, UUID destinationId, Instant slot, Instant queuedAt, LogicalBackupRequest request) { }
    record State(BackupPolicy policy, Instant nextRunAt, Active active, Instant lastRunAt,
            String lastStatus, Instant lastSuccessAt) { }
    private final Path policies, outbox;
    private final LogicalWorkerService workers;
    private final JsonMapper json;
    private final boolean allowHttp;
    private final Clock clock;
    private final Map<UUID,State> states = new LinkedHashMap<>();

    @Autowired
    public BackupPolicyService(AgentProperties props, LogicalWorkerService workers, JsonMapper json,
            @Value("${pgcp.backups.allow-http-storage:false}") boolean allowHttp) throws IOException {
        this(props.dataDir(), workers, json, allowHttp, Clock.systemUTC());
    }
    BackupPolicyService(Path root, LogicalWorkerService workers, JsonMapper json, boolean allowHttp, Clock clock) throws IOException {
        this.workers=workers;this.json=json;this.allowHttp=allowHttp;this.clock=clock;
        Path protection=root.resolve("protection");
        this.policies=protection.resolve("policies");this.outbox=protection.resolve("outbox");
        for (Path path:List.of(protection,policies,outbox)) {
            Files.createDirectories(path);
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
        }
        try(var files=Files.list(policies)) {
            for(Path file:files.filter(p->p.toString().endsWith(".json")).sorted().toList()) {
                State state=json.readValue(file.toFile(),State.class);
                states.put(state.policy().id(),state);
            }
        }
    }

    public synchronized BackupPolicyStatus put(BackupPolicy policy) {
        try { policy.validate(allowHttp); }
        catch(RuntimeException e) { throw AgentException.badRequest("invalid backup policy or export template"); }
        State old=states.get(policy.id());
        if(old!=null && (!old.policy().template().instanceId().equals(policy.template().instanceId())
                || !old.policy().template().connection().database().equals(policy.template().connection().database())
                || !old.policy().destinationId().equals(policy.destinationId()))) {
            throw AgentException.conflict("create a new policy when changing the database, instance or destination");
        }
        if(old!=null && policy.revision()<=old.policy().revision()) {
            if(!json.writeValueAsString(policy).equals(json.writeValueAsString(old.policy()))) {
                throw AgentException.conflict("policy revision must increase when changing credentials or settings");
            }
            return view(old);
        }
        Instant now=clock.instant();
        boolean scheduleChanged=old==null || !old.policy().dailyTime().equals(policy.dailyTime())
                || !old.policy().timezone().equals(policy.timezone()) || old.policy().enabled()!=policy.enabled();
        boolean rebound=old!=null && !old.policy().template().containerId().equals(policy.template().containerId());
        Instant next=scheduleChanged ? (old==null && policy.runImmediately() ? now : policy.nextAfter(now)) : old.nextRunAt();
        State state=new State(policy,next,old==null?null:old.active(),old==null?null:old.lastRunAt(),
                old==null || rebound?null:old.lastStatus(),old==null || rebound?null:old.lastSuccessAt());
        save(state);
        return view(state);
    }

    public synchronized List<BackupPolicyStatus> statuses() { return states.values().stream().map(this::view).toList(); }
    private BackupPolicyStatus view(State state) {
        String health=!state.policy().enabled()?"PAUSED": "FAILED".equals(state.lastStatus())?"BACKUP_FAILED":
                state.lastSuccessAt()==null ? (clock.instant().isAfter(state.nextRunAt().plusSeconds(7200))?"OVERDUE":"AWAITING_FIRST_BACKUP"):
                state.lastSuccessAt().plus(Duration.ofHours(state.policy().maxAgeHours())).isBefore(clock.instant())?"STALE":"HEALTHY";
        return new BackupPolicyStatus(state.policy().id(),state.policy().revision(),state.policy().enabled(),
                state.nextRunAt(),state.active()==null?null:state.active().id(),state.lastRunAt(),state.lastStatus(),state.lastSuccessAt(),health);
    }

    @Scheduled(fixedDelay=5000, initialDelay=3000)
    public synchronized void tick() {
        for(State state:List.copyOf(states.values())) {
            try { reconcile(state); }
            catch(Exception ignored) { /* Durable state remains pending during Docker/disk outages. Never log credentials. */ }
        }
    }
    private void reconcile(State state) throws Exception {
        Instant now=clock.instant();
        Active active=state.active();
        if(active==null) {
            if(!state.policy().enabled() || state.nextRunAt().isAfter(now)) return;
            UUID id=UUID.nameUUIDFromBytes((state.policy().id()+":"+state.nextRunAt()).getBytes(StandardCharsets.UTF_8));
            active=new Active(id,state.policy().revision(),state.policy().destinationId(),state.nextRunAt(),now,state.policy().request(id));
            state=new State(state.policy(),state.nextRunAt(),active,state.lastRunAt(),state.lastStatus(),state.lastSuccessAt());
            save(state); // Snapshot the consented credentials before launching or advancing the schedule.
        }
        Operation op;
        try { op=workers.get(active.id()); }
        catch(AgentException missing) {
            if(missing.kind()!=AgentException.Kind.NOT_FOUND) throw missing;
            if(active.queuedAt().plusSeconds(7200).isBefore(now)) {
                complete(state,"FAILED",Map.of(),active.queuedAt(),now);return;
            }
            try { op=workers.start(active.request()); }
            catch(AgentException busy) {
                if(busy.kind()!=AgentException.Kind.CONFLICT) throw busy;
                return; // Global worker/source lock or changed identity; never rebind automatically.
            }
        }
        if(op.status()==OperationStatus.RUNNING) return;
        complete(state,op.status()==OperationStatus.SUCCEEDED?"SUCCEEDED":"FAILED",op.result(),op.createdAt(),op.finishedAt());
    }
    private void complete(State state,String status,Map<String,String> result,Instant startedAt,Instant finishedAt) {
        Active active=state.active();
        var request=active.request();
        if("SUCCEEDED".equals(status) && (result.get("sha256")==null || !result.get("sha256").matches("[a-f0-9]{64}")
                || !request.artifactKey().equals(result.get("artifactKey")) || !request.manifestKey().equals(result.get("manifestKey")))) {
            status="FAILED";result=Map.of();
        }
        UUID eventId=UUID.nameUUIDFromBytes((active.id()+":completed").getBytes(StandardCharsets.UTF_8));
        var event=new BackupPolicyEvent(eventId,state.policy().id(),active.revision(),active.id(),request.instanceId(),
                active.destinationId(),request.connection().database(),request.containerId(),request.pgMajor(),status,result,startedAt,finishedAt);
        write(outbox.resolve(eventId+".json"),event); // Persist before clearing active: a crash can only replay this same event.
        boolean currentSource=state.policy().template().containerId().equals(request.containerId());
        save(new State(state.policy(),state.policy().nextAfter(clock.instant()),null,startedAt,currentSource?status:state.lastStatus(),
                currentSource && "SUCCEEDED".equals(status)?startedAt:state.lastSuccessAt()));
    }
    public synchronized List<BackupPolicyEvent> events() throws IOException {
        try(var files=Files.list(outbox)) {
            return files.filter(p->p.toString().endsWith(".json")).sorted().limit(100)
                    .map(p->json.readValue(p.toFile(),BackupPolicyEvent.class)).toList();
        }
    }
    public synchronized void acknowledge(UUID eventId) throws IOException { Files.deleteIfExists(outbox.resolve(eventId+".json")); }
    private void save(State state) {
        write(policies.resolve(state.policy().id()+".json"),state);
        states.put(state.policy().id(),state);
    }
    private void write(Path file,Object value) {
        try {
            Path tmp=file.resolveSibling(file.getFileName()+".tmp");
            Files.write(tmp,json.writeValueAsBytes(value),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) { throw new AgentException(AgentException.Kind.FAILED,"cannot persist protection state"); }
    }
}
