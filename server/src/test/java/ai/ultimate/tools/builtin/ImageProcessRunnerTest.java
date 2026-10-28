package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageProcessRunnerTest {
    @TempDir Path temporary;

    private List<String> command(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String testClasses = Path.of(ImageProcessRunnerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", testClasses, Probe.class.getName(), mode);
    }

    @Test void drainsLargeRealStdoutAndStderrWithoutUnboundedCapture() throws Exception {
        var result = new ImageProcessRunner.Native().run(command("noise"), temporary, Map.of(), Duration.ofSeconds(10));
        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).hasSize(8192);
        assertThat(result.output()).startsWith("stdout");
    }

    @Test void terminatesAnActualTimedOutNativeProcess() throws Exception {
        long start = System.nanoTime();
        var result = new ImageProcessRunner.Native().run(command("sleep"), temporary, Map.of(), Duration.ofMillis(300));
        assertThat(result.timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test void removesInheritedLoaderAndImageConfigurationVariables() {
        var clean = ImageProcessRunner.Native.environment(Map.of("PATH", "trusted-path", "SystemRoot", "os-root",
                "MAGICK_CONFIGURE_PATH", "untrusted", "LD_PRELOAD", "untrusted", "DYLD_LIBRARY_PATH", "untrusted",
                "JAVA_TOOL_OPTIONS", "untrusted", "SECRET_TOKEN", "private"), temporary, Map.of());
        assertThat(clean).containsEntry("PATH", "trusted-path").containsEntry("SystemRoot", "os-root")
                .containsEntry("MAGICK_CONFIGURE_PATH", temporary.toString()).containsEntry("LC_ALL", "C")
                .doesNotContainKeys("LD_PRELOAD", "DYLD_LIBRARY_PATH", "JAVA_TOOL_OPTIONS", "SECRET_TOKEN");
    }

    @Test void terminatesObservedDescendantsAfterTimeoutAndNormalParentExit() throws Exception {
        for (String mode : List.of("parent-exits", "parent-sleeps")) {
            Path pidFile = temporary.resolve(mode + ".pid");
            var args = new java.util.ArrayList<>(command(mode));
            args.add(pidFile.toString());
            // Allow cold nested JVM startup on Windows; the sleeping parent still exceeds this budget.
            var result = new ImageProcessRunner.Native().run(args, temporary, Map.of(), Duration.ofSeconds(10));
            assertThat(result.timedOut()).as(mode + ": " + result.output()).isEqualTo(mode.equals("parent-sleeps"));
            long pid = Long.parseLong(Files.readString(pidFile));
            var child = ProcessHandle.of(pid);
            try {
                if (child.isPresent()) child.get().onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            } finally { child.ifPresent(ProcessHandle::destroyForcibly); }
        }
    }

    @Test void interruptsAndTerminatesAnActualProcess() throws Exception {
        Path pidFile = temporary.resolve("interrupt.pid");
        var args = new java.util.ArrayList<>(command("pid-sleep"));
        args.add(pidFile.toString());
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { new ImageProcessRunner.Native().run(args, temporary, Map.of(), Duration.ofSeconds(20)); }
            catch (InterruptedException expected) { stopped.set(true); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
        worker.interrupt();
        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(stopped.get()).isTrue();
        long pid = Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].startsWith("parent-")) {
                System.out.println("parent-started");
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                var child = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), "pid-sleep"));
                child.add(args[1]);
                new ProcessBuilder(child).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                System.out.println("child-started");
                Thread.sleep(args[0].equals("parent-exits") ? 800 : 15_000);
                System.out.println("parent-returning");
                return;
            }
            if (args[0].equals("pid-sleep")) {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(15_000);
                return;
            }
            if (args[0].equals("sleep")) { Thread.sleep(15_000); return; }
            System.out.print("stdout");
            for (int i = 0; i < 1000; i++) {
                System.out.print("a".repeat(1024));
                System.err.print("b".repeat(1024));
            }
        }
    }
}
