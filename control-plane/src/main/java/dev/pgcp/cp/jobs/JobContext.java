package dev.pgcp.cp.jobs;

import java.util.Map;
import java.util.UUID;

public interface JobContext {

    UUID jobId();

    <T> T params(Class<T> type);

    void info(String message);

    void warn(String message);

    void progress(int percent, String message);

    void result(Map<String, ?> result);
}
