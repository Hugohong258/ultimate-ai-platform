package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolTest {
    @TempDir Path temporary;

    private Path workspace() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("managed/tenant-a"));
        ImageIO.write(new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB), "png", workspace.resolve("input.png").toFile());
        return workspace;
    }

    private ImageProcessingTool tool(ImageProcessRunner runner) {
        return new ImageProcessingTool(temporary.resolve("managed"), "magick", runner, Duration.ofSeconds(10));
    }

    private String convert(ImageProcessingTool tool, Path workspace, List<String> inputs, String parent, String name) {
        return tool.processImages(workspace.toString(), inputs, parent, name, "convert", "png", 0, 0, 0, 0, 85, 0, 0, "");
    }

    private void noScratch(Path workspace) throws Exception {
        try (var files = Files.list(workspace)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".ultimate-image-"));
        }
    }

    @Test void rejectsTraversalAndCrossWorkspaceInputsBeforeExecution() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(temporary.resolve("managed/tenant-b"));
        Files.copy(workspace.resolve("input.png"), temporary.resolve("managed/tenant-b/private.png"));
        var commands = new ArrayList<List<String>>();
        var tool = tool((c, p, e, t) -> { commands.add(c); throw new AssertionError("must not execute"); });
        assertThat(convert(tool, workspace, List.of("../tenant-b/private.png"), ".", "out")).contains("escapes workspace");
        assertThat(convert(tool, workspace, List.of("input.png"), "../tenant-b", "out")).contains("escapes workspace");
        assertThat(convert(tool, temporary, List.of("input.png"), ".", "out")).contains("below the managed root");
        assertThat(commands).isEmpty();
        assertThat(temporary.resolve("managed/tenant-b/private.png")).exists();
    }

    @Test void refusesExistingOutputAndDuplicateInputs() throws Exception {
        Path workspace = workspace();
        Files.createDirectory(workspace.resolve("out"));
        Files.writeString(workspace.resolve("out/keep.txt"), "keep");
        var tool = tool((c, p, e, t) -> { throw new AssertionError("must not execute"); });
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "out")).contains("already exists");
        assertThat(convert(tool, workspace, List.of("input.png", "./input.png"), ".", "other")).contains("Duplicate input");
        assertThat(workspace.resolve("out/keep.txt")).hasContent("keep");
    }

    @Test void rejectsInvalidParametersWithoutThrowing() throws Exception {
        Path workspace = workspace();
        var tool = tool((c, p, e, t) -> { throw new AssertionError("must not execute"); });
        assertThat(convert(tool, workspace, List.of(), ".", "out")).contains("Validation Error");
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "../out")).contains("Validation Error");
        assertThat(tool.processImages(workspace.toString(), List.of("input.png"), ".", "out", "resize", "png", -1, 10, 0, 0, 80, 0, 0, "")).contains("Validation Error");
        assertThat(tool.processImages(workspace.toString(), List.of("input.png"), ".", "out", "color-correct", "png", 0, 0, 0, 0, 80, Double.NaN, 0, "")).contains("Validation Error");
        assertThat(tool.processImages(workspace.toString(), List.of("input.png"), ".", "out", "convert", "png", 0, 0, 0, 0, 80, 0, 0, "bad\nwatermark")).contains("Validation Error");
        assertThat(convert(tool, workspace, java.util.Collections.nCopies(11, "input.png"), ".", "out")).contains("1-10");
    }

    @Test void cleansScratchOnNativeFailureAndDoesNotPublishPartialBatch() throws Exception {
        Path workspace = workspace();
        Files.copy(workspace.resolve("input.png"), workspace.resolve("second.png"));
        var commands = new ArrayList<List<String>>();
        var tool = tool((command, directory, env, timeout) -> {
            commands.add(List.copyOf(command));
            if (command.contains("%m %w %h")) return new ImageProcessRunner.Result(0, false, "PNG 128 96");
            if (command.contains("info:")) return new ImageProcessRunner.Result(0, false, "128 96");
            if (commands.size() == 2) {
                Path output = Path.of(command.getLast().substring("PNG:".length()));
                Files.copy(directory.resolve("input-0.image"), output);
                return new ImageProcessRunner.Result(0, false, "");
            }
            return new ImageProcessRunner.Result(1, false, "second image failed");
        });
        assertThat(convert(tool, workspace, List.of("input.png", "second.png"), ".", "out")).contains("NATIVE_ERROR");
        assertThat(workspace.resolve("out")).doesNotExist();
        assertThat(workspace.resolve("input.png")).exists();
        noScratch(workspace);
        assertThat(commands).hasSize(5);
    }

    @Test void cleansScratchOnTimeoutAndInterruption() throws Exception {
        Path workspace = workspace();
        assertThat(convert(tool((c, p, e, t) -> new ImageProcessRunner.Result(-1, true, "")), workspace,
                List.of("input.png"), ".", "out")).contains("runtime limit");
        noScratch(workspace);
        try {
            assertThat(convert(tool((c, p, e, t) -> { throw new InterruptedException(); }), workspace,
                    List.of("input.png"), ".", "out")).contains("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            noScratch(workspace);
        } finally { Thread.interrupted(); }
    }

    @Test void rejectsNonImagesAndMissingNativeOutput() throws Exception {
        Path workspace = workspace();
        Files.writeString(workspace.resolve("fake.png"), "not an image");
        var tool = tool((c, p, e, t) -> new ImageProcessRunner.Result(0, false, c.contains("info:") ? "128 96" : ""));
        assertThat(convert(tool, workspace, List.of("fake.png"), ".", "out")).contains("supported JPEG/PNG");
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "out")).contains("IO_ERROR");
        assertThat(workspace.resolve("out")).doesNotExist();
        noScratch(workspace);
    }

    @Test void boundsFailureMessagesAndPassesOnlyPrivateNativePaths() throws Exception {
        Path workspace = workspace();
        var tool = tool((command, directory, env, timeout) -> {
            assertThat(command).startsWith("magick", "-regard-warnings").doesNotContain("sh", "bash", "cmd", "-c");
            assertThat(env.get("MAGICK_CONFIGURE_PATH")).isEqualTo(directory.toString());
            assertThat(Files.readString(directory.resolve("policy.xml"))).contains("pattern=\"{PNG,JPEG,WEBP,INFO}\"");
            assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
            return new ImageProcessRunner.Result(1, false, "x".repeat(20_000));
        });
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "out")).hasSizeLessThan(1300);
        noScratch(workspace);
    }

    @Test void rejectsSymbolicLinksWhenSupported() throws Exception {
        Path workspace = workspace();
        Path outside = temporary.resolve("outside.png");
        Files.copy(workspace.resolve("input.png"), outside);
        try { Files.createSymbolicLink(workspace.resolve("link.png"), outside); }
        catch (java.io.IOException | UnsupportedOperationException e) { assumeTrue(false, "Host does not permit symlink creation"); }
        var tool = tool((c, p, e, t) -> { throw new AssertionError("must not execute"); });
        assertThat(convert(tool, workspace, List.of("link.png"), ".", "out")).contains("Symbolic links");
        assertThat(outside).exists();
    }

    @Test void rejectsOversizedFilesAndBatchesBeforeNativeExecution() throws Exception {
        Path workspace = workspace();
        var tool = tool((c, p, e, t) -> { throw new AssertionError("must not execute"); });
        Path large = workspace.resolve("large.png");
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(25L * 1024 * 1024 + 1); }
        assertThat(convert(tool, workspace, List.of("large.png"), ".", "out")).contains("25MiB");
        var batch = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            String name = "large-" + i + ".png";
            try (var file = new java.io.RandomAccessFile(workspace.resolve(name).toFile(), "rw")) {
                file.setLength(21L * 1024 * 1024);
            }
            batch.add(name);
        }
        assertThat(convert(tool, workspace, batch, ".", "out")).contains("100MiB");
        noScratch(workspace);
    }

    @Test void doesNotReplaceAnOutputCreatedDuringProcessing() throws Exception {
        Path workspace = workspace();
        var tool = tool((command, directory, env, timeout) -> {
            if (command.contains("%m %w %h")) return new ImageProcessRunner.Result(0, false, "PNG 128 96");
            if (command.contains("info:")) return new ImageProcessRunner.Result(0, false, "128 96");
            Files.copy(directory.resolve("input-0.image"), Path.of(command.getLast().substring("PNG:".length())));
            Files.createDirectory(workspace.resolve("out"));
            Files.writeString(workspace.resolve("out/keep.txt"), "concurrent output");
            return new ImageProcessRunner.Result(0, false, "");
        });
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "out")).contains("Image Processing Error");
        assertThat(workspace.resolve("out/keep.txt")).hasContent("concurrent output");
        assertThat(workspace.resolve("out/image-01.png")).doesNotExist();
        noScratch(workspace);
    }

    @Test void rejectsAChangedOrCorruptOutputBeforePublication() throws Exception {
        Path workspace = workspace();
        var tool = tool((command, directory, env, timeout) -> {
            if (command.contains("%m %w %h")) return new ImageProcessRunner.Result(0, false, "PNG 1 1");
            if (command.contains("info:")) return new ImageProcessRunner.Result(0, false, "128 96");
            Files.copy(directory.resolve("input-0.image"), Path.of(command.getLast().substring("PNG:".length())));
            return new ImageProcessRunner.Result(0, false, "");
        });
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "out")).contains("OUTPUT_INVALID");
        assertThat(workspace.resolve("out")).doesNotExist();
        noScratch(workspace);
    }

    @Test void boundsConcurrentWorkAndRecoversCapacityAfterNativeFailures() throws Exception {
        Path workspace = workspace();
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tool = tool((command, directory, env, timeout) -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("workers stuck");
            return new ImageProcessRunner.Result(1, false, "private native diagnostic " + directory);
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> convert(tool, workspace, List.of("input.png"), ".", "first"));
            var second = executor.submit(() -> convert(tool, workspace, List.of("input.png"), ".", "second"));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(convert(tool, workspace, List.of("input.png"), ".", "third")).contains("BUSY");
            } finally { release.countDown(); }
            assertThat(first.get()).contains("NATIVE_ERROR").doesNotContain("private native diagnostic", workspace.toString());
            assertThat(second.get()).contains("NATIVE_ERROR");
        }
        assertThat(convert(tool, workspace, List.of("input.png"), ".", "recovered")).contains("NATIVE_ERROR").doesNotContain("BUSY");
        noScratch(workspace);
    }

    @Test void rejectsRelativeWorkspacesAndConflictingParametersBeforeExecution() throws Exception {
        Path workspace = workspace();
        var tool = tool((c, p, e, t) -> { throw new AssertionError("must not execute"); });
        assertThat(convert(tool, Path.of("relative"), List.of("input.png"), ".", "out")).contains("VALIDATION_ERROR");
        for (String operation : List.of("convert", "resize", "crop", "color-correct", "unknown")) {
            assertThat(tool.processImages(workspace.toString(), List.of("input.png"), ".", "out", operation,
                    "svg", 0, 0, 0, 0, 85, 0, 0, "")).contains("VALIDATION_ERROR");
        }
        assertThat(tool.processImages(workspace.toString(), List.of("input.png"), ".", "out", "convert",
                "png", 1, 0, 0, 0, 85, 0, 0, "")).contains("VALIDATION_ERROR");
    }

    @Test void rejectsHugeHeaderDimensionsAndPixelCountsWithoutDecodingTheRaster() throws Exception {
        Path workspace = workspace();
        var tool = tool((c, p, e, t) -> { throw new AssertionError("must not execute"); });
        for (int[] dimensions : List.of(new int[]{8193, 1}, new int[]{8192, 8192})) {
            byte[] bytes = Files.readAllBytes(workspace.resolve("input.png"));
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            buffer.putInt(16, dimensions[0]).putInt(20, dimensions[1]);
            var crc = new java.util.zip.CRC32();
            crc.update(bytes, 12, 17);
            buffer.putInt(29, (int) crc.getValue());
            Files.write(workspace.resolve("huge.png"), bytes);
            assertThat(convert(tool, workspace, List.of("huge.png"), ".", "out")).contains("IMAGE_LIMIT");
            noScratch(workspace);
        }
    }

    @Test void rejectsOutputParentReplacedWithSymlinkDuringProcessing() throws Exception {
        Path workspace = workspace();
        Path outside = Files.createDirectories(temporary.resolve("managed/tenant-b"));
        Path link = workspace.resolve("link-test");
        try { Files.createSymbolicLink(link, outside); Files.delete(link); }
        catch (java.io.IOException | UnsupportedOperationException e) { assumeTrue(false, "Host does not permit symlinks"); }
        Files.createDirectory(workspace.resolve("results"));
        Files.writeString(outside.resolve("keep.txt"), "other tenant");
        var tool = tool((command, directory, env, timeout) -> {
            if (command.contains("%m %w %h")) return new ImageProcessRunner.Result(0, false, "PNG 128 96");
            if (command.contains("info:")) return new ImageProcessRunner.Result(0, false, "128 96");
            Files.copy(directory.resolve("input-0.image"), Path.of(command.getLast().substring("PNG:".length())));
            Files.move(workspace.resolve("results"), workspace.resolve("old-results"));
            Files.createSymbolicLink(workspace.resolve("results"), outside);
            return new ImageProcessRunner.Result(0, false, "");
        });
        assertThat(convert(tool, workspace, List.of("input.png"), "results", "out")).contains("VALIDATION_ERROR");
        assertThat(outside.resolve("keep.txt")).hasContent("other tenant");
        assertThat(outside.resolve("out")).doesNotExist();
        noScratch(workspace);
    }
}
