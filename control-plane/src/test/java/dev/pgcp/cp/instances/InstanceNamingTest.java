package dev.pgcp.cp.instances;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.pgcp.agent.api.AgentApi;

class InstanceNamingTest {

    @Test
    void suggestsValidNamesFromCoolifyLabelsOrContainerNames() {
        assertThat(InstanceService.suggestName(Map.of("coolify.resourceName", "shared-db"), "shared-postgres-x")).isEqualTo("shared-db");
        String longName = InstanceService.suggestName(Map.of("coolify.resourceName", "postgresql-database-w1jqg0toweyovks39f8j6yfa"), "w1");
        assertThat(longName).hasSizeLessThanOrEqualTo(31).matches(AgentApi.INSTANCE_NAME);
        assertThat(InstanceService.suggestName(Map.of(), "123_My.DB")).matches(AgentApi.INSTANCE_NAME);
        assertThat(InstanceService.suggestName(Map.of(), "9")).isEqualTo("external");
    }
}
