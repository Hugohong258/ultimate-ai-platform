package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real native tests: pass -Dultimate.image.integration.binary=/absolute/path/to/magick. */
class ImageProcessingNativeTest {
    @TempDir Path temporary;
    String binary;
    Path workspace;
    ImageProcessingTool tool;
    final ImageProcessRunner runner = new ImageProcessRunner.Native();

    @BeforeEach void setup() throws Exception {
        binary = System.getProperty("ultimate.image.integration.binary");
        assumeTrue(binary != null && Files.isExecutable(Path.of(binary)), "Explicit ImageMagick executable is required");
        workspace = Files.createDirectories(temporary.resolve("managed/tenant-a"));
        tool = new ImageProcessingTool(temporary.resolve("managed"), binary, runner, Duration.ofSeconds(60));
        var image = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 128; x++)
            image.setRGB(x, y, new Color(60 + x, 40 + y, 50).getRGB());
        assertThat(ImageIO.write(image, "png", workspace.resolve("with spaces.png").toFile())).isTrue();
        assertThat(ImageIO.write(image, "jpeg", workspace.resolve("input.jpeg").toFile())).isTrue();
    }

    String run(List<String> inputs, String name, String operation, String format, int width, int height,
               int x, int y, double brightness, double contrast, String watermark) {
        return tool.processImages(workspace.toString(), inputs, ".", name, operation, format,
                width, height, x, y, 85, brightness, contrast, watermark);
    }

    void clean() throws Exception {
        try (var paths = Files.list(workspace)) {
            assertThat(paths.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".ultimate-image-"));
        }
    }

    @Test void convertsRealJpegAndPngToWebpAndPreservesInputs() throws Exception {
        byte[] original = Files.readAllBytes(workspace.resolve("with spaces.png"));
        assertThat(run(List.of("with spaces.png", "input.jpeg"), "converted", "convert", "webp", 0, 0, 0, 0, 0, 0, ""))
                .startsWith("Image processing completed");
        for (int i = 1; i <= 2; i++) {
            Path output = workspace.resolve("converted/image-0" + i + ".webp");
            byte[] bytes = Files.readAllBytes(output);
            assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            assertThat(new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("WEBP");
            var info = runner.run(List.of(binary, "identify", "-format", "%m %w %h", output.toString()),
                    workspace, Map.of(), Duration.ofSeconds(5));
            assertThat(info.exitCode()).isZero();
            assertThat(info.output()).isEqualTo("WEBP 128 96");
        }
        assertThat(Files.readAllBytes(workspace.resolve("with spaces.png"))).isEqualTo(original);
        clean();
    }

    @Test void resizesAndRendersLiteralWatermarkPixels() throws Exception {
        assertThat(run(List.of("with spaces.png"), "plain", "resize", "png", 64, 64, 0, 0, 0, 0, ""))
                .startsWith("Image processing completed");
        assertThat(run(List.of("with spaces.png"), "marked", "resize", "png", 64, 64, 0, 0, 0, 0, "%@"))
                .startsWith("Image processing completed");
        var plain = ImageIO.read(workspace.resolve("plain/image-01.png").toFile());
        var marked = ImageIO.read(workspace.resolve("marked/image-01.png").toFile());
        assertThat(plain.getWidth()).isEqualTo(64);
        assertThat(plain.getHeight()).isEqualTo(48);
        assertThat(marked.getRGB(63, 47)).isNotEqualTo(plain.getRGB(63, 47));
        clean();
    }

    @Test void cropsExactRectangleAndActuallyAdjustsColor() throws Exception {
        assertThat(run(List.of("with spaces.png"), "crop", "crop", "png", 16, 20, 4, 6, 0, 0, ""))
                .startsWith("Image processing completed");
        var cropped = ImageIO.read(workspace.resolve("crop/image-01.png").toFile());
        assertThat(cropped.getWidth()).isEqualTo(16);
        assertThat(cropped.getHeight()).isEqualTo(20);
        assertThat(new Color(cropped.getRGB(0, 0)).getRed()).isBetween(62, 66);
        assertThat(run(List.of("with spaces.png"), "color", "color-correct", "png", 0, 0, 0, 0, 30, 0, ""))
                .startsWith("Image processing completed");
        var adjusted = ImageIO.read(workspace.resolve("color/image-01.png").toFile());
        assertThat(new Color(adjusted.getRGB(0, 0)).getRed()).isGreaterThan(80);
        assertThat(run(List.of("with spaces.png"), "invalid", "crop", "png", 16, 20, 120, 90, 0, 0, ""))
                .contains("Crop rectangle exceeds");
        assertThat(workspace.resolve("invalid")).doesNotExist();
        clean();
    }

    @Test void stripsRealEmbeddedCommentMetadata() throws Exception {
        Path annotated = workspace.resolve("private.jpeg");
        var setMetadata = runner.run(List.of(binary, workspace.resolve("input.jpeg").toString(), "-set", "comment",
                "PRIVATE_META_SENTINEL", annotated.toString()), workspace, Map.of(), Duration.ofSeconds(5));
        assertThat(setMetadata.exitCode()).isZero();
        assertThat(new String(Files.readAllBytes(annotated), java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains("PRIVATE_META_SENTINEL");
        assertThat(run(List.of("private.jpeg"), "stripped", "convert", "jpeg", 0, 0, 0, 0, 0, 0, ""))
                .startsWith("Image processing completed");
        String output = new String(Files.readAllBytes(workspace.resolve("stripped/image-01.jpeg")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(output).doesNotContain("PRIVATE_META_SENTINEL");
        clean();
    }

    @Test void orientsRealExifBeforeCropAndRemovesTheExifProfile() throws Exception {
        byte[] artist = "PRIVATE_EXIF_SENTINEL\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var tiff = java.nio.ByteBuffer.allocate(38 + artist.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        tiff.putShort((short) 0x013b).putShort((short) 2).putInt(artist.length).putInt(38);
        tiff.putInt(0).put(artist);
        byte[] jpeg = Files.readAllBytes(workspace.resolve("input.jpeg"));
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(jpeg, 0, 2);
        int segmentSize = 2 + 6 + tiff.capacity();
        bytes.write(0xff); bytes.write(0xe1); bytes.write(segmentSize >>> 8); bytes.write(segmentSize & 0xff);
        bytes.write("Exif\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        bytes.write(tiff.array()); bytes.write(jpeg, 2, jpeg.length - 2);
        Files.write(workspace.resolve("oriented.jpeg"), bytes.toByteArray());
        assertThat(run(List.of("oriented.jpeg"), "oriented", "convert", "png", 0, 0, 0, 0, 0, 0, ""))
                .startsWith("Image processing completed");
        Path output = workspace.resolve("oriented/image-01.png");
        var image = ImageIO.read(output.toFile());
        assertThat(image.getWidth()).isEqualTo(96);
        assertThat(image.getHeight()).isEqualTo(128);
        assertThat(new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("PRIVATE_EXIF_SENTINEL");
        assertThat(run(List.of("oriented.jpeg"), "oriented-crop", "crop", "png", 32, 110, 0, 0, 0, 0, ""))
                .startsWith("Image processing completed");
        assertThat(ImageIO.read(workspace.resolve("oriented-crop/image-01.png").toFile()).getHeight()).isEqualTo(110);
        clean();
    }

    @Test void aRealBadSecondImageLeavesNoPublishedFirstImage() throws Exception {
        Files.writeString(workspace.resolve("bad.png"), "this is not PNG data");
        assertThat(run(List.of("with spaces.png", "bad.png"), "failed-batch", "convert", "png", 0, 0, 0, 0, 0, 0, ""))
                .contains("Image Processing Error");
        assertThat(workspace.resolve("failed-batch")).doesNotExist();
        assertThat(workspace.resolve("with spaces.png")).exists();
        clean();
    }

    @Test void processesTenImagesAndKeepsConcurrentBatchesSeparate() throws Exception {
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String name = "image-" + i + ".png";
            Files.copy(workspace.resolve("with spaces.png"), workspace.resolve(name));
            inputs.add(name);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> run(inputs, "batch-a", "resize", "png", 32, 32, 0, 0, 0, 0, ""));
            var second = executor.submit(() -> run(List.of("input.jpeg"), "batch-b", "convert", "png", 0, 0, 0, 0, 0, 0, ""));
            assertThat(first.get()).startsWith("Image processing completed");
            assertThat(second.get()).startsWith("Image processing completed");
        }
        try (var paths = Files.list(workspace.resolve("batch-a"))) { assertThat(paths.count()).isEqualTo(10); }
        assertThat(workspace.resolve("batch-b/image-01.png")).exists();
        clean();
    }
}
