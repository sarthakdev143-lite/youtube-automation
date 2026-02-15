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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

@Component
public class FfmpegCompositionRenderer implements CompositionRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegCompositionRenderer.class);
    private static final double CUT_TRANSITION_DURATION_SECONDS = 0.001;
    private static final double EPSILON = 1e-9;

    private final FfmpegCommandBuilder ffmpegCommandBuilder;
    private final FfmpegProcessRunner ffmpegProcessRunner;

    public FfmpegCompositionRenderer(
            FfmpegCommandBuilder ffmpegCommandBuilder,
            FfmpegProcessRunner ffmpegProcessRunner) {
        this.ffmpegCommandBuilder = ffmpegCommandBuilder;
        this.ffmpegProcessRunner = ffmpegProcessRunner;
    }

    @Override
    public void renderComposition(CompositionRenderPlan plan, Path outputVideoPath) throws IOException, InterruptedException {
        renderComposition(plan, outputVideoPath, "adhoc-job", progress -> {
        });
    }

    @Override
    public void renderComposition(
            CompositionRenderPlan plan,
            Path outputVideoPath,
            String jobId,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        if (plan.scenes().isEmpty()) {
            throw new IllegalArgumentException("Composition render plan must include at least one scene.");
        }

        Path workDir = Files.createTempDirectory("media-factory-composition-");
        List<Path> sceneClips = new ArrayList<>();
        Path visualTrack = workDir.resolve("visual.mp4");

        double totalTimeline = Math.max(plan.totalDurationSec(), 1.0);
        double computedTotalWork = plan.scenes().stream().mapToDouble(CompositionScenePlan::durationSec).sum()
                + totalTimeline
                + totalTimeline;
        if (computedTotalWork <= 0.0) {
            computedTotalWork = totalTimeline * 3.0;
        }
        final double totalWork = computedTotalWork;

        AtomicInteger lastOverallProgress = new AtomicInteger(-1);
        double completedWork = 0.0;

        try {
            for (int index = 0; index < plan.scenes().size(); index++) {
                CompositionScenePlan scene = plan.scenes().get(index);
                Path assetPath = plan.assetPaths().get(scene.assetId());
                if (assetPath == null) {
                    throw new IllegalArgumentException("Missing asset path for scene assetId=" + scene.assetId());
                }

                Path sceneClip = workDir.resolve("scene-" + index + ".mp4");
                List<String> nvencCommand = scene.type() == SceneType.IMAGE
                        ? buildImageSceneCommand(
                                scene,
                                assetPath,
                                plan.outputPreset().width(),
                                plan.outputPreset().height(),
                                sceneClip,
                                true)
                        : buildVideoSceneCommand(
                                scene,
                                assetPath,
                                plan.outputPreset().width(),
                                plan.outputPreset().height(),
                                sceneClip,
                                true);
                List<String> softwareCommand = scene.type() == SceneType.IMAGE
                        ? buildImageSceneCommand(
                                scene,
                                assetPath,
                                plan.outputPreset().width(),
                                plan.outputPreset().height(),
                                sceneClip,
                                false)
                        : buildVideoSceneCommand(
                                scene,
                                assetPath,
                                plan.outputPreset().width(),
                                plan.outputPreset().height(),
                                sceneClip,
                                false);

                double stageWork = Math.max(scene.durationSec(), 1.0);
                double workAtStageStart = completedWork;
                runEncodingStageWithFallback(
                        jobId,
                        "render-scene-" + index,
                        nvencCommand,
                        softwareCommand,
                        stageWork,
                        sceneClip,
                        stageProgress -> reportOverallProgress(
                                workAtStageStart,
                                stageWork,
                                stageProgress,
                                totalWork,
                                lastOverallProgress,
                                progressConsumer));

                sceneClips.add(sceneClip);
                completedWork += stageWork;
            }

            if (sceneClips.size() == 1) {
                Files.copy(sceneClips.get(0), visualTrack);
                reportOverallProgress(
                        completedWork,
                        totalTimeline,
                        100,
                        totalWork,
                        lastOverallProgress,
                        progressConsumer);
                completedWork += totalTimeline;
            } else {
                boolean hasCrossfade = plan.scenes()
                        .stream()
                        .skip(1)
                        .anyMatch(scene -> scene.transition().type() == TransitionType.CROSSFADE);

                List<String> nvencCombineCommand = hasCrossfade
                        ? buildVisualTransitionCommand(sceneClips, plan.scenes(), visualTrack, true)
                        : buildVisualConcatCommand(sceneClips, visualTrack, true);
                List<String> softwareCombineCommand = hasCrossfade
                        ? buildVisualTransitionCommand(sceneClips, plan.scenes(), visualTrack, false)
                        : buildVisualConcatCommand(sceneClips, visualTrack, false);

                double workAtStageStart = completedWork;
                runEncodingStageWithFallback(
                        jobId,
                        "combine-scene-clips",
                        nvencCombineCommand,
                        softwareCombineCommand,
                        totalTimeline,
                        visualTrack,
                        stageProgress -> reportOverallProgress(
                                workAtStageStart,
                                totalTimeline,
                                stageProgress,
                                totalWork,
                                lastOverallProgress,
                                progressConsumer));

                completedWork += totalTimeline;
            }

            double workAtMuxStart = completedWork;
            ffmpegProcessRunner.runCommand(
                    jobId,
                    "mux-audio-and-visual-tracks",
                    buildAudioMuxCommand(plan.audioPath(), visualTrack, outputVideoPath),
                    totalTimeline,
                    outputVideoPath,
                    stageProgress -> reportOverallProgress(
                            workAtMuxStart,
                            totalTimeline,
                            stageProgress,
                            totalWork,
                            lastOverallProgress,
                            progressConsumer));
            progressConsumer.accept(100);
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
        return buildImageSceneCommand(scene, assetPath, width, height, outputPath, ffmpegCommandBuilder.nvencAvailable());
    }

    List<String> buildImageSceneCommand(
            CompositionScenePlan scene,
            Path assetPath,
            int width,
            int height,
            Path outputPath,
            boolean useNvenc) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommandBuilder.ffmpegBinary());
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
        ffmpegCommandBuilder.appendVideoEncoding(command, useNvenc);
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildVideoSceneCommand(
            CompositionScenePlan scene,
            Path assetPath,
            int width,
            int height,
            Path outputPath) {
        return buildVideoSceneCommand(scene, assetPath, width, height, outputPath, ffmpegCommandBuilder.nvencAvailable());
    }

    List<String> buildVideoSceneCommand(
            CompositionScenePlan scene,
            Path assetPath,
            int width,
            int height,
            Path outputPath,
            boolean useNvenc) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommandBuilder.ffmpegBinary());
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
        ffmpegCommandBuilder.appendVideoEncoding(command, useNvenc);
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildVisualConcatCommand(List<Path> sceneClips, Path outputPath) {
        return buildVisualConcatCommand(sceneClips, outputPath, ffmpegCommandBuilder.nvencAvailable());
    }

    List<String> buildVisualConcatCommand(List<Path> sceneClips, Path outputPath, boolean useNvenc) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommandBuilder.ffmpegBinary());
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
        ffmpegCommandBuilder.appendVideoEncoding(command, useNvenc);
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildVisualTransitionCommand(
            List<Path> sceneClips,
            List<CompositionScenePlan> scenes,
            Path outputPath) {
        return buildVisualTransitionCommand(sceneClips, scenes, outputPath, ffmpegCommandBuilder.nvencAvailable());
    }

    List<String> buildVisualTransitionCommand(
            List<Path> sceneClips,
            List<CompositionScenePlan> scenes,
            Path outputPath,
            boolean useNvenc) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommandBuilder.ffmpegBinary());
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
        ffmpegCommandBuilder.appendVideoEncoding(command, useNvenc);
        command.add(outputPath.toString());
        return command;
    }

    List<String> buildAudioMuxCommand(Path audioPath, Path visualTrackPath, Path outputVideoPath) {
        return List.of(
                ffmpegCommandBuilder.ffmpegBinary(),
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
                "-movflags",
                "+faststart",
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

    private void runEncodingStageWithFallback(
            String jobId,
            String stage,
            List<String> nvencCommand,
            List<String> softwareCommand,
            double expectedDurationSeconds,
            Path outputPath,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        if (ffmpegCommandBuilder.nvencAvailable()) {
            try {
                ffmpegProcessRunner.runCommand(
                        jobId,
                        stage + "-nvenc",
                        nvencCommand,
                        expectedDurationSeconds,
                        outputPath,
                        progressConsumer);
                return;
            } catch (IOException nvencError) {
                if (!ffmpegProcessRunner.isNvencFailure(nvencError)) {
                    throw nvencError;
                }
                logger.warn("[NVENC FALLBACK] jobId={} stage={} reason={}", jobId, stage, nvencError.getMessage());
            }
        }

        ffmpegProcessRunner.runCommand(
                jobId,
                stage + "-libx264",
                softwareCommand,
                expectedDurationSeconds,
                outputPath,
                progressConsumer);
    }

    private void reportOverallProgress(
            double completedBeforeStage,
            double stageWork,
            int stageProgressPercent,
            double totalWork,
            AtomicInteger lastOverallProgress,
            IntConsumer progressConsumer) {
        double stageCompleted = (stageWork * Math.max(stageProgressPercent, 0)) / 100.0;
        int overall = (int) Math.floor(((completedBeforeStage + stageCompleted) / totalWork) * 100.0);
        overall = Math.max(0, Math.min(99, overall));
        if (overall > lastOverallProgress.get()) {
            lastOverallProgress.set(overall);
            progressConsumer.accept(overall);
        }
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

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
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
