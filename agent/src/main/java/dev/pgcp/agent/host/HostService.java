package dev.pgcp.agent.host;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.DiskUsage;
import dev.pgcp.agent.api.HostFacts;
import dev.pgcp.agent.api.HostMetrics;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.DockerGateway;
import jakarta.annotation.PostConstruct;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.util.GlobalConfig;

/** Host facts and periodically sampled metrics. Reads the host's /proc when the agent runs in a container. */
@Service
public class HostService {

    private static final Logger log = LoggerFactory.getLogger(HostService.class);
    private static final Set<String> INTERESTING_FS = Set.of("ext4", "xfs", "btrfs", "zfs", "f2fs");

    private final AgentProperties props;
    private final DockerGateway docker;
    private final HardwareAbstractionLayer hal;
    private final Path procPath;

    private long[] prevTicks;
    private long prevRead = -1;
    private long prevWrite = -1;
    private long prevSampleNanos;
    private volatile HostMetrics latest;

    public HostService(AgentProperties props, DockerGateway docker) {
        this.props = props;
        this.docker = docker;
        this.procPath = props.hostProcPath() != null ? props.hostProcPath() : Path.of("/proc");
        if (props.hostProcPath() != null) {
            GlobalConfig.set("oshi.util.proc.path", props.hostProcPath().toString());
        }
        this.hal = new SystemInfo().getHardware();
        this.prevTicks = hal.getProcessor().getSystemCpuLoadTicks();
    }

    @PostConstruct
    void firstSample() {
        sample();
    }

    public HostFacts facts() {
        GlobalMemory mem = hal.getMemory();
        String dockerVersion;
        try {
            dockerVersion = docker.version();
        } catch (RuntimeException e) {
            dockerVersion = "unavailable: " + e.getMessage();
        }
        return new HostFacts(
                readFirstLine(procPath.resolve("sys/kernel/hostname"), "unknown"),
                osPrettyName(),
                readFirstLine(procPath.resolve("sys/kernel/osrelease"), "unknown"),
                hal.getProcessor().getLogicalProcessorCount(),
                mem.getTotal(),
                mem.getVirtualMemory().getSwapTotal(),
                dockerVersion,
                disks(),
                filesystems());
    }

    public HostMetrics metrics() {
        return latest;
    }

    public long memAvailableBytes() {
        return hal.getMemory().getAvailable();
    }

    public long freeBytes(Path path) {
        try {
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    @Scheduled(fixedDelayString = "${pgcp.agent.metrics-interval:5s}")
    public synchronized void sample() {
        try {
            CentralProcessor cpu = hal.getProcessor();
            double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
            prevTicks = cpu.getSystemCpuLoadTicks();
            GlobalMemory mem = hal.getMemory();

            long read = 0;
            long write = 0;
            for (HWDiskStore d : hal.getDiskStores()) {
                read += d.getReadBytes();
                write += d.getWriteBytes();
            }
            long now = System.nanoTime();
            long readRate = 0;
            long writeRate = 0;
            if (prevRead >= 0) {
                double seconds = Math.max(0.001, (now - prevSampleNanos) / 1e9);
                readRate = (long) (Math.max(0, read - prevRead) / seconds);
                writeRate = (long) (Math.max(0, write - prevWrite) / seconds);
            }
            prevRead = read;
            prevWrite = write;
            prevSampleNanos = now;

            double[] load = cpu.getSystemLoadAverage(1);
            latest = new HostMetrics(Instant.now(), round(cpuLoad), round(load[0]), mem.getTotal(), mem.getAvailable(),
                    mem.getVirtualMemory().getSwapUsed(), readRate, writeRate, disks());
        } catch (RuntimeException e) {
            log.warn("host metrics sampling failed: {}", e.toString());
        }
    }

    private List<DiskUsage> disks() {
        List<DiskUsage> result = new ArrayList<>();
        Map<String, Path> paths = props.diskPaths() == null ? Map.of() : props.diskPaths();
        paths.forEach((label, path) -> {
            try {
                FileStore fs = Files.getFileStore(path);
                result.add(new DiskUsage(label, path.toString(), fs.getTotalSpace(), fs.getUsableSpace()));
            } catch (IOException e) {
                log.debug("cannot stat {}: {}", path, e.toString());
            }
        });
        return result;
    }

    private List<String> filesystems() {
        Set<String> types = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(procPath.resolve("1/mounts"))) {
                String[] parts = line.split(" ");
                if (parts.length > 2 && INTERESTING_FS.contains(parts[2])) {
                    types.add(parts[2]);
                }
            }
        } catch (IOException e) {
            log.debug("cannot read mounts: {}", e.toString());
        }
        return List.copyOf(types);
    }

    private String osPrettyName() {
        Path root = props.hostRootPath() != null ? props.hostRootPath() : Path.of("/");
        try {
            for (String line : Files.readAllLines(root.resolve("etc/os-release"))) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return line.substring("PRETTY_NAME=".length()).replace("\"", "");
                }
            }
        } catch (IOException e) {
            log.debug("cannot read os-release: {}", e.toString());
        }
        return System.getProperty("os.name");
    }

    private static String readFirstLine(Path file, String fallback) {
        try {
            return Files.readAllLines(file).getFirst().trim();
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
