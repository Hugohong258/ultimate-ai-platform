# Managed image processing

`ImageProcessingTool` is a Spring `@Component` / `UltimateTool`. The existing
`ToolRegistry` discovers its `processImages` method without registry changes.
It uses **ImageMagick 7** on the local host; no external image API or model is
called. Put `magick` on the host's PATH, or set the trusted deployment JVM
property `-Dultimate.tools.image.magick=/absolute/path/to/magick`.

## Workspace setup

The invoking host must authorize the selected workspace for the caller **before
exposing the tool**. The tool is not an authentication or tenant-ownership
service. Provision each caller's workspace as a separate directory below
`~/ultimate-managed-workspaces`; the managed root and workspace must already
exist. Do not give untrusted processes write access to another tenant's
directories. Canonical path checks and isolated scratch directories do not
replace operating-system access controls or a container sandbox.

For example:

```text
~/ultimate-managed-workspaces/project-a/
  uploads/photo.png
  uploads/cover.jpeg
  results/
```

`inputPaths` and `outputDirectory` are relative to the selected workspace;
absolute input/output paths, escaping paths and symbolic links are rejected.
The output parent must exist. `outputName` names a **new batch folder** in that
parent. Existing folders and files are never replaced.

## Calling the tool

The public `processImages` arguments, in order:

| Argument | Meaning |
| --- | --- |
| `workspacePath` | Authorized absolute workspace directory |
| `inputPaths` | List of 1–10 JPEG/PNG/WebP files, e.g. `["uploads/photo.png", "uploads/cover.jpeg"]`; frame zero only |
| `outputDirectory` | Existing relative parent, e.g. `results` |
| `outputName` | New folder, e.g. `web-covers`; letters/numbers/underscore/hyphen, max 64 |
| `operation` | `convert`, `resize`, `crop`, or `color-correct` |
| `outputFormat` | `png`, `jpeg`, or `webp` |
| `width`, `height` | Resize bounding box or crop rectangle, 1–8192; otherwise both zero |
| `offsetX`, `offsetY` | Nonnegative crop offsets; otherwise zero |
| `quality` | 1–100, e.g. 85 |
| `brightness`, `contrast` | Percent adjustment −100…100 for `color-correct`; otherwise both zero |
| `watermark` | Literal text of at most 80 characters, or empty string |

Example JPEG/PNG → WebP batch:

```java
tool.processImages(workspacePath,
    List.of("uploads/photo.png", "uploads/cover.jpeg"),
    "results", "web-covers", "convert", "webp",
    0, 0, 0, 0, 85, 0, 0, "");
```

This produces `results/web-covers/image-01.webp` and `image-02.webp`.
`resize` preserves aspect ratio, fits inside the requested box and does not
upscale. `crop` uses exact pixel coordinates **after EXIF orientation** and
rejects rectangles outside the oriented image. Brightness/contrast correction
uses sRGB. Embedded ICC profiles are transformed to a private standard sRGB
profile before stripping. Untagged inputs use ImageMagick's declared colorspace;
this does not infer a missing camera profile. PNG/WebP preserve alpha; JPEG
flattens transparency onto white. WebP quality 100 uses lossless encoding
(RGB values hidden by completely transparent pixels are not guaranteed).
Every operation strips embedded profiles/metadata; final filenames
are generated rather than taken from ImageMagick expressions.

A watermark can be applied with any operation. It is rendered to a private PNG
using Java's logical font, then composited at the bottom-right. Text is not an
ImageMagick expression, filename or command. Font glyph availability depends on
the host; a text mark that cannot fit at the minimum size returns an error.

## Execution, cleanup and limits

Inputs are copied into a unique `.ultimate-image-*` directory in that workspace.
Only JPEG/PNG/WebP inputs are accepted, regardless of filename extension.
The first frame is processed; this tool does not preserve animations or all
frames of a multi-image file.
Native processing uses a fixed argument vector, no shell, a per-call policy
allowing PNG/JPEG/WebP/INFO coders and read-only ICC/ICM profiles, and disabled delegates/filters/indirect
`@file` reads. These are defense-in-depth restrictions, not a claim of complete
native-code sandboxing. The child environment retains only host PATH and Windows
OS lookup variables; HOME, locale, ImageMagick configuration and temporary paths
are explicitly set. Inherited loader, credential and ImageMagick variables are
not passed through. The configured binary and host PATH must be administrator-controlled.

- Input: 25 MiB per file, 100 MiB per batch, maximum dimension 8192, maximum
  40 million pixels. Limits are checked again while staging growing files.
- ImageMagick: 128 MiB memory, 256 MiB mapped memory, 256 MiB disk cache,
  one native thread, two-image list, 30-second native policy limit.
- Whole batch: 60-second deadline, at most two concurrent invocations.
- Output: 25 MiB per image; errors and native diagnostics are bounded.

All images must finish before the result directory is moved to the designated
output target. A failed image does not publish earlier images from that batch.
Input files are preserved. Paths are checked again around staging; file identity,
size and modification time must remain stable across each copy. Native failure, timeout, invalid image, interruption
and publication failure all run scratch cleanup in `finally`. Only the final
batch directory survives success. Cleanup failures return an explicit error;
the tool does not falsely report successful cleanup. Observed child processes are
terminated on timeout, interruption and normal parent exit. Descendants are
sampled every 25ms; this cannot contain a deliberately detached process that
escapes observation. OS process/job isolation remains a deployment responsibility.

Canonical checks are not a race-free filesystem capability: another process with
write access to workspace ancestors can race checks and opens. Provision exclusive
tenant directory permissions and authorize both workspace and output destination.
The tool does not establish caller identity or infer ownership from a path. A host
crash, JVM forced kill or power loss prevents `finally` cleanup; the deployment
must reclaim abandoned `.ultimate-image-*` directories after ensuring their jobs
are dead. Do not delete a directory belonging to a live job. No such crash janitor
or cross-host lifecycle/authorization service is supplied by this tool.

Results/errors are JSON-encoded Java strings and do not throw from `processImages`.
Run blocking tool callbacks on the host's worker
execution path, not a reactive event-loop thread.

## Result contract

Every invocation receives a generated `requestId`. Inspect `status` and `code`,
not message text. Successful artifacts have workspace-relative forward-slash
paths, decoded format/dimensions and actual byte size. Native decoding verifies
each output's format and expected dimensions **before** the batch is published.

```json
{"requestId":"47e04f04-ea49-44d6-afb4-26f47d04345a","status":"ok","code":"OK","message":"Image processing completed.","artifacts":[{"path":"web-covers/image-01.webp","format":"webp","width":128,"height":96,"bytes":1234}]}
```

The example byte count is illustrative. Stable error codes are `VALIDATION_ERROR`,
`BUSY`, `INVALID_IMAGE`, `IMAGE_LIMIT`, `INPUT_CHANGED`, `TIMEOUT`, `NATIVE_ERROR`,
`OUTPUT_INVALID`, `INTERRUPTED`, `IO_ERROR` and `CLEANUP_FAILED`. Native diagnostics
and absolute private paths are not returned to the model; debug logs are host-only.
Error artifacts are empty unless publication already succeeded and subsequent
scratch cleanup failed (`CLEANUP_FAILED`); in that case the published artifacts
are retained in the result. The framework's default String result converter may
wrap this JSON in a JSON string. Invalid tool-call JSON/type binding is handled
by Spring AI before method invocation; this contract describes method results.

## Verification

From `server` with Java 21 and ImageMagick 7 available:

```sh
./mvnw -Dultimate.image.integration.binary="$(command -v magick)" clean verify
```

On Windows, use `mvnw.cmd` and an absolute `magick.exe` path. Native tests require
that explicit executable property; they are skipped when it is absent. Other
image contract/process tests still run. The dedicated image workflow supplies
the native executable on Linux. The symlink test skips on hosts that prohibit
symlink creation; it runs on a normal Linux filesystem.

### Reference corpus and acceptance matrix

Fixtures are generated locally by the tests (no downloaded user images).

| Area | Objective assertion |
| --- | --- |
| PNG/JPEG/WebP | All nine input/output pairs: native format, dimensions, opaque reference channels within ±4 and unchanged input bytes; PNG→lossless WebP→PNG alpha and opaque/partial-alpha pixels |
| JPEG alpha | Fully transparent area becomes white (250–255 per channel); opaque reference channels stay within ±3 |
| Color | RGB crop/color-adjustment pixels; grayscale equal channels; untagged CMYK→RGB within ±10; embedded linear RGB 128 transforms to sRGB 185–191 before ICC removal |
| Orientation | All eight EXIF orientations: exact corner mapping and width/height; oriented crop; removed EXIF/comment profiles |
| Malformed/unsupported | Truncated PNG, invalid WebP, unsupported SVG; no partial publication and unchanged source bytes |
| Bounds/publication | 12MP resize, ten-image batch, two concurrent calls, third-call BUSY and capacity recovery, traversal/symlinks, byte/pixel limits, output corruption and concurrent destination creation |
| Process | Bounded real stdout/stderr, real timeout/interruption, observed descendant cleanup, inherited environment filtering |
| AI tool boundary | Production Spring AI MethodToolCallbackProvider schema binding → validation → native execution → publication → JSON result; artifact bytes agree with direct invocation; conflicting parameters return a stable error |

These are deterministic generated-reference tests, not an exhaustive photographic
color corpus or a security certification. The callback test does not run an LLM,
Ollama, the chat UI, an authenticated tenant session or the complete application
intent/planning loop. Deployment reviewers must validate those integration layers
using their selected model and authorization configuration.

### Resource observations

Opt in with `-Dultimate.image.benchmark=true` alongside the native executable
property. `ImageProcessingBenchmarkTest` writes
`target/image-processing-benchmark.json`; the image workflow uploads it with
Surefire reports. It records fixture SHA-256s, OS/Java/ImageMagick/CPU-count/heap,
native startup, first image call, 12MP resize, ten-image batch, two concurrent
calls, twelve repeated calls, and an injected slow child timeout followed by
successful recovery. Each case reports elapsed time, sampled process CPU,
workspace logical storage peak, and Linux `/proc` aggregate JVM+descendant RSS
and block I/O counters. Unsupported RSS/I/O observations are `null` on Windows.

Sampling at 50ms can miss short-lived processes and instantaneous peaks; storage
includes fixture and retained output bytes, and CPU/RSS include the test JVM and
sampler. Cached reads may have zero block I/O. These values are observations,
not exact peak measurements. First-call timing is not a flushed OS cold-cache
benchmark. Repetition is a short smoke workload, not a long soak or controlled
memory-pressure run. Whole-AI-workload behavior, production hardware, model
concurrency and pass/fail resource thresholds must be defined and measured by
the deployment reviewer; this report does not establish them.
