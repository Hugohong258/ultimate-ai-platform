package ai.ultimate.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Generated, redistributable reference corpus; assertions inspect actual artifacts. */
class ImageProcessingAcceptanceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;
    Path workspace;
    String binary;
    ImageProcessingTool tool;
    final ImageProcessRunner runner = new ImageProcessRunner.Native();

    @BeforeEach void setup() throws Exception {
        binary = System.getProperty("ultimate.image.integration.binary");
        assumeTrue(binary != null && Files.isExecutable(Path.of(binary)), "Explicit ImageMagick executable required");
        workspace = Files.createDirectories(temporary.resolve("managed/tenant-a"));
        tool = new ImageProcessingTool(temporary.resolve("managed"), binary, runner, Duration.ofSeconds(60));
        var rgba = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++)
            rgba.setRGB(x, y, new Color(40, 120, 200, x < 20 ? 0 : x < 40 ? 128 : 255).getRGB());
        ImageIO.write(rgba, "png", workspace.resolve("alpha.png").toFile());
    }

    String convert(String input, String name, String format) {
        return tool.processImages(workspace.toString(), List.of(input), ".", name, "convert", format,
                0, 0, 0, 0, 100, 0, 0, "");
    }

    @Test void returnsCorrelatedJsonWithVerifiedArtifactPropertiesAndStableErrors() throws Exception {
        JsonNode result = JSON.readTree(convert("alpha.png", "png", "png"));
        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(result.path("code").asText()).isEqualTo("OK");
        assertThat(result.path("requestId").asText()).matches("[a-f0-9-]{36}");
        JsonNode artifact = result.path("artifacts").get(0);
        assertThat(artifact.path("path").asText()).isEqualTo("png/image-01.png");
        assertThat(artifact.path("format").asText()).isEqualTo("png");
        assertThat(artifact.path("width").asInt()).isEqualTo(64);
        assertThat(artifact.path("height").asInt()).isEqualTo(48);
        assertThat(artifact.path("bytes").asLong()).isEqualTo(Files.size(workspace.resolve("png/image-01.png")));
        JsonNode error = JSON.readTree(convert("alpha.png", "png", "png"));
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("artifacts").isEmpty()).isTrue();
        assertThat(error.path("requestId").asText()).isNotEqualTo(result.path("requestId").asText());
    }

    @Test void preservesAlphaAndOpaquePixelsAcrossLosslessWebpRoundTrip() throws Exception {
        convert("alpha.png", "webp", "webp");
        String result = convert("webp/image-01.webp", "roundtrip", "png");
        assertThat(workspace.resolve("roundtrip/image-01.png")).as(result).exists();
        var image = ImageIO.read(workspace.resolve("roundtrip/image-01.png").toFile());
        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getHeight()).isEqualTo(48);
        for (int x : new int[]{25, 50}) assertThat(image.getRGB(x, 24)).isEqualTo(
                ImageIO.read(workspace.resolve("alpha.png").toFile()).getRGB(x, 24));
        assertThat(new Color(image.getRGB(5, 24), true).getAlpha()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"png,png", "png,jpeg", "png,webp", "jpeg,png", "jpeg,jpeg", "jpeg,webp", "webp,png", "webp,jpeg", "webp,webp"})
    void convertsEveryInputOutputFormatPairWithReferenceColor(String inputFormat, String outputFormat) throws Exception {
        String input = "alpha.png";
        if (!inputFormat.equals("png")) {
            input = "input." + inputFormat;
            var fixture = runner.run(List.of(binary, workspace.resolve("alpha.png").toString(), "-background", "white",
                    "-alpha", "remove", "-alpha", "off", "-quality", "100", "-define", "webp:lossless=true",
                    workspace.resolve(input).toString()), workspace, Map.of(), Duration.ofSeconds(5));
            assertThat(fixture.exitCode()).as(fixture.output()).isZero();
        }
        byte[] before = Files.readAllBytes(workspace.resolve(input));
        assertThat(JSON.readTree(convert(input, "matrix", outputFormat)).path("code").asText()).isEqualTo("OK");
        Path output = workspace.resolve("matrix/image-01." + outputFormat);
        var identify = runner.run(List.of(binary, "identify", "-format", "%m %w %h", output.toString()),
                workspace, Map.of(), Duration.ofSeconds(5));
        assertThat(identify.exitCode()).isZero();
        assertThat(identify.output()).isEqualTo(outputFormat.toUpperCase(java.util.Locale.ROOT) + " 64 48");
        Path png = workspace.resolve("decoded.png");
        var decode = runner.run(List.of(binary, output.toString(), "PNG32:" + png), workspace, Map.of(), Duration.ofSeconds(5));
        assertThat(decode.exitCode()).isZero();
        Color opaque = new Color(ImageIO.read(png.toFile()).getRGB(55, 24));
        assertThat(opaque.getRed()).isBetween(36, 44);
        assertThat(opaque.getGreen()).isBetween(116, 124);
        assertThat(opaque.getBlue()).isBetween(196, 204);
        assertThat(Files.readAllBytes(workspace.resolve(input))).isEqualTo(before);
    }

    @Test void flattensJpegTransparencyOntoWhiteAndPreservesOpaqueColor() throws Exception {
        convert("alpha.png", "jpeg", "jpeg");
        var image = ImageIO.read(workspace.resolve("jpeg/image-01.jpeg").toFile());
        assertThat(image.getColorModel().hasAlpha()).isFalse();
        Color transparent = new Color(image.getRGB(5, 24));
        assertThat(transparent.getRed()).isBetween(250, 255);
        assertThat(transparent.getGreen()).isBetween(250, 255);
        assertThat(transparent.getBlue()).isBetween(250, 255);
        Color opaque = new Color(image.getRGB(55, 24));
        assertThat(opaque.getRed()).isBetween(37, 43);
        assertThat(opaque.getGreen()).isBetween(117, 123);
        assertThat(opaque.getBlue()).isBetween(197, 203);
    }

    @Test void convertsEmbeddedLinearRgbProfileBeforeStrippingIt() throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, 0x808040);
        ImageIO.write(image, "png", workspace.resolve("gray.png").toFile());
        Path profile = workspace.resolve("linear.icc");
        Files.write(profile, ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData());
        var fixture = runner.run(List.of(binary, workspace.resolve("gray.png").toString(), "+profile", "*",
                "-profile", profile.toString(), "PNG24:" + workspace.resolve("profiled.png")),
                workspace, Map.of(), Duration.ofSeconds(10));
        assertThat(fixture.exitCode()).as(fixture.output()).isZero();
        convert("profiled.png", "srgb", "png");
        var output = ImageIO.read(workspace.resolve("srgb/image-01.png").toFile());
        // Linear-light 128/255 encodes to approximately 188/255 in sRGB.
        assertThat(new Color(output.getRGB(8, 8)).getRed()).isBetween(185, 191);
        var profiles = runner.run(List.of(binary, "identify", "-format", "%[profiles]",
                workspace.resolve("srgb/image-01.png").toString()), workspace, Map.of(), Duration.ofSeconds(5));
        assertThat(profiles.output()).doesNotContain("icc", "exif");
    }

    @Test void executesStructuredSpringAiCallbackAndReturnsTheSameArtifactContract() throws Exception {
        var callback = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()[0];
        var arguments = Map.ofEntries(Map.entry("workspacePath", workspace.toString()),
                Map.entry("inputPaths", List.of("alpha.png")), Map.entry("outputDirectory", "."),
                Map.entry("outputName", "callback"), Map.entry("operation", "convert"),
                Map.entry("outputFormat", "png"), Map.entry("width", 0), Map.entry("height", 0),
                Map.entry("offsetX", 0), Map.entry("offsetY", 0), Map.entry("quality", 100),
                Map.entry("brightness", 0), Map.entry("contrast", 0), Map.entry("watermark", ""));
        JsonNode callbackResult = JSON.readTree(callback.call(JSON.writeValueAsString(arguments)));
        // Spring AI's default converter serializes a String return value as a JSON string.
        if (callbackResult.isTextual()) callbackResult = JSON.readTree(callbackResult.asText());
        JsonNode directResult = JSON.readTree(convert("alpha.png", "direct", "png"));
        assertThat(callbackResult.path("code")).isEqualTo(directResult.path("code"));
        assertThat(Files.readAllBytes(workspace.resolve("callback/image-01.png")))
                .isEqualTo(Files.readAllBytes(workspace.resolve("direct/image-01.png")));
        var invalid = new java.util.HashMap<String, Object>(arguments);
        invalid.put("width", 5); // conflicting convert parameter
        JsonNode error = JSON.readTree(callback.call(JSON.writeValueAsString(invalid)));
        if (error.isTextual()) error = JSON.readTree(error.asText());
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void orientsAllExifCornerMappingsBeforeRemovingMetadata(int orientation) throws Exception {
        var source = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        int[] colors = {Color.RED.getRGB(), Color.GREEN.getRGB(), Color.BLUE.getRGB(), Color.YELLOW.getRGB()};
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) source.setRGB(x, y, colors[(y / 24) * 2 + x / 32]);
        Path jpegPath = workspace.resolve("corners.jpeg");
        ImageIO.write(source, "jpeg", jpegPath.toFile());
        var decoded = ImageIO.read(jpegPath.toFile());
        int[] reference = {decoded.getRGB(5, 5), decoded.getRGB(58, 5), decoded.getRGB(5, 42), decoded.getRGB(58, 42)};
        byte[] jpeg = Files.readAllBytes(jpegPath);
        var tiff = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0).putInt(0);
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(jpeg, 0, 2);
        bytes.write(new byte[]{(byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0});
        bytes.write(tiff.array()); bytes.write(jpeg, 2, jpeg.length - 2);
        Files.write(jpegPath, bytes.toByteArray());
        assertThat(JSON.readTree(convert("corners.jpeg", "oriented", "png")).path("code").asText()).isEqualTo("OK");
        var output = ImageIO.read(workspace.resolve("oriented/image-01.png").toFile());
        assertThat(output.getWidth()).isEqualTo(orientation >= 5 ? 48 : 64);
        assertThat(output.getHeight()).isEqualTo(orientation >= 5 ? 64 : 48);
        int[][] mappings = {{0,1,2,3}, {1,0,3,2}, {3,2,1,0}, {2,3,0,1}, {0,2,1,3}, {2,0,3,1}, {3,1,2,0}, {1,3,0,2}};
        int[] actual = {output.getRGB(5, 5), output.getRGB(output.getWidth()-6, 5),
                output.getRGB(5, output.getHeight()-6), output.getRGB(output.getWidth()-6, output.getHeight()-6)};
        for (int i = 0; i < 4; i++) assertThat(actual[i]).isEqualTo(reference[mappings[orientation-1][i]]);
        var profiles = runner.run(List.of(binary, "identify", "-format", "%[profiles]",
                workspace.resolve("oriented/image-01.png").toString()), workspace, Map.of(), Duration.ofSeconds(5));
        assertThat(profiles.output()).doesNotContain("exif");
    }

    @Test void handlesGrayscaleAndCmykChannelsAndRejectsMalformedOrUnsupportedImages() throws Exception {
        for (String colorspace : List.of("gray", "CMYK")) {
            Path fixture = workspace.resolve(colorspace + ".jpeg");
            var created = runner.run(List.of(binary, workspace.resolve("alpha.png").toString(), "-colorspace", colorspace,
                    fixture.toString()), workspace, Map.of(), Duration.ofSeconds(5));
            assertThat(created.exitCode()).as(created.output()).isZero();
            assertThat(JSON.readTree(convert(fixture.getFileName().toString(), colorspace, "png")).path("code").asText()).isEqualTo("OK");
            var image = ImageIO.read(workspace.resolve(colorspace + "/image-01.png").toFile());
            assertThat(image.getWidth()).isEqualTo(64);
            assertThat(image.getHeight()).isEqualTo(48);
            Color pixel = new Color(image.getRGB(55, 24));
            if (colorspace.equals("gray")) {
                assertThat(pixel.getRed()).isEqualTo(pixel.getGreen()).isEqualTo(pixel.getBlue());
            } else {
                assertThat(pixel.getRed()).isBetween(30, 50);
                assertThat(pixel.getGreen()).isBetween(110, 130);
                assertThat(pixel.getBlue()).isBetween(190, 210);
            }
        }
        byte[] original = Files.readAllBytes(workspace.resolve("alpha.png"));
        Files.write(workspace.resolve("truncated.png"), java.util.Arrays.copyOf(original, 35));
        Files.writeString(workspace.resolve("unsupported.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        Files.write(workspace.resolve("broken.webp"), "RIFFxxxxWEBPbroken".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (String bad : List.of("truncated.png", "unsupported.svg", "broken.webp")) {
            JsonNode reply = JSON.readTree(tool.processImages(workspace.toString(), List.of("alpha.png", bad), ".", "bad", "convert", "png",
                    0, 0, 0, 0, 85, 0, 0, ""));
            assertThat(reply.path("status").asText()).as(bad).isEqualTo("error");
            assertThat(reply.path("artifacts").isEmpty()).isTrue();
            assertThat(workspace.resolve("bad")).doesNotExist();
        }
        assertThat(Files.readAllBytes(workspace.resolve("alpha.png"))).isEqualTo(original);
        try (var paths = Files.list(workspace)) {
            assertThat(paths.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".ultimate-image-"));
        }
    }
}
