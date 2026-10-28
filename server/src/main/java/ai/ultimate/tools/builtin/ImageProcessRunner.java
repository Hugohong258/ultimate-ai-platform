package ai.ultimate.tools.builtin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
            long deadline = System.nanoTime() + timeout.toNanos();
            var builder = new ProcessBuilder(List.copyOf(command))
                    .directory(directory.toFile()).redirectErrorStream(true);
            Map<String, String> clean = environment(builder.environment(), directory, environment);
            builder.environment().clear();
            builder.environment().putAll(clean);
            Process process = builder.start();
            Set<ProcessHandle> descendants = new HashSet<>();
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
                boolean exited;
                do {
                    process.descendants().forEach(descendants::add);
                    if (!process.toHandle().isAlive()) {
                        // On some JDKs pipe reaping waits for an inherited child pipe to close.
                        descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                        exited = process.waitFor(2, TimeUnit.SECONDS);
                        break;
                    }
                    long nanos = deadline - System.nanoTime();
                    if (nanos <= 0) { exited = !process.isAlive(); break; }
                    exited = process.waitFor(Math.min(25, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos))),
                            TimeUnit.MILLISECONDS);
                } while (!exited);
                if (!exited) {
                    terminate(process, descendants);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                // A normally exiting parent can leave a child holding the output pipe open.
                descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                drain.join(1000);
                return new Result(exited ? process.exitValue() : -1, !exited, capture.text());
            } finally {
                terminate(process, descendants);
                if (process.isAlive()) {
                    try { process.waitFor(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                process.getInputStream().close();
                process.getOutputStream().close();
                process.getErrorStream().close();
                drain.interrupt();
            }
        }

        static Map<String, String> environment(Map<String, String> inherited, Path directory,
                                               Map<String, String> overrides) {
            Map<String, String> clean = new HashMap<>();
            // Preserve executable/OS lookup, never inherited loader or ImageMagick configuration.
            inherited.forEach((key, value) -> {
                if (key.equalsIgnoreCase("PATH") || key.equalsIgnoreCase("SystemRoot") || key.equalsIgnoreCase("WINDIR"))
                    clean.put(key, value);
            });
            clean.put("HOME", directory.toString());
            clean.put("USERPROFILE", directory.toString());
            clean.put("LANG", "C");
            clean.put("LC_ALL", "C");
            for (String key : List.of("MAGICK_CONFIGURE_PATH", "MAGICK_TEMPORARY_PATH", "TMPDIR", "TEMP", "TMP"))
                clean.put(key, overrides.getOrDefault(key, directory.toString()));
            return clean;
        }

        private static void terminate(Process process, Set<ProcessHandle> observed) {
            process.descendants().forEach(observed::add);
            observed.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
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
