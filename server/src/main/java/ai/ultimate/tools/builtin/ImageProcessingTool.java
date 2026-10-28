package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Bounded raster processing; workspace authorization belongs to the invoking host. */
@Component
public class ImageProcessingTool implements UltimateTool {
    private static final long FILE_LIMIT = 25L * 1024 * 1024;
    private static final long BATCH_LIMIT = 100L * 1024 * 1024;
    private static final Semaphore CAPACITY = new Semaphore(2);
    private static final Set<String> OPERATIONS = Set.of("convert", "resize", "crop", "color-correct");
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "webp");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ImageProcessingTool.class.getName());
    private record Artifact(String path, String format, int width, int height, long bytes) { }
    private record Reply(String requestId, String status, String code, String message, List<Artifact> artifacts) { }
    private static final class Failure extends IOException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    private static final String POLICY = """
            <policymap>
              <policy domain="delegate" rights="none" pattern="*"/>
              <policy domain="filter" rights="none" pattern="*"/>
              <policy domain="coder" rights="none" pattern="*"/>
              <policy domain="coder" rights="read|write" pattern="{PNG,JPEG,WEBP,INFO}"/>
              <policy domain="coder" rights="read" pattern="{ICC,ICM}"/>
              <policy domain="path" rights="none" pattern="@*"/>
              <policy domain="resource" name="memory" value="128MiB"/>
              <policy domain="resource" name="map" value="256MiB"/>
              <policy domain="resource" name="disk" value="256MiB"/>
              <policy domain="resource" name="width" value="8192"/>
              <policy domain="resource" name="height" value="8192"/>
              <policy domain="resource" name="list-length" value="2"/>
              <policy domain="resource" name="thread" value="1"/>
              <policy domain="resource" name="time" value="30"/>
            </policymap>
            """;

    private final Path managedRoot;
    private final String binary;
    private final ImageProcessRunner runner;
    private final Duration budget;

    public ImageProcessingTool() {
        this(Path.of(System.getProperty("user.home"), "ultimate-managed-workspaces"),
                System.getProperty("ultimate.tools.image.magick", "magick"),
                new ImageProcessRunner.Native(), Duration.ofSeconds(60));
    }

    ImageProcessingTool(Path managedRoot, String binary, ImageProcessRunner runner, Duration budget) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.binary = binary;
        this.runner = runner;
        this.budget = budget;
    }

    @Tool(description = "Process 1-10 local JPEG/PNG/WebP images in an authorized managed workspace. "
            + "Convert to png/jpeg/webp, resize to fit a width/height box without upscaling, "
            + "crop after EXIF orientation using pixel offsets, or color-correct brightness/contrast. "
            + "All operations strip metadata and optionally add a literal text watermark. "
            + "Outputs are published together in a NEW named folder under outputDirectory; "
            + "existing files/folders are never overwritten. Returns JSON with requestId, status, code, message "
            + "and verified artifacts (relative path, format, width, height, bytes). JPEG transparency is flattened "
            + "onto white; PNG/WebP preserve alpha. WebP quality 100 is lossless. "
            + "Limits: 25MiB per input, 100MiB total, dimensions 8192, 60 seconds per batch.")
    public String processImages(
            @ToolParam(description = "Authorized absolute directory below ~/ultimate-managed-workspaces")
            String workspacePath,
            @ToolParam(description = "List of existing relative JPEG/PNG/WebP input paths; first frame only") List<String> inputPaths,
            @ToolParam(description = "Existing relative output parent directory, for example results or .")
            String outputDirectory,
            @ToolParam(description = "New output folder name: letters, numbers, underscores or hyphens")
            String outputName,
            @ToolParam(description = "convert, resize, crop or color-correct") String operation,
            @ToolParam(description = "png, jpeg or webp") String outputFormat,
            @ToolParam(description = "Resize/crop width 1-8192; zero for convert/color-correct") int width,
            @ToolParam(description = "Resize/crop height 1-8192; zero for convert/color-correct") int height,
            @ToolParam(description = "Crop left pixel offset; zero otherwise") int offsetX,
            @ToolParam(description = "Crop top pixel offset; zero otherwise") int offsetY,
            @ToolParam(description = "Output quality 1-100, for example 85") int quality,
            @ToolParam(description = "Brightness percent -100..100; zero except for color-correct") double brightness,
            @ToolParam(description = "Contrast percent -100..100; zero except for color-correct") double contrast,
            @ToolParam(description = "Optional literal watermark text, at most 80 characters; empty for none")
            String watermark) {
        Path runtime = null;
        boolean acquired = false;
        String requestId = UUID.randomUUID().toString();
        List<Artifact> published = List.of();
        Reply result;
        try {
            validate(inputPaths, outputName, operation, outputFormat, width, height,
                    offsetX, offsetY, quality, brightness, contrast, watermark);
            if (!CAPACITY.tryAcquire()) return encode(new Reply(requestId, "error", "BUSY",
                    "Image Processing Busy: two batches are already running.", List.of()));
            acquired = true;
            long deadline = System.nanoTime() + budget.toNanos();
            Path root = managedRoot.toRealPath();
            if (workspacePath == null || !Path.of(workspacePath).isAbsolute())
                throw new IllegalArgumentException("Workspace path must be absolute.");
            Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
            if (workspace.equals(root) || !workspace.startsWith(root))
                throw new IllegalArgumentException("Workspace must be below the managed root.");
            workspace = contained(root, workspace, true);
            Path outputParent = contained(workspace, relative(outputDirectory), true);
            Path target = outputParent.resolve(outputName);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Output folder already exists.");

            List<Path> inputs = new ArrayList<>();
            Set<Path> unique = new HashSet<>();
            long size = 0;
            for (String input : inputPaths) {
                Path real = contained(workspace, relative(input), false);
                if (!unique.add(real)) throw new IllegalArgumentException("Duplicate input path.");
                long bytes = Files.size(real);
                if (bytes <= 0 || bytes > FILE_LIMIT) throw new IllegalArgumentException("Input exceeds 25MiB or is empty.");
                size += bytes;
                if (size > BATCH_LIMIT) throw new IllegalArgumentException("Batch exceeds 100MiB.");
                inputs.add(real);
            }
            runtime = Files.createTempDirectory(workspace, ".ultimate-image-").toRealPath();
            Path results = Files.createDirectory(runtime.resolve("results"));
            Files.writeString(runtime.resolve("policy.xml"), POLICY);
            Path srgb = runtime.resolve("srgb.icc");
            Files.write(srgb, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
            Map<String, String> environment = Map.of(
                    "MAGICK_CONFIGURE_PATH", runtime.toString(),
                    "MAGICK_TEMPORARY_PATH", runtime.toString(),
                    "TMPDIR", runtime.toString(), "TEMP", runtime.toString(), "TMP", runtime.toString());
            long stagedBytes = 0;
            List<Artifact> artifacts = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                Path staged = runtime.resolve("input-" + index + ".image");
                contained(workspace, inputs.get(index), false);
                stagedBytes += stage(inputs.get(index), staged, deadline, BATCH_LIMIT - stagedBytes);
                contained(workspace, inputs.get(index), false);
                String coder = inputCoder(staged);
                String image = coder + ":" + staged + "[0]";
                List<String> probe = command();
                probe.addAll(List.of(image, "-auto-orient", "-format", "%w %h", "info:"));
                String dimensions = execute(probe, runtime, environment, deadline).trim();
                if (!dimensions.matches("[0-9]+ [0-9]+")) {
                    LOG.log(System.Logger.Level.WARNING, "Invalid probe output: {0}", bounded(dimensions));
                    throw new Failure("INVALID_IMAGE", "Invalid image dimensions.");
                }
                String[] parts = dimensions.split(" ");
                int actualWidth = Integer.parseInt(parts[0]);
                int actualHeight = Integer.parseInt(parts[1]);
                checkDimensions(actualWidth, actualHeight);
                if ("crop".equals(operation)
                        && ((long) offsetX + width > actualWidth || (long) offsetY + height > actualHeight))
                    throw new IllegalArgumentException("Crop rectangle exceeds oriented image dimensions.");

                List<String> command = command();
                command.addAll(List.of(image, "-auto-orient", "-profile", srgb.toString(), "-colorspace", "sRGB"));
                if ("resize".equals(operation)) command.addAll(List.of("-resize", width + "x" + height + ">"));
                if ("crop".equals(operation))
                    command.addAll(List.of("-crop", width + "x" + height + "+" + offsetX + "+" + offsetY, "+repage"));
                if ("color-correct".equals(operation))
                    command.addAll(List.of("-brightness-contrast", brightness + "x" + contrast));
                if (!watermark.isEmpty()) {
                    double scale = "resize".equals(operation)
                            ? Math.min(1, Math.min((double) width / actualWidth, (double) height / actualHeight)) : 1;
                    int canvasWidth = "crop".equals(operation) ? width : Math.max(1, (int) Math.round(actualWidth * scale));
                    int canvasHeight = "crop".equals(operation) ? height : Math.max(1, (int) Math.round(actualHeight * scale));
                    Path overlay = runtime.resolve("watermark-" + index + ".png");
                    watermark(overlay, watermark, canvasWidth, canvasHeight);
                    command.addAll(List.of("PNG:" + overlay, "-gravity", "southeast", "-compose", "over", "-composite"));
                }
                Path output = results.resolve(String.format(Locale.ROOT, "image-%02d.%s", index + 1, outputFormat));
                if ("jpeg".equals(outputFormat))
                    command.addAll(List.of("-background", "white", "-alpha", "remove", "-alpha", "off"));
                if ("webp".equals(outputFormat) && quality == 100)
                    command.addAll(List.of("-define", "webp:lossless=true"));
                command.addAll(List.of("-strip", "-quality", Integer.toString(quality),
                        outputFormat.toUpperCase(Locale.ROOT) + ":" + output));
                execute(command, runtime, environment, deadline);
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) == 0)
                    throw new IOException("ImageMagick did not produce a nonempty image.");
                if (Files.size(output) > FILE_LIMIT) throw new IOException("Output exceeds 25MiB.");
                List<String> verify = command();
                verify.addAll(List.of(outputFormat.toUpperCase(Locale.ROOT) + ":" + output,
                        "-format", "%m %w %h", "info:"));
                String properties = execute(verify, runtime, environment, deadline).trim();
                String[] fields = properties.split(" ");
                if (fields.length != 3 || !fields[0].equalsIgnoreCase(outputFormat)
                        || !fields[1].matches("[0-9]+") || !fields[2].matches("[0-9]+"))
                    throw new Failure("OUTPUT_INVALID", "Unexpected output format or dimensions.");
                int outputWidth = Integer.parseInt(fields[1]), outputHeight = Integer.parseInt(fields[2]);
                checkDimensions(outputWidth, outputHeight);
                double scale = "resize".equals(operation)
                        ? Math.min(1, Math.min((double) width / actualWidth, (double) height / actualHeight)) : 1;
                int expectedWidth = "crop".equals(operation) ? width : Math.max(1, (int) Math.round(actualWidth * scale));
                int expectedHeight = "crop".equals(operation) ? height : Math.max(1, (int) Math.round(actualHeight * scale));
                if (outputWidth != expectedWidth || outputHeight != expectedHeight)
                    throw new Failure("OUTPUT_INVALID", "Output dimensions do not match the requested operation.");
                artifacts.add(new Artifact(workspace.relativize(target.resolve(output.getFileName())).toString()
                        .replace('\\', '/'), outputFormat, outputWidth, outputHeight, Files.size(output)));
            }
            // Recheck the destination before publishing; do not replace a concurrently created target.
            if (!contained(workspace, outputParent, true).equals(outputParent))
                throw new IOException("Output directory changed during execution.");
            remaining(deadline);
            Files.move(results, target);
            published = List.copyOf(artifacts);
            result = new Reply(requestId, "ok", "OK", "Image processing completed.", published);
        } catch (IllegalArgumentException e) {
            result = new Reply(requestId, "error", "VALIDATION_ERROR", "Image Validation Error: "
                    + (e instanceof java.nio.file.InvalidPathException ? "Invalid path." : bounded(e.getMessage())), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new Reply(requestId, "error", "INTERRUPTED", "Image Processing Error: interrupted.", List.of());
        } catch (Failure e) {
            result = new Reply(requestId, "error", e.code, "Image Processing Error: " + e.getMessage(), List.of());
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Image request " + requestId + " failed", e);
            result = new Reply(requestId, "error", "IO_ERROR", "Image Processing Error: cannot read, process or publish image batch.", List.of());
        } finally {
            try {
                cleanup(runtime);
            } catch (IOException e) {
                result = new Reply(requestId, "error", "CLEANUP_FAILED",
                        "Image Cleanup Error: scratch could not be completely removed.", published);
            } finally {
                if (acquired) CAPACITY.release();
            }
        }
        return encode(result);
    }

    private static String encode(Reply reply) {
        try { return JSON.writeValueAsString(reply); }
        catch (JsonProcessingException e) { return "{\"status\":\"error\",\"code\":\"SERIALIZATION_ERROR\"}"; }
    }

    private static Path relative(String path) {
        if (path == null || path.isBlank() || path.length() > 1024)
            throw new IllegalArgumentException("A relative path of at most 1024 characters is required.");
        Path value = Path.of(path);
        if (value.isAbsolute()) throw new IllegalArgumentException("Input/output paths must be relative.");
        return value;
    }

    private static Path contained(Path boundary, Path path, boolean directory) throws IOException {
        Path candidate = boundary.resolve(path).toAbsolutePath().normalize();
        if (!candidate.startsWith(boundary)) throw new IllegalArgumentException("Path escapes workspace.");
        Path cursor = boundary;
        for (Path part : boundary.relativize(candidate)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("Symbolic links are not accepted.");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(boundary)
                || (directory ? !Files.isDirectory(real) : !Files.isRegularFile(real)))
            throw new IllegalArgumentException("Path is outside the workspace or has the wrong type.");
        return real;
    }

    private static void validate(List<String> inputs, String name, String operation, String format,
                                 int width, int height, int x, int y, int quality,
                                 double brightness, double contrast, String watermark) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 10)
            throw new IllegalArgumentException("Provide 1-10 input files.");
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid output folder name.");
        if (operation == null || format == null || !OPERATIONS.contains(operation) || !FORMATS.contains(format))
            throw new IllegalArgumentException("Unsupported operation or output format.");
        boolean dimensional = operation.equals("resize") || operation.equals("crop");
        if (dimensional ? width < 1 || width > 8192 || height < 1 || height > 8192 : width != 0 || height != 0)
            throw new IllegalArgumentException("Invalid dimensions for this operation.");
        if (x < 0 || y < 0 || (!operation.equals("crop") && (x != 0 || y != 0)))
            throw new IllegalArgumentException("Offsets are only supported for crop.");
        if (quality < 1 || quality > 100 || !Double.isFinite(brightness) || !Double.isFinite(contrast)
                || Math.abs(brightness) > 100 || Math.abs(contrast) > 100
                || (!operation.equals("color-correct") && (brightness != 0 || contrast != 0)))
            throw new IllegalArgumentException("Invalid quality or color correction.");
        if (watermark == null || watermark.length() > 80 || watermark.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Watermark must contain at most 80 printable characters.");
    }

    private static long stage(Path source, Path target, long deadline, long remainingBytes) throws IOException {
        var before = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                remaining(deadline);
                total += count;
                if (total > Math.min(FILE_LIMIT, remainingBytes)) throw new IOException("Input grew beyond the file/batch limit while staging.");
                output.write(buffer, 0, count);
            }
            var after = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != total || after.size() != total || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                throw new Failure("INPUT_CHANGED", "Input changed during staging; retry with a stable file.");
            return total;
        }
    }

    private static String inputCoder(Path path) throws IOException {
        try (InputStream raw = Files.newInputStream(path)) {
            byte[] header = raw.readNBytes(12);
            if (header.length == 12 && new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "WEBP";
        }
        try (InputStream raw = Files.newInputStream(path);
             var input = new MemoryCacheImageInputStream(raw)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new Failure("INVALID_IMAGE", "Input is not a supported JPEG/PNG/WebP image.");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!format.equals("PNG") && !format.equals("JPEG")) throw new Failure("INVALID_IMAGE", "Only JPEG/PNG/WebP inputs are supported.");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                checkDimensions(width, height);
                return format;
            } finally { reader.dispose(); }
        } catch (Failure e) { throw e; }
        catch (IOException | RuntimeException e) {
            throw new Failure("INVALID_IMAGE", "Invalid image header.");
        }
    }

    private static void checkDimensions(int width, int height) throws Failure {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long) width * height > 40_000_000)
            throw new Failure("IMAGE_LIMIT", "Image dimensions exceed image limits.");
    }

    private List<String> command() {
        return new ArrayList<>(List.of(binary, "-regard-warnings", "-limit", "memory", "128MiB",
                "-limit", "map", "256MiB", "-limit", "disk", "256MiB", "-limit", "thread", "1"));
    }

    private String execute(List<String> command, Path runtime, Map<String, String> environment, long deadline)
            throws IOException, InterruptedException {
        ImageProcessRunner.Result result = runner.run(command, runtime, environment, remaining(deadline));
        if (result.timedOut()) throw new Failure("TIMEOUT", "Batch exceeded its runtime limit.");
        if (result.exitCode() != 0) {
            LOG.log(System.Logger.Level.DEBUG, "ImageMagick exited {0}: {1}", result.exitCode(), bounded(result.output()));
            throw new Failure("NATIVE_ERROR", "ImageMagick could not process the image.");
        }
        return result.output();
    }

    private static Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new Failure("TIMEOUT", "Batch exceeded its runtime limit.");
        return Duration.ofNanos(nanos);
    }

    private static String bounded(String message) {
        if (message == null) return "Operation failed.";
        return message.substring(0, Math.min(message.length(), 1200));
    }

    private static void watermark(Path target, String text, int width, int height) throws IOException {
        var canvas = new BufferedImage(Math.min(width, 1600), Math.min(height, 120), BufferedImage.TYPE_INT_ARGB);
        var graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int size = Math.min(24, Math.max(8, height / 8));
            for (; size >= 8; size--) {
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
                if (graphics.getFontMetrics().stringWidth(text) + 8 <= canvas.getWidth()
                        && graphics.getFontMetrics().getHeight() + 8 <= canvas.getHeight()) break;
            }
            if (size < 8) throw new IOException("Watermark does not fit the output image.");
            int textWidth = graphics.getFontMetrics().stringWidth(text) + 8;
            int textHeight = graphics.getFontMetrics().getHeight() + 8;
            int left = canvas.getWidth() - textWidth, top = canvas.getHeight() - textHeight;
            graphics.setColor(new Color(0, 0, 0, 160));
            graphics.fillRect(left, top, textWidth, textHeight);
            graphics.setColor(Color.WHITE);
            graphics.drawString(text, left + 4, top + 4 + graphics.getFontMetrics().getAscent());
        } finally { graphics.dispose(); }
        if (!ImageIO.write(canvas, "png", target.toFile())) throw new IOException("Cannot write watermark.");
    }

    private static void cleanup(Path directory) throws IOException {
        if (directory == null) return;
        IOException failure = null;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
        }
        if (failure != null) throw failure;
    }
}
