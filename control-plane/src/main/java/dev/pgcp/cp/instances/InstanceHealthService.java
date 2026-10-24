package dev.pgcp.cp.instances;

import java.sql.Connection;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.pg.InstanceConnector;

/** Periodically reconciles instance status with reality. Instances with an active job are left to that job. */
@Service
public class InstanceHealthService {

    private static final Logger log = LoggerFactory.getLogger(InstanceHealthService.class);

    private final InstanceRepository repo;
    private final AgentClient agent;
    private final InstanceConnector connector;
    private final JobService jobs;

    public InstanceHealthService(InstanceRepository repo, AgentClient agent, InstanceConnector connector, JobService jobs) {
        this.repo = repo;
        this.agent = agent;
        this.connector = connector;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${pgcp.health-interval:30s}", initialDelay = 10_000)
    public void check() {
        for (Instance i : repo.listActive()) {
            if (Instance.PROVISIONING.equals(i.status()) || Instance.DELETING.equals(i.status())
                    || jobs.hasActive(i.resourceKey())) {
                continue;
            }
            try {
                String status = i.managed() ? managedStatus(i) : externalStatus(i);
                String message = status.equals(Instance.RUNNING) ? null : i.statusMessage();
                if (!status.equals(i.status())) {
                    log.info("instance {} status {} -> {}", i.name(), i.status(), status);
                    repo.updateStatus(i.id(), status, message);
                }
            } catch (RuntimeException e) {
                log.debug("health check of {} failed: {}", i.name(), e.toString());
            }
        }
    }

    private String managedStatus(Instance i) {
        if (Instance.FAILED.equals(i.status()) && i.containerName() == null) {
            return Instance.FAILED;
        }
        try {
            var c = agent.managedInstance(i.name());
            if (!"running".equals(c.state())) {
                return Instance.STOPPED;
            }
            return "healthy".equals(c.health()) ? Instance.RUNNING : Instance.UNHEALTHY;
        } catch (ApiException e) {
            if (e.status().value() == 404) {
                return Instance.FAILED;
            }
            throw e;
        }
    }

    private String externalStatus(Instance i) {
        try (Connection c = connector.open(i, i.maintenanceDb(), InstanceConnector.As.ADMIN)) {
            return c.isValid(5) ? Instance.RUNNING : Instance.UNREACHABLE;
        } catch (SQLException e) {
            return Instance.UNREACHABLE;
        }
    }
}
