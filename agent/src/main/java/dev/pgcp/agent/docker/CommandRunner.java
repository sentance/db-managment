package dev.pgcp.agent.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.pgcp.agent.config.AgentException;

/**
 * Runs external programs with a fixed argv: no shell is involved, so arguments are never re-parsed.
 * Callers must pass only validated values. Output is capped to keep memory bounded.
 */
@Component
public class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    public record Result(int exitCode, String output) {
    }

    public Result run(List<String> argv, Path workDir, Duration timeout) {
        log.info("exec {}", argv);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        try {
            Process process = pb.start();
            process.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AgentException(AgentException.Kind.FAILED, "command timed out after " + timeout + ": " + argv.get(0));
            }
            reader.join(Duration.ofSeconds(5));
            String output;
            synchronized (out) {
                output = out.toString(StandardCharsets.UTF_8);
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            throw new AgentException(AgentException.Kind.FAILED, "cannot start " + argv.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentException.Kind.FAILED, "interrupted", e);
        }
    }

    public String runChecked(List<String> argv, Path workDir, Duration timeout) {
        Result result = run(argv, workDir, timeout);
        if (result.exitCode() != 0) {
            throw new AgentException(AgentException.Kind.FAILED,
                    argv.get(0) + " " + argv.get(1) + " failed (exit " + result.exitCode() + "): " + tail(result.output(), 2000));
        }
        return result.output();
    }

    static String tail(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (out) {
                    if (out.size() + n > MAX_OUTPUT_BYTES) {
                        byte[] keep = out.toByteArray();
                        out.reset();
                        int from = Math.max(0, keep.length - MAX_OUTPUT_BYTES / 2);
                        out.write(keep, from, keep.length - from);
                    }
                    out.write(buf, 0, n);
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }
}
