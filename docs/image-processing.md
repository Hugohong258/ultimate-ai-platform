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
| `inputPaths` | List of 1–10 JPEG/PNG files, e.g. `["uploads/photo.png", "uploads/cover.jpeg"]` |
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
uses sRGB. Every operation strips embedded profiles/metadata; final filenames
are generated rather than taken from ImageMagick expressions.

A watermark can be applied with any operation. It is rendered to a private PNG
using Java's logical font, then composited at the bottom-right. Text is not an
ImageMagick expression, filename or command. Font glyph availability depends on
the host; a text mark that cannot fit at the minimum size returns an error.

## Execution, cleanup and limits

Inputs are copied into a unique `.ultimate-image-*` directory in that workspace.
Only JPEG/PNG inputs are accepted, even if another file uses an image extension.
Native processing uses a fixed argument vector, no shell, a per-call policy
allowing only PNG/JPEG/WebP/INFO coders, and disabled delegates/filters/indirect
`@file` reads. These are defense-in-depth restrictions, not a claim of complete
native-code sandboxing.

- Input: 25 MiB per file, 100 MiB per batch, maximum dimension 8192, maximum
  40 million pixels. Limits are checked again while staging growing files.
- ImageMagick: 128 MiB memory, 256 MiB mapped memory, 256 MiB disk cache,
  one native thread, two-image list, 30-second native policy limit.
- Whole batch: 60-second deadline, at most two concurrent invocations.
- Output: 25 MiB per image; errors and native diagnostics are bounded.

All images must finish before the result directory is moved to the designated
output target. A failed image does not publish earlier images from that batch.
Input files are preserved. Native failure, timeout, invalid image, interruption
and publication failure all run scratch cleanup in `finally`. Only the final
batch directory survives success. Cleanup failures return an explicit error;
the tool does not falsely report successful cleanup. Results/errors are strings
and do not throw to the model. Run blocking tool callbacks on the host's worker
execution path, not a reactive event-loop thread.

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

Tests exercise real JPEG/PNG → WebP output, exact crop and resize dimensions,
color changes, literal watermark pixels, synthetic EXIF orientation and removal,
comment stripping, ten-input batches, concurrent calls, partial-batch failure,
traversal, byte limits, overwrite races, cleanup, and real process stream/timeout
behavior. No paid service, production credential, user upload or model is needed.
