package dev.pgcp.agent.api;

import java.util.List;

public record LogLines(String container, List<String> lines) {
}
