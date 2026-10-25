package ai.ultimate.tools.builtin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a fixed argument vector without a shell; draining continues after the capture limit. */
@FunctionalInterface
interface ImageProcessRunner {
    Result run(List<String> command, Path directory, Map<String, String> environment,
               Duration timeout) throws IOException, InterruptedException;

    record Result(int exitCode, boolean timedOut, String output) { }

    final class Native implements ImageProcessRunner {
        @Override
        public Result run(List<String> command, Path directory, Map<String, String> environment,
                          Duration timeout) throws IOException, InterruptedException {
            var builder = new ProcessBuilder(List.copyOf(command))
                    .directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            var capture = new Capture();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = reader.read(buffer)) != -1) capture.append(buffer, count);
                } catch (IOException ignored) {
                    // Closing the stream is part of timeout/interruption cleanup.
                }
            });
            try {
                boolean exited = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
                if (!exited) {
                    terminate(process);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                drain.join(1000);
                return new Result(exited ? process.exitValue() : -1, !exited, capture.text());
            } finally {
                if (process.isAlive()) {
                    terminate(process);
                    try { process.waitFor(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                process.getInputStream().close();
                process.getOutputStream().close();
                process.getErrorStream().close();
                drain.interrupt();
            }
        }

        private static void terminate(Process process) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        private static final class Capture {
            private final StringBuilder text = new StringBuilder();
            synchronized void append(char[] chars, int count) {
                int remaining = 8192 - text.length();
                if (remaining > 0) text.append(chars, 0, Math.min(count, remaining));
            }
            synchronized String text() { return text.toString(); }
        }
    }
}
