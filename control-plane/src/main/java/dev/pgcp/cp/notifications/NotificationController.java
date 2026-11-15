package dev.pgcp.cp.notifications;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationController {
    private final TelegramService telegram;private final NotificationRepository repo;
    public NotificationController(TelegramService telegram,NotificationRepository repo) { this.telegram=telegram;this.repo=repo; }
    @GetMapping("/api/notifications/telegram") public TelegramService.TelegramView settings() { return telegram.view(); }
    @PutMapping("/api/notifications/telegram") public TelegramService.TelegramView save(@RequestBody TelegramService.TelegramRequest request) { return telegram.save(request); }
    @GetMapping("/api/backups/alerts") public List<NotificationRepository.Alert> alerts() { return repo.alerts(); }
    @GetMapping("/api/notifications/deliveries") public List<NotificationRepository.Delivery> deliveries() { return repo.deliveries(); }
}
