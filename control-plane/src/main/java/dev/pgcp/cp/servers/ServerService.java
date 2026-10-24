package dev.pgcp.cp.servers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.HostFacts;
import dev.pgcp.agent.api.HostMetrics;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;
import dev.pgcp.cp.config.CpProperties;

/** The VPS registry. Phase 1 has exactly one server: the host running the local agent. */
@Service
public class ServerService {

    public record Server(UUID id, String name, String agentUrl, String hostname, String os, String kernel, Integer cpuCores,
            Long memTotalBytes, String dockerVersion, Instant lastSeenAt) {
    }

    public record ServerStatus(Server server, boolean agentReachable, HostFacts facts, HostMetrics metrics, String error) {
    }

    private static final Logger log = LoggerFactory.getLogger(ServerService.class);

    private final JdbcClient jdbc;
    private final AgentClient agent;
    private final CpProperties props;
    private final Json json;

    public ServerService(JdbcClient jdbc, AgentClient agent, CpProperties props, Json json) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.props = props;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerLocal() {
        jdbc.sql("""
                insert into server (id, name, agent_url) values (?, ?, ?)
                on conflict (name) do update set agent_url = excluded.agent_url
                """).params(UUID.randomUUID(), props.serverName(), props.agent().url()).update();
        refreshFacts();
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void refreshFacts() {
        try {
            HostFacts f = agent.facts();
            jdbc.sql("""
                    update server set hostname = ?, os = ?, kernel = ?, cpu_cores = ?, mem_total_bytes = ?, docker_version = ?,
                        facts = ?::jsonb, last_seen_at = now()
                    where name = ?
                    """).params(f.hostname(), f.os(), f.kernel(), f.cpuCores(), f.memTotalBytes(), f.dockerVersion(),
                    json.write(f), props.serverName()).update();
        } catch (ApiException e) {
            log.warn("cannot refresh host facts: {}", e.getMessage());
        }
    }

    public List<Server> list() {
        return jdbc.sql("""
                select id, name, agent_url, hostname, os, kernel, cpu_cores, mem_total_bytes, docker_version, last_seen_at
                from server order by name
                """).query(Server.class).list();
    }

    public Optional<Server> find(UUID id) {
        return jdbc.sql("""
                select id, name, agent_url, hostname, os, kernel, cpu_cores, mem_total_bytes, docker_version, last_seen_at
                from server where id = ?
                """).param(id).query(Server.class).optional();
    }

    public Server local() {
        return jdbc.sql("""
                select id, name, agent_url, hostname, os, kernel, cpu_cores, mem_total_bytes, docker_version, last_seen_at
                from server where name = ?
                """).param(props.serverName()).query(Server.class).optional()
                .orElseThrow(() -> ApiException.unavailable("local server is not registered yet"));
    }

    public ServerStatus status(Server s) {
        try {
            HostFacts facts = agent.facts();
            HostMetrics metrics = agent.metrics();
            jdbc.sql("update server set last_seen_at = now() where id = ?").param(s.id()).update();
            return new ServerStatus(s, true, facts, metrics, null);
        } catch (ApiException e) {
            return new ServerStatus(s, false, null, null, e.getMessage());
        }
    }
}
