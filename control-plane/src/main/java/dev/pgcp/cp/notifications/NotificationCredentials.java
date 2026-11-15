package dev.pgcp.cp.notifications;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.pgcp.cp.crypto.SecretStore;

@Service
public class NotificationCredentials {
    public record Snapshot(String token,String chatId) {
        @Override public String toString() { return "NotificationCredentials[redacted]"; }
    }
    private final NotificationRepository repo;private final SecretStore secrets;
    public NotificationCredentials(NotificationRepository repo,SecretStore secrets) { this.repo=repo;this.secrets=secrets; }
    @Transactional public Optional<Snapshot> snapshot(NotificationRepository.Delivery item) {
        repo.lockConfig();var config=repo.config();
        if(!config.enabled() || config.tokenSecretId()==null || config.revision()!=item.configRevision()) return Optional.empty();
        return Optional.of(new Snapshot(secrets.read(config.tokenSecretId()),config.chatId()));
    }
}
