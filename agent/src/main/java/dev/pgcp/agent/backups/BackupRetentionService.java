package dev.pgcp.agent.backups;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.*;
import tools.jackson.databind.json.JsonMapper;

/** Only entries recorded after successful pgcp scheduled exports are eligible. No repository discovery/deletion. */
@Service
public class BackupRetentionService {
    record Entry(BackupPolicyEvent backup,String status,Set<UUID> pins,Instant verifiedAt,int attempt,Instant retryAt,boolean failed) { }
    private final Path root,outbox;
    private final LogicalWorkerService workers;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    @Autowired public BackupRetentionService(AgentProperties props,LogicalWorkerService workers,JsonMapper json) throws IOException {
        this(props.dataDir(),workers,json,Clock.systemUTC());
    }
    BackupRetentionService(Path data,LogicalWorkerService workers,JsonMapper json,Clock clock) throws IOException {
        this.workers=workers;this.json=json;this.clock=clock;
        root=data.resolve("protection/retention");outbox=data.resolve("protection/outbox");
        for(Path path:List.of(data.resolve("protection"),root,outbox)) {
            Files.createDirectories(path);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
        }
        try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.toString().endsWith(".json")).toList()) {
                Entry e=json.readValue(file.toFile(),Entry.class);entries.put(e.backup().backupId(),e);
            }
        }
    }
    public synchronized void record(BackupPolicyEvent event) {
        if("SUCCEEDED".equals(event.status()) && !entries.containsKey(event.backupId())) {
            save(new Entry(event,"AVAILABLE",Set.of(),null,0,null,false));
        }
    }
    /** Unknown entries are manual/pre-upgrade backups, which this registry cannot expire. */
    public synchronized boolean pin(UUID backup,UUID restore) {
        if(backup==null || restore==null) throw AgentException.badRequest("backup and restore IDs are required");
        Entry e=entries.get(backup);if(e==null) return false;
        if(!e.status().equals("AVAILABLE")) throw AgentException.conflict("backup expiration has started; select another recovery point");
        Set<UUID> pins=new HashSet<>(e.pins());pins.add(restore);
        save(new Entry(e.backup(),e.status(),Set.copyOf(pins),e.verifiedAt(),e.attempt(),e.retryAt(),e.failed()));return true;
    }
    public synchronized void release(UUID backup,UUID restore,boolean verified) {
        if(backup==null || restore==null) throw AgentException.badRequest("backup and restore IDs are required");
        Entry e=entries.get(backup);if(e==null) return;
        if(verified && !e.pins().contains(restore)) {
            // A completed replay may already have released the pin.
            if(e.verifiedAt()!=null) return;
            throw AgentException.conflict("verified restore must hold its backup pin");
        }
        Set<UUID> pins=new HashSet<>(e.pins());pins.remove(restore);
        save(new Entry(e.backup(),e.status(),Set.copyOf(pins),verified?clock.instant():e.verifiedAt(),e.attempt(),e.retryAt(),e.failed()));
    }
    public synchronized String health(UUID policy) {
        return entries.values().stream().anyMatch(e->e.backup().policyId().equals(policy)&&e.failed())?"ERROR":"OK";
    }
    public synchronized void reconcile(BackupPolicy policy) throws Exception {
        // Once deletion is committed it must finish, even if the policy is subsequently paused/retention disabled.
        for(Entry e:List.copyOf(entries.values())) {
            if(e.backup().policyId().equals(policy.id()) && e.status().equals("EXPIRING")) { expire(e,policy);return; }
        }
        if(!policy.enabled() || policy.keepLast()==0) return;
        List<Entry> saved=entries.values().stream().filter(e->e.backup().policyId().equals(policy.id()) && e.status().equals("AVAILABLE"))
                .sorted(Comparator.comparing((Entry e)->e.backup().startedAt()).reversed().thenComparing(e->e.backup().backupId())).toList();
        UUID verified=saved.stream().filter(e->e.verifiedAt()!=null).max(Comparator.comparing(e->e.backup().startedAt()))
                .map(e->e.backup().backupId()).orElse(null);
        for(Entry e:saved.stream().skip(policy.keepLast()).toList().reversed()) {
            if(e.pins().isEmpty() && !e.backup().backupId().equals(verified)
                    && e.backup().startedAt().plus(Duration.ofDays(policy.keepDays())).isBefore(clock.instant())) {
                Entry expiring=new Entry(e.backup(),"EXPIRING",e.pins(),e.verifiedAt(),0,clock.instant(),false);
                save(expiring);event(expiring,"EXPIRING");expire(expiring,policy);return;
            }
        }
    }
    private void expire(Entry e,BackupPolicy policy) throws Exception {
        if(e.retryAt()!=null && e.retryAt().isAfter(clock.instant())) return;
        event(e,"EXPIRING"); // Also repairs a crash between saving EXPIRING and publishing it.
        UUID operation=UUID.nameUUIDFromBytes((e.backup().backupId()+":expire:"+e.attempt()).getBytes(StandardCharsets.UTF_8));
        Operation op;
        try { op=workers.get(operation); }
        catch(AgentException missing) {
            if(missing.kind()!=AgentException.Kind.NOT_FOUND) { uncertain(e);return; }
            var storage=policy.template().storage();
            var request=new LogicalBackupRequest(operation,LogicalBackupRequest.Action.EXPIRE,e.backup().instanceId(),null,null,
                    e.backup().pgMajor(),null,storage,e.backup().result().get("artifactKey"),e.backup().result().get("manifestKey"),null,null);
            try { op=workers.start(request); }
            catch(AgentException busy) { if(busy.kind()!=AgentException.Kind.CONFLICT) fail(e);return; }
            catch(Exception failure) { fail(e);return; }
        }
        catch(RuntimeException unavailable) { uncertain(e);return; }
        if(op.status()==OperationStatus.RUNNING) return;
        if(op.status()==OperationStatus.SUCCEEDED && "true".equals(op.result().get("expired"))) {
            Entry expired=new Entry(e.backup(),"EXPIRED",Set.of(),e.verifiedAt(),e.attempt(),null,false);
            event(expired,"EXPIRED");save(expired);
        } else fail(e);
    }
    private void fail(Entry e) {
        save(new Entry(e.backup(),"EXPIRING",e.pins(),e.verifiedAt(),e.attempt()+1,
                clock.instant().plusSeconds(Math.min(3600,30L << Math.min(7,e.attempt()))),true));
    }
    private void uncertain(Entry e) {
        // Keep the same operation ID when its outcome is unknown; never launch a replacement delete concurrently.
        save(new Entry(e.backup(),"EXPIRING",e.pins(),e.verifiedAt(),e.attempt(),clock.instant().plusSeconds(30),true));
    }
    private void event(Entry e,String status) {
        var b=e.backup();UUID id=UUID.nameUUIDFromBytes((b.backupId()+":"+status).getBytes(StandardCharsets.UTF_8));
        write(outbox.resolve(id+".json"),new BackupPolicyEvent(id,b.policyId(),b.revision(),b.backupId(),b.instanceId(),b.destinationId(),
                b.database(),b.containerId(),b.pgMajor(),status,b.result(),b.startedAt(),clock.instant()));
    }
    private void save(Entry e) { write(root.resolve(e.backup().backupId()+".json"),e);entries.put(e.backup().backupId(),e); }
    private void write(Path file,Object value) {
        try {
            Path tmp=file.resolveSibling(file.getFileName()+".tmp");Files.write(tmp,json.writeValueAsBytes(value));
            Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) { throw new AgentException(AgentException.Kind.FAILED,"cannot persist backup retention state"); }
    }
}
