package github.sarthakdev143.media_factory.integration.video;

import github.sarthakdev143.media_factory.model.CaptionPosition;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.VisualFilterType;
import github.sarthakdev143.media_factory.model.composition.CompositionCaptionPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionColorGradePlan;
import github.sarthakdev143.media_factory.model.composition.CompositionOverlayPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionRenderPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionScenePlan;
import github.sarthakdev143.media_factory.model.composition.CompositionVisualEditPlan;
import github.sarthakdev143.media_factory.service.CompositionRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class FfmpegCompositionRenderer implements CompositionRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegCompositionRenderer.class);
    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";
    private static final double CUT_TRANSITION_DURATION_SECONDS = 0.001;
    private static final double EPSILON = 1e-9;

    @Override
    public void renderComposition(CompositionRenderPlan plan, Path outputVideoPath) throws IOException, InterruptedException {
        if (plan.scenes().isEmpty()) {
            throw new IllegalArgumentException("Composition render plan must include at least one scene.");
        }

        Path workDir = Files.createTempDirectory("media-factory-composition-");
        List<Path> sceneClips = new ArrayList<>();
        Path visualTrack = workDir.resolve("visual.mp4");

        try {
            for (int index = 0; index < plan.scenes().size(); index++) {
                CompositionScenePlan scene = plan.scenes().get(index);
                Path assetPath = plan.assetPaths().get(scene.assetId());
                if (assetPath == null) {
                    throw new IllegalArgumentException("Missing asset path for scene assetId=" + scene.assetId());
                }

                Path sceneClip = workDir.resolve("scene-" + index + ".mp4");
                List<String> renderSceneCommand = scene.type() == SceneType.IMAGE
                        ? buildImageSceneCommand(scene, assetPath, plan.outputPreset().width(), plan.outputPreset().height(), sceneClip)
                        : buildVideoSceneCommand(scene, assetPath, plan.outputPreset().width(), plan.outputPreset().height(), sceneClip);
                runCommand(renderSceneCommand, "render scene " + index);
                sceneClips.add(sceneClip);
            }

            if (sceneClips.size() == 1) {
                Files.copy(sceneClips.get(0), visualTrack);
            } else {
                boolean hasCrossfade = plan.scenes()
                        .stream()
                        .skip(1)
                        .anyMatch(scene -> scene.transition().type() == TransitionType.CROSSFADE);

                List<String> combineCommand = hasCrossfade
                        ? buildVisualTransitionCommand(sceneClips, plan.scenes(), visualTrack)
                        : buildVisualConcatCommand(sceneClips, visualTrack);
                runCommand(combineCommand, "combine scene clips");
            }

            runCommand(buildAudioMuxCommand(plan.audioPath(), visualTrack, outputVideoPath), "mux audio and visual tracks");
        } finally {
            deleteIfExists(visualTrack);
            for (Path sceneClip : sceneClips) {
                deleteIfExists(sceneClip);
            }
            deleteRecursively(workDir);
        }
    }

    List<String> buildImageSceneCommand(
            CompositionScenePlan scene,
            Path assetPath,
            int width,
            int height,
            Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegBinary());
        command.add("-y");
        command.add("-loop");
        command.add("1");
        command.add("-i");
        command.add(assetPath.toString());
        command.add("-t");
        command.add(formatSeconds(scene.durationSec()));
        command.add("-vf");
        command.add(buildSceneFilter(scene, width, height, true));
        command.add("-r");
        command.add("30");
        command.add("-an");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildVideoSceneCommand(
            CompositionScenePlan scene,
            Path assetPath,
            int width,
            int height,
            Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegBinary());
        command.add("-y");
        command.add("-ss");
        command.add(formatSeconds(scene.clipStartSec()));
        command.add("-t");
        command.add(formatSeconds(scene.durationSec()));
        command.add("-i");
        command.add(assetPath.toString());
        command.add("-vf");
        command.add(buildSceneFilter(scene, width, height, false));
        command.add("-an");
        command.add("-r");
        command.add("30");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildVisualConcatCommand(List<Path> sceneClips, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegBinary());
        command.add("-y");
        for (Path sceneClip : sceneClips) {
            command.add("-i");
            command.add(sceneClip.toString());
        }

        StringBuilder filterBuilder = new StringBuilder();
        for (int index = 0; index < sceneClips.size(); index++) {
            filterBuilder.append("[").append(index).append(":v]");
        }
        filterBuilder.append("concat=n=").append(sceneClips.size()).append(":v=1:a=0[v]");

        command.add("-filter_complex");
        command.add(filterBuilder.toString());
        command.add("-map");
        command.add("[v]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildVisualTransitionCommand(
            List<Path> sceneClips,
            List<CompositionScenePlan> scenes,
            Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegBinary());
        command.add("-y");
        for (Path sceneClip : sceneClips) {
            command.add("-i");
            command.add(sceneClip.toString());
        }

        String currentLabel = "[0:v]";
        double accumulatedDuration = scenes.get(0).durationSec();
        StringBuilder filterComplex = new StringBuilder();

        for (int index = 1; index < scenes.size(); index++) {
            CompositionScenePlan scene = scenes.get(index);
            double transitionDuration = scene.transition().type() == TransitionType.CROSSFADE
                    ? scene.transition().durationSec()
                    : CUT_TRANSITION_DURATION_SECONDS;
            double offset = Math.max(accumulatedDuration - transitionDuration, 0.0);

            String outputLabel = "[xf" + index + "]";
            if (filterComplex.length() > 0) {
                filterComplex.append(";");
            }

            filterComplex.append(currentLabel)
                    .append("[").append(index).append(":v]")
                    .append("xfade=transition=fade:duration=").append(formatSeconds(transitionDuration))
                    .append(":offset=").append(formatSeconds(offset))
                    .append(outputLabel);

            currentLabel = outputLabel;
            accumulatedDuration = accumulatedDuration + scene.durationSec() - transitionDuration;
        }

        command.add("-filter_complex");
        command.add(filterComplex.toString());
        command.add("-map");
        command.add(currentLabel);
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildAudioMuxCommand(Path audioPath, Path visualTrackPath, Path outputVideoPath) {
        return List.of(
                resolveFfmpegBinary(),
                "-y",
                "-stream_loop",
                "-1",
                "-i",
                audioPath.toString(),
                "-i",
                visualTrackPath.toString(),
                "-map",
                "1:v:0",
                "-map",
                "0:a:0",
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-shortest",
                outputVideoPath.toString());
    }

    String buildSceneFilter(CompositionScenePlan scene, int width, int height, boolean imageScene) {
        List<String> filters = new ArrayList<>();

        String baseScalePad = "scale="
                + width
                + ":"
                + height
                + ":force_original_aspect_ratio=decrease,pad="
                + width
                + ":"
                + height
                + ":(ow-iw)/2:(oh-ih)/2:black,setsar=1";
        filters.add(baseScalePad);

        if (imageScene && scene.motion() != null && scene.motion() != MotionType.NONE) {
            filters.add(buildMotionFilter(scene.motion(), scene.durationSec(), width, height));
        }

        appendVisualEditFilters(scene.visualEdit(), filters);

        if (scene.caption() != null) {
            filters.add(buildCaptionFilter(scene.caption(), width, height));
        }

        return String.join(",", filters);
    }

    private void appendVisualEditFilters(CompositionVisualEditPlan visualEdit, List<String> filters) {
        if (visualEdit == null) {
            return;
        }

        if (visualEdit.filter() != null && visualEdit.filter() != VisualFilterType.NONE) {
            filters.add(buildFilterPresetExpression(visualEdit.filter()));
        }

        CompositionColorGradePlan colorGrade = visualEdit.colorGrade();
        if (colorGrade != null && hasColorGradeAdjustments(colorGrade)) {
            filters.add(buildColorGradeExpression(colorGrade));
        }

        CompositionOverlayPlan overlay = visualEdit.overlay();
        if (overlay != null && overlay.opacity() > EPSILON) {
            filters.add(buildOverlayExpression(overlay));
        }
    }

    private String buildMotionFilter(MotionType motion, double durationSeconds, int width, int height) {
        String duration = formatSeconds(durationSeconds);
        return switch (motion) {
            case ZOOM_IN -> "zoompan=z='if(lte(on,1),1.0,min(zoom+0.0015,1.15))':"
                    + "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:fps=30:s=" + width + "x" + height;
            case ZOOM_OUT -> "zoompan=z='if(lte(on,1),1.15,max(zoom-0.0015,1.0))':"
                    + "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:fps=30:s=" + width + "x" + height;
            case PAN_LEFT -> "zoompan=z='1.08':x='max(iw/zoom-(iw/zoom)*on/(30*" + duration + "),0)':"
                    + "y='ih/2-(ih/zoom/2)':d=1:fps=30:s=" + width + "x" + height;
            case PAN_RIGHT -> "zoompan=z='1.08':x='min((iw/zoom)*on/(30*" + duration + "),iw/zoom)':"
                    + "y='ih/2-(ih/zoom/2)':d=1:fps=30:s=" + width + "x" + height;
            case NONE -> "";
        };
    }

    private boolean hasColorGradeAdjustments(CompositionColorGradePlan colorGrade) {
        return Math.abs(colorGrade.brightness()) > EPSILON
                || Math.abs(colorGrade.contrast() - 1.0) > EPSILON
                || Math.abs(colorGrade.saturation() - 1.0) > EPSILON;
    }

    private String buildColorGradeExpression(CompositionColorGradePlan colorGrade) {
        return "eq=brightness="
                + formatDecimal(colorGrade.brightness())
                + ":contrast="
                + formatDecimal(colorGrade.contrast())
                + ":saturation="
                + formatDecimal(colorGrade.saturation());
    }

    private String buildFilterPresetExpression(VisualFilterType filter) {
        return switch (filter) {
            case GRAYSCALE -> "hue=s=0";
            case SEPIA -> "colorchannelmixer=.393:.769:.189:.349:.686:.168:.272:.534:.131";
            case COOL -> "colorbalance=rs=-0.05:gs=0.00:bs=0.08";
            case WARM -> "colorbalance=rs=0.08:gs=0.03:bs=-0.03";
            case NONE -> "";
        };
    }

    private String buildOverlayExpression(CompositionOverlayPlan overlay) {
        String hexColor = overlay.hexColor();
        String ffmpegColor = hexColor.startsWith("#") ? "0x" + hexColor.substring(1) : hexColor;
        return "drawbox=x=0:y=0:w=iw:h=ih:color="
                + ffmpegColor
                + "@"
                + formatDecimal(overlay.opacity())
                + ":t=fill";
    }

    private String buildCaptionFilter(CompositionCaptionPlan caption, int width, int height) {
        String text = escapeDrawText(caption.text());
        CaptionPosition position = caption.position() == null ? CaptionPosition.BOTTOM : caption.position();
        return "drawtext=text='"
                + text
                + "':fontcolor=white:fontsize="
                + Math.max(width, height) / 24
                + ":box=1:boxcolor=black@0.45:boxborderw=12:x=(w-text_w)/2:y="
                + position.yExpression()
                + ":enable='between(t,"
                + formatSeconds(caption.startOffsetSec())
                + ","
                + formatSeconds(caption.endOffsetSec())
                + ")'";
    }

    private String escapeDrawText(String text) {
        return text
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'")
                .replace("%", "\\%");
    }

    private void runCommand(List<String> command, String stage) throws IOException, InterruptedException {
        logger.info("Running FFmpeg command for stage {}: {}", stage, String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("FFmpeg timed out during stage: " + stage);
        }

        if (process.exitValue() != 0) {
            throw new IOException(
                    "FFmpeg failed during stage "
                            + stage
                            + " with exit code "
                            + process.exitValue()
                            + ". Output: "
                            + output);
        }
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String resolveFfmpegBinary() {
        String configuredPath = System.getenv(FFMPEG_PATH_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath;
        }
        return DEFAULT_FFMPEG_BINARY;
    }

    private void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }

        try {
            if (Files.notExists(directory)) {
                return;
            }

            try (var pathStream = Files.walk(directory)) {
                pathStream
                        .sorted((left, right) -> right.compareTo(left))
                        .forEach(this::deleteIfExists);
            }
        } catch (IOException ignored) {
            // Cleanup failures are non-fatal.
        }
    }

    private void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup failures are non-fatal.
        }
    }
}
