package dev.pgcp.cp.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditService.Entry> list(@RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) Long beforeId) {
        return audit.list(limit, beforeId);
    }

    @GetMapping("/verify")
    public AuditService.Verification verify() {
        return audit.verify();
    }
}
