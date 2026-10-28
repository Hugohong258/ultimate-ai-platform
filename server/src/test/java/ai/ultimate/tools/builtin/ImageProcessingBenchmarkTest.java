package ai.ultimate.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in observations, not deployment acceptance thresholds or a whole-AI-workload benchmark. */
class ImageProcessingBenchmarkTest {
    @TempDir Path temporary;

    @Test void writesReproducibleWorkloadAndSampledResourceReport() throws Exception {
        assumeTrue(Boolean.getBoolean("ultimate.image.benchmark"), "Opt in to resource measurements");
        String binary = System.getProperty("ultimate.image.integration.binary");
        assumeTrue(binary != null && Files.isExecutable(Path.of(binary)), "Explicit ImageMagick required");
        Path workspace = Files.createDirectories(temporary.resolve("managed/benchmark"));
        fixture(workspace.resolve("small.png"), 640, 480);
        fixture(workspace.resolve("large.png"), 4000, 3000);
        var nativeRunner = new ImageProcessRunner.Native();
        var tool = new ImageProcessingTool(temporary.resolve("managed"), binary, nativeRunner, Duration.ofSeconds(60));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("environment", Map.of("os", System.getProperty("os.name"), "osVersion", System.getProperty("os.version"),
                "architecture", System.getProperty("os.arch"), "java", System.getProperty("java.version"),
                "logicalProcessors", Runtime.getRuntime().availableProcessors(), "maxJvmHeapBytes", Runtime.getRuntime().maxMemory(),
                "imageMagick", nativeRunner.run(List.of(binary, "-version"), workspace, Map.of(), Duration.ofSeconds(5)).output()));
        report.put("method", "50ms JVM+observed-descendant sampling; RSS only on Linux /proc; CPU counters may miss exited processes; "
                + "workspace peak is logical bytes including fixtures and published files. First call is not an OS cold-cache run. No LLM load.");
        report.put("corpusSha256", Map.of("small.png", sha256(workspace.resolve("small.png")), "large.png", sha256(workspace.resolve("large.png"))));
        List<Map<String, Object>> cases = new ArrayList<>();
        measure(cases, "native-process-startup", workspace, () -> {
            var result = nativeRunner.run(List.of(binary, "-version"), workspace, Map.of(), Duration.ofSeconds(5));
            assertThat(result.exitCode()).isZero();
        });
        measure(cases, "first-image-call", workspace, () -> success(tool, workspace, List.of("small.png"), "first", false));
        measure(cases, "large-12MP-resize", workspace, () -> success(tool, workspace, List.of("large.png"), "large", true));
        List<String> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String name = "batch-" + i + ".png";
            Files.copy(workspace.resolve("small.png"), workspace.resolve(name));
            batch.add(name);
        }
        measure(cases, "ten-image-batch", workspace, () -> success(tool, workspace, batch, "batch", false));
        measure(cases, "two-concurrent-calls", workspace, () -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = executor.submit(() -> success(tool, workspace, List.of("small.png"), "parallel-a", false));
                var b = executor.submit(() -> success(tool, workspace, List.of("small.png"), "parallel-b", false));
                a.get(); b.get();
            }
        });
        for (int i = 0; i < 12; i++) {
            String name = "repeat-" + i;
            measure(cases, name, workspace, () -> success(tool, workspace, List.of("small.png"), name, false));
        }
        measure(cases, "timeout-and-recovery", workspace, () -> {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classes = Path.of(ImageProcessingBenchmarkTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            ImageProcessRunner slow = (command, directory, env, timeout) -> nativeRunner.run(
                    List.of(java, "-cp", classes, ImageProcessRunnerTest.Probe.class.getName(), "sleep"),
                    directory, env, timeout);
            var timeoutTool = new ImageProcessingTool(temporary.resolve("managed"), binary, slow, Duration.ofMillis(300));
            assertThat(timeoutTool.processImages(workspace.toString(), List.of("small.png"), ".", "timeout", "convert", "png",
                    0, 0, 0, 0, 85, 0, 0, "")).contains("TIMEOUT");
            assertThat(workspace.resolve("timeout")).doesNotExist();
            success(tool, workspace, List.of("small.png"), "recovered", false);
        });
        try (var paths = Files.list(workspace)) {
            assertThat(paths.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".ultimate-image-"));
        }
        report.put("cases", cases);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/image-processing-benchmark.json").toFile(), report);
    }

    private static String success(ImageProcessingTool tool, Path workspace, List<String> inputs, String name, boolean resize) {
        String result = tool.processImages(workspace.toString(), inputs, ".", name, resize ? "resize" : "convert", "webp",
                resize ? 2000 : 0, resize ? 2000 : 0, 0, 0, 85, 0, 0, "");
        assertThat(result).contains("\"code\":\"OK\"");
        return result;
    }

    private static void fixture(Path path, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setPaint(new java.awt.GradientPaint(0, 0, Color.BLUE, width, height, Color.ORANGE));
            graphics.fillRect(0, 0, width, height);
        } finally { graphics.dispose(); }
        ImageIO.write(image, "png", path.toFile());
    }

    private static String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    @FunctionalInterface interface Work { void run() throws Exception; }

    private static void measure(List<Map<String, Object>> cases, String name, Path workspace, Work work) throws Exception {
        try (var sampler = new Sampler(workspace)) {
            long start = System.nanoTime();
            work.run();
            long elapsed = System.nanoTime() - start;
            sampler.close();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", name);
            values.put("elapsedMs", elapsed / 1_000_000.0);
            values.put("sampleCount", sampler.samples);
            values.put("sampledCpuMs", sampler.cpu.values().stream().mapToLong(Long::longValue).sum() / 1_000_000.0);
            values.put("sampledPeakRssBytes", sampler.linux ? sampler.peakRss : null);
            values.put("sampledReadBytes", sampler.linux ? sampler.read.values().stream().mapToLong(Long::longValue).sum() : null);
            values.put("sampledWriteBytes", sampler.linux ? sampler.write.values().stream().mapToLong(Long::longValue).sum() : null);
            values.put("sampledPeakWorkspaceBytes", sampler.peakDisk);
            cases.add(values);
        }
    }

    private static final class Sampler implements AutoCloseable {
        final Path workspace;
        final boolean linux = Files.isDirectory(Path.of("/proc/self"));
        final Map<Long, Long> cpu = new HashMap<>();
        final Map<Long, Long> read = new HashMap<>(), write = new HashMap<>();
        final long rootCpu = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
        final long rootRead = io(ProcessHandle.current().pid(), "read_bytes:");
        final long rootWrite = io(ProcessHandle.current().pid(), "write_bytes:");
        volatile boolean running = true;
        long peakRss, peakDisk, samples;
        final Thread thread;

        Sampler(Path workspace) {
            this.workspace = workspace;
            thread = Thread.ofPlatform().daemon().start(() -> {
                while (running) {
                    sample();
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
            });
        }

        void sample() {
            long rss = 0;
            var processes = new ArrayList<>(ProcessHandle.current().descendants().toList());
            processes.add(ProcessHandle.current());
            for (var process : processes) {
                long nanos = process.info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
                cpu.merge(process.pid(), Math.max(0, nanos - (process.pid() == ProcessHandle.current().pid() ? rootCpu : 0)), Math::max);
                if (linux) try {
                    for (String line : Files.readAllLines(Path.of("/proc", Long.toString(process.pid()), "status")))
                        if (line.startsWith("VmRSS:")) rss += Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
                    boolean root = process.pid() == ProcessHandle.current().pid();
                    read.merge(process.pid(), Math.max(0, io(process.pid(), "read_bytes:") - (root ? rootRead : 0)), Math::max);
                    write.merge(process.pid(), Math.max(0, io(process.pid(), "write_bytes:") - (root ? rootWrite : 0)), Math::max);
                } catch (Exception ignored) { /* Process exited between enumeration and sampling. */ }
            }
            peakRss = Math.max(peakRss, rss);
            try (var paths = Files.walk(workspace)) {
                long bytes = 0;
                for (Path path : paths.filter(Files::isRegularFile).toList())
                    try { bytes += Files.size(path); } catch (java.io.IOException ignored) { }
                peakDisk = Math.max(peakDisk, bytes);
            } catch (java.io.IOException ignored) { }
            samples++;
        }

        private static long io(long pid, String field) {
            try {
                for (String line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "io")))
                    if (line.startsWith(field)) return Long.parseLong(line.substring(field.length()).trim());
            } catch (Exception ignored) { }
            return 0;
        }

        @Override public void close() throws InterruptedException {
            running = false;
            thread.interrupt();
            thread.join();
        }
    }
}
