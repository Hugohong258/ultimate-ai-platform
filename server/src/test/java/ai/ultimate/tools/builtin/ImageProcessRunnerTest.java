package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) { Thread.sleep(15_000); return; }
            System.out.print("stdout");
            for (int i = 0; i < 1000; i++) {
                System.out.print("a".repeat(1024));
                System.err.print("b".repeat(1024));
            }
        }
    }
}
