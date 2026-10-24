package dev.pgcp.cp.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PgSettingsTest {

    @Test
    void derivesMemoryDefaultsAndAppliesOverrides() {
        var s = PgSettings.withDefaults(1024, Map.of("work_mem", "16MB"));
        assertThat(s).containsEntry("shared_buffers", "256MB").containsEntry("work_mem", "16MB")
                .containsEntry("max_connections", "100");
        assertThat(PgSettings.withDefaults(512, null)).containsEntry("shared_buffers", "128MB")
                .containsEntry("max_connections", "50");
    }

    @Test
    void rejectsParametersOutsideTheAllowlistOrMalformedValues() {
        assertThatThrownBy(() -> PgSettings.validate(Map.of("archive_command", "rm -rf /"))).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> PgSettings.validate(Map.of("shared_preload_libraries", "x"))).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> PgSettings.validate(Map.of("work_mem", "lots"))).hasMessageContaining("invalid value");
        assertThatThrownBy(() -> PgSettings.validate(Map.of("jit", "maybe"))).hasMessageContaining("invalid value");
        assertThat(PgSettings.validate(Map.of("statement_timeout", "30s", "random_page_cost", "1.1"))).hasSize(2);
    }
}
