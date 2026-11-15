package dev.pgcp.cp.notifications;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {
    public record Config(boolean enabled,Long tokenSecretId,String chatId,long revision) { }
    public record Alert(UUID id,String kind,UUID resourceId,String message,boolean active,int generation,
            Instant openedAt,Instant changedAt,Instant resolvedAt) { }
    public record Delivery(UUID id,UUID alertId,int generation,long configRevision,String message,String status,int attempts,
            Instant nextAttemptAt,Instant sentAt,String lastError) { }
    private final JdbcClient jdbc;
    public NotificationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Config config() { return jdbc.sql("select enabled,token_secret_id,chat_id,revision from telegram_settings where id=1").query(Config.class).single(); }
    public void lockConfig() { jdbc.sql("select id from telegram_settings where id=1 for update").query(Integer.class).single(); }
    public void config(boolean enabled,Long secret,String chat) {
        jdbc.sql("update telegram_settings set enabled=?,token_secret_id=?,chat_id=?,revision=revision+1 where id=1").params(enabled,secret,chat).update();
        jdbc.sql("update notification_delivery set status='CANCELLED',last_error='Notification settings changed' where status='PENDING'").update();
    }
    public List<Alert> alerts() {
        return jdbc.sql("select * from backup_alert order by active desc,changed_at desc limit 100").query((r,n)->new Alert((UUID)r.getObject("id"),
                r.getString("kind"),(UUID)r.getObject("resource_id"),r.getString("message"),r.getBoolean("active"),r.getInt("generation"),
                r.getTimestamp("opened_at").toInstant(),r.getTimestamp("changed_at").toInstant(),r.getTimestamp("resolved_at")==null?null:r.getTimestamp("resolved_at").toInstant())).list();
    }
    public List<Delivery> deliveries() {
        return jdbc.sql("select * from notification_delivery order by created_at desc limit 100").query(this::delivery).list();
    }
    private Delivery delivery(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Delivery((UUID)r.getObject("id"),(UUID)r.getObject("alert_id"),r.getInt("generation"),r.getLong("config_revision"),
                r.getString("message"),r.getString("status"),r.getInt("attempts"),r.getTimestamp("next_attempt_at").toInstant(),
                r.getTimestamp("sent_at")==null?null:r.getTimestamp("sent_at").toInstant(),r.getString("last_error"));
    }
    /** One journal entry per transition, regardless of polling frequency or concurrent evaluators. */
    @Transactional
    public void condition(String key,String kind,UUID resource,boolean active,String message,String recovery) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param("pgcp.alert:"+key).query().singleRow();
        record Existing(UUID id,boolean active,int generation) { }
        var old=jdbc.sql("select id,active,generation from backup_alert where dedupe_key=?").param(key).query(Existing.class).optional();
        if(old.isEmpty() && !active || old.isPresent() && old.get().active()==active) return;
        UUID id=old.map(Existing::id).orElseGet(UUID::randomUUID);int generation=old.map(e->e.generation()+1).orElse(1);
        String text=active?message:recovery;
        jdbc.sql("""
                insert into backup_alert(id,dedupe_key,kind,resource_id,message,active,generation,resolved_at)
                values(?,?,?,?,?,?,?,case when ? then null else now() end)
                on conflict(dedupe_key) do update set message=excluded.message,active=excluded.active,generation=excluded.generation,
                  opened_at=case when excluded.active then now() else backup_alert.opened_at end,
                  changed_at=now(),resolved_at=excluded.resolved_at
                """).params(id,key,kind,resource,text,active,generation,active).update();
        // Lock settings through commit, so disable/edit cannot race with queue insertion.
        lockConfig();var config=config();
        if(config.enabled() && config.tokenSecretId()!=null) {
            jdbc.sql("insert into notification_delivery(id,alert_id,generation,config_revision,message) values(?,?,?,?,?)")
                    .params(UUID.randomUUID(),id,generation,config.revision(),"pgcp "+(active?"ALERT":"RESOLVED")+" ["+kind+"]\n"+text+"\nResource: "+resource).update();
        }
    }
    @Transactional
    public Optional<Delivery> claim(Instant now) {
        var timestamp=OffsetDateTime.ofInstant(now,ZoneOffset.UTC);
        jdbc.sql("update notification_delivery set status=case when attempts>=8 then 'FAILED' else 'PENDING' end,claimed_at=null,last_error='Interrupted delivery; retry may duplicate an accepted message' where status='SENDING' and claimed_at < ?")
                .param(timestamp.minusMinutes(2)).update();
        var item=jdbc.sql("""
                select d.* from notification_delivery d where d.status='PENDING' and d.next_attempt_at<=?
                 and not exists(select 1 from notification_delivery older where older.alert_id=d.alert_id and older.generation<d.generation and older.status in ('PENDING','SENDING'))
                order by d.created_at,d.id limit 1 for update of d skip locked
                """).param(timestamp).query(this::delivery).optional();
        item.ifPresent(d->jdbc.sql("update notification_delivery set status='SENDING',attempts=attempts+1,claimed_at=? where id=?").params(timestamp,d.id()).update());
        return item;
    }
    public void cancel(UUID id) { jdbc.sql("update notification_delivery set status='CANCELLED',claimed_at=null,last_error='Notification settings changed' where id=?").param(id).update(); }
    public void sent(UUID id,Instant now) {
        jdbc.sql("update notification_delivery set status='SENT',sent_at=?,claimed_at=null,last_error=null where id=?")
                .params(OffsetDateTime.ofInstant(now,ZoneOffset.UTC),id).update();
    }
    public void failed(Delivery d,Instant now,int retryAfter) {
        int attempt=d.attempts()+1;
        long seconds=Math.max(Math.min(86400,retryAfter),Math.min(3600,15L << Math.min(8,attempt)));
        jdbc.sql("update notification_delivery set status=?,attempts=?,next_attempt_at=?,claimed_at=null,last_error='Telegram delivery failed; check bot access, chat and network' where id=?")
                .params(attempt>=8?"FAILED":"PENDING",attempt,OffsetDateTime.ofInstant(now.plusSeconds(seconds),ZoneOffset.UTC),d.id()).update();
    }
}
