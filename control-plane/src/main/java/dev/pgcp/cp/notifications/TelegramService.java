package dev.pgcp.cp.notifications;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.crypto.SecretStore;

@Service
public class TelegramService {
    public record TelegramRequest(boolean enabled,String chatId,String token) {
        @Override public String toString() { return "TelegramRequest[redacted]"; }
    }
    public record TelegramView(boolean enabled,boolean configured,String chatId,long revision) { }
    private final NotificationRepository repo;private final SecretStore secrets;private final StepUp stepUp;
    private final AuditService audit;private final TelegramTransport transport;
    private final NotificationCredentials credentials;
    private final java.util.concurrent.ExecutorService sender=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean sending=new java.util.concurrent.atomic.AtomicBoolean();
    public TelegramService(NotificationRepository repo,SecretStore secrets,StepUp stepUp,AuditService audit,TelegramTransport transport,NotificationCredentials credentials) {
        this.repo=repo;this.secrets=secrets;this.stepUp=stepUp;this.audit=audit;this.transport=transport;
        this.credentials=credentials;
    }
    public TelegramView view() { var c=repo.config();return new TelegramView(c.enabled(),c.tokenSecretId()!=null,c.chatId(),c.revision()); }
    @Transactional public TelegramView save(TelegramRequest request) {
        stepUp.require();repo.lockConfig();var old=repo.config();
        String chat=request.chatId()==null?old.chatId():request.chatId().strip();
        if(!chat.isEmpty() && !chat.matches("-?[0-9]{1,20}")) throw ApiException.badRequest("use a numeric Telegram chat ID");
        if((request.token()==null || request.token().isBlank()) && request.enabled()==old.enabled() && chat.equals(old.chatId())) return view();
        Long secret=old.tokenSecretId();
        if(request.token()!=null && !request.token().isBlank()) {
            if(!request.token().matches("[0-9]{5,16}:[A-Za-z0-9_-]{20,160}")) throw ApiException.badRequest("invalid bot token format");
            if(secret==null) secret=secrets.create("telegram-bot",request.token());else secrets.replace(secret,request.token());
        }
        if(request.enabled() && (secret==null || chat.isEmpty())) throw ApiException.badRequest("bot token and chat ID are required before enabling notifications");
        repo.config(request.enabled(),secret,chat);
        audit.success("notification.telegram.update","settings","telegram",java.util.Map.of("enabled",request.enabled()));return view();
    }
    @Scheduled(fixedDelay=5000,initialDelayString="${pgcp.notifications.initial-delay:10s}")
    public void scheduledDelivery() {
        if(sending.compareAndSet(false,true)) sender.submit(()->{ try { deliver(); } finally { sending.set(false); } });
    }
    public synchronized void deliver() {
        for(int i=0;i<10;i++) {
            var next=repo.claim(Instant.now());if(next.isEmpty()) return;
            var item=next.get();var snapshot=credentials.snapshot(item);
            if(snapshot.isEmpty()) { repo.cancel(item.id());continue; }
            TelegramTransport.Outcome outcome;
            try { outcome=transport.send(snapshot.get().token(),snapshot.get().chatId(),item.message()); }
            catch(RuntimeException e) { outcome=new TelegramTransport.Outcome(false,0); }
            if(outcome.success()) repo.sent(item.id(),Instant.now());else repo.failed(item,Instant.now(),outcome.retryAfter());
            if(Thread.currentThread().isInterrupted()) return;
        }
    }
    @jakarta.annotation.PreDestroy public void shutdown() { sender.shutdownNow(); }
}
