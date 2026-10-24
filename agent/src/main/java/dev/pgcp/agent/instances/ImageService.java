package dev.pgcp.agent.instances;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.CommandRunner;
import dev.pgcp.agent.docker.DockerGateway;
import dev.pgcp.agent.ops.OperationService.Progress;

/**
 * Builds the managed PostgreSQL image for a major version from the bundled build context. The tag contains a hash
 * of the context, so updating the Dockerfile yields a new tag while existing instances keep the image they run.
 */
@Service
public class ImageService {

    private final AgentProperties props;
    private final DockerGateway docker;
    private final CommandRunner commands;
    private volatile String contextHash;

    public ImageService(AgentProperties props, DockerGateway docker, CommandRunner commands) {
        this.props = props;
        this.docker = docker;
        this.commands = commands;
    }

    public String tagFor(int pgMajor) {
        return AgentApi.IMAGE_REPOSITORY + ":" + pgMajor + "-" + contextHash();
    }

    public synchronized Map<String, String> ensure(int pgMajor, Progress progress) {
        try {
            AgentApi.requireSupportedMajor(pgMajor);
        } catch (IllegalArgumentException e) {
            throw AgentException.badRequest(e.getMessage());
        }
        String tag = tagFor(pgMajor);
        if (docker.imageExists(tag)) {
            return Map.of("image", tag, "built", "false");
        }
        progress.update(10, "building " + tag + " (first use of this version, may take a few minutes)");
        commands.runChecked(List.of(props.dockerCommand(), "build", "--tag", tag, "--build-arg",
                "PG_MAJOR=" + pgMajor, props.imageContextDir().toString()), null, Duration.ofMinutes(20));
        if (!docker.imageExists(tag)) {
            throw new AgentException(AgentException.Kind.FAILED, "image build reported success but " + tag + " is missing");
        }
        return Map.of("image", tag, "built", "true");
    }

    private String contextHash() {
        String h = contextHash;
        if (h == null) {
            h = hashContext(props.imageContextDir());
            contextHash = h;
        }
        return h;
    }

    static String hashContext(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                md.update(dir.relativize(f).toString().getBytes());
                md.update((byte) 0);
                md.update(Files.readAllBytes(f));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 12);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read image context " + dir, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
