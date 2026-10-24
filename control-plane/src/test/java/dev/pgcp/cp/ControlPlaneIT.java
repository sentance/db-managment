package dev.pgcp.cp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.pgcp.agent.api.ContainerCredentials;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.agent.api.Libc;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.auth.TotpTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end through the HTTP API with a real metadata database and a real target PostgreSQL registered as an
 * external instance. The agent is not running here; agent-backed paths are covered by the agent's Docker tests and
 * the compose smoke test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ControlPlaneIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer meta = new PostgreSQLContainer("postgres:18-trixie");

    @Container
    static PostgreSQLContainer target = new PostgreSQLContainer("postgres:18-trixie").withUsername("target_admin")
            .withPassword("TargetSuperuserPassword4242");

    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        tmp = Files.createTempDirectory("pgcp-it");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path keyFile = tmp.resolve("master.key");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
        r.add("pgcp.crypto.master-key-file", keyFile::toString);
        r.add("pgcp.setup-token-file", () -> tmp.resolve("setup-token").toString());
        r.add("pgcp.agent.url", () -> "http://127.0.0.1:9");
        r.add("pgcp.agent.tls", () -> "false");
        r.add("pgcp.health-interval", () -> "1h");
        r.add("server.servlet.session.cookie.secure", () -> "false");
        for (String k : new String[] { "PGCP_AGENT_CLIENT_CERT", "PGCP_AGENT_CA" }) {
            r.add(k, () -> "classpath:tls/test-cert.pem");
        }
        r.add("PGCP_AGENT_CLIENT_KEY", () -> "classpath:tls/test-key.pem");
    }

    @MockitoBean
    AgentClient agent;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    MockHttpSession session = new MockHttpSession();

    @Test
    void setupLoginManageExternalDatabaseAndAudit(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.setupRequired").value(true));
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());

        // --- initial setup ---------------------------------------------------------------------------------------
        mvc.perform(post("/api/auth/setup").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("token", "wrong", "username", "admin", "password", "correct horse battery"))))
                .andExpect(status().isForbidden());
        String token = Files.readString(tmp.resolve("setup-token")).trim();
        JsonNode setup = call(post("/api/auth/setup"), Map.of("token", token, "username", "admin", "password",
                "correct horse battery"), 200);
        String totpSecret = setup.get("totpSecret").asString();
        MvcResult done = mvc.perform(post("/api/auth/setup/totp").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("code", TotpTestSupport.code(totpSecret, 0)))))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) done.getRequest().getSession();
        assertThat(json.readTree(done.getResponse().getContentAsString()).get("recoveryCodes").size()).isEqualTo(10);
        assertThat(Files.exists(tmp.resolve("setup-token"))).as("setup token is consumed").isFalse();
        call(get("/api/auth/status"), null, 200);

        // --- external instance in MANAGE_OBJECTS mode requires step-up --------------------------------------------
        Map<String, Object> external = Map.of("name", "target", "host", target.getHost(), "port", target.getFirstMappedPort(),
                "username", target.getUsername(), "password", target.getPassword(), "sslMode", "disable", "mode",
                "MANAGE_OBJECTS", "protection", "STANDARD");
        JsonNode denied = call(post("/api/instances/external"), external, 403);
        assertThat(denied.get("code").asString()).isEqualTo("STEP_UP_REQUIRED");
        String stepUpCode = TotpTestSupport.code(totpSecret, 1);
        call(post("/api/auth/step-up"), Map.of("code", stepUpCode), 200);
        String instanceId = call(post("/api/instances/external"), external, 200).get("id").asString();

        // A second registration in MONITOR_ONLY mode must refuse every change.
        String monitorId = call(post("/api/instances/external"), Map.of("name", "watch-only", "host", target.getHost(), "port",
                target.getFirstMappedPort(), "username", target.getUsername(), "password", target.getPassword(), "sslMode",
                "disable"), 200).get("id").asString();
        call(post("/api/instances/" + monitorId + "/databases"), Map.of("name", "nope"), 403);
        assertThat(call(get("/api/instances/" + monitorId + "/monitoring"), null, 200).get("version").asString())
                .contains("PostgreSQL 18");

        // --- registration from a discovered container: credentials come from the agent and never leave the server ---
        String containerSecret = "ContainerEnvSecret987654";
        org.mockito.Mockito.when(agent.postgresContainers()).thenReturn(java.util.List.of(new ContainerInfo("c1", "unreachable-pg",
                "postgres:16-alpine", "running", "healthy", "running", false, null, 16, "16.14", Libc.MUSL,
                java.util.List.of("coolify"), Map.of("coolify.resourceName", "shared-db"), null)));
        org.mockito.Mockito.when(agent.postgresCredentials("unreachable-pg"))
                .thenReturn(new ContainerCredentials("unreachable-pg", "postgres", containerSecret, "postgres"));
        JsonNode unreachable = call(post("/api/instances/external/from-container"), Map.of("containerName", "unreachable-pg"), 400);
        assertThat(unreachable.toString()).contains("cannot connect").doesNotContain(containerSecret);
        call(post("/api/instances/external/from-container"), Map.of("containerName", "bad name!"), 400);
        call(post("/api/instances/external/from-container"), Map.of("containerName", "missing"), 404);

        // --- create a database with owner + read-only roles ------------------------------------------------------
        JsonNode created = call(post("/api/instances/" + instanceId + "/databases"), Map.of("name", "shop",
                "createReadOnlyRole", true), 202);
        String ownerPassword = created.get("credentials").get(0).get("password").asString();
        String roPassword = created.get("credentials").get(1).get("password").asString();
        assertThat(created.get("connection").get("endpoints").get(0).get("uri").asString()).contains(ownerPassword);
        awaitJob(created.get("jobId").asString());

        try (var c = DriverManager.getConnection(jdbc("shop"), "shop_owner", ownerPassword)) {
            c.createStatement().execute("create table orders(id int)");
        }
        try (var c = DriverManager.getConnection(jdbc("shop"), "shop_ro", roPassword)) {
            c.createStatement().executeQuery("select count(*) from orders").close();
        }

        // Duplicate names are rejected before anything runs.
        call(post("/api/instances/" + instanceId + "/databases"), Map.of("name", "shop"), 409);
        // Connection info is masked; reveal is allowed because step-up is still fresh.
        assertThat(call(get("/api/instances/" + instanceId + "/databases/shop/connection"), null, 200).toString())
                .doesNotContain(ownerPassword).contains("********");
        assertThat(call(post("/api/instances/" + instanceId + "/roles/shop_owner/reveal-password"), null, 200)
                .get("password").asString()).isEqualTo(ownerPassword);

        // Rotation invalidates the old password.
        String rotated = call(post("/api/instances/" + instanceId + "/roles/shop_ro/rotate-password"), null, 200)
                .get("password").asString();
        assertThat(rotated).isNotEqualTo(roPassword);
        DriverManager.getConnection(jdbc("shop"), "shop_ro", rotated).close();

        // Roles that pgcp uses or that are superusers are protected.
        call(post("/api/instances/" + instanceId + "/roles/" + target.getUsername() + "/rotate-password"), null, 403);

        // --- drop requires typing the name -------------------------------------------------------------------------
        call(post("/api/instances/" + instanceId + "/databases/shop/drop"), Map.of("confirmName", "wrong"), 400);
        String dropJob = call(post("/api/instances/" + instanceId + "/databases/shop/drop"), Map.of("confirmName", "shop",
                "terminateConnections", true), 202).get("jobId").asString();
        awaitJob(dropJob);
        assertThat(call(get("/api/instances/" + instanceId + "/databases"), null, 200).toString()).doesNotContain("\"shop\"");

        // --- audit chain is intact and never contains secrets --------------------------------------------------------
        assertThat(call(get("/api/audit/verify"), null, 200).get("intact").asBoolean()).isTrue();
        String audit = call(get("/api/audit?limit=500"), null, 200).toString();
        assertThat(audit).contains("database.create", "role.reveal_password", "auth.step_up")
                .doesNotContain(ownerPassword, roPassword, rotated, target.getPassword(), containerSecret);
        assertThat(output.getAll()).doesNotContain(ownerPassword, roPassword, rotated, target.getPassword(), totpSecret, token,
                containerSecret)
                .doesNotContain("generated security password");

        // --- recovery codes can be regenerated (step-up still fresh); the OpenAPI document is published to docs/ ---
        assertThat(call(post("/api/auth/recovery-codes"), null, 200).get("recoveryCodes").size()).isEqualTo(10);
        String openApi = mvc.perform(get("/api/docs").session(session)).andReturn().getResponse().getContentAsString();
        assertThat(openApi).contains("/api/instances/managed");
        Path docs = Path.of("..", "docs");
        if (Files.isDirectory(docs)) {
            Files.writeString(docs.resolve("openapi.json"), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(json.readTree(openApi)) + "\n");
        }

        // --- replaying an already used TOTP code is rejected (as a replay, or as expired if a minute has passed) ------
        call(post("/api/auth/logout"), null, 204);
        session = new MockHttpSession();
        call(post("/api/auth/login"), Map.of("username", "admin", "password", "correct horse battery", "code", stepUpCode),
                401);
    }

    private void awaitJob(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode job = call(get("/api/jobs/" + jobId), null, 200).get("job");
            String s = job.get("status").asString();
            if (s.equals("SUCCEEDED")) {
                return;
            }
            assertThat(s).as("job error: " + job.get("error")).isNotEqualTo("FAILED");
            Thread.sleep(300);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body,
            int expectedStatus) throws Exception {
        req = req.with(csrf()).session(session);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body(body));
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        String content = r.getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private String body(Object o) {
        return json.writeValueAsString(o);
    }

    private static String jdbc(String db) {
        return "jdbc:postgresql://" + target.getHost() + ":" + target.getFirstMappedPort() + "/" + db;
    }
}
