package github.sarthakdev143.media_factory.integration.video;

import github.sarthakdev143.media_factory.model.CaptionPosition;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.composition.CompositionCaptionPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionScenePlan;
import github.sarthakdev143.media_factory.model.composition.CompositionTransitionPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCompositionRendererTest {

    private final FfmpegCompositionRenderer renderer = new FfmpegCompositionRenderer();

    @Test
    void buildImageSceneCommandIncludesMotionAndCaptionFilters() {
        CompositionScenePlan scene = new CompositionScenePlan(
                "scene-1",
                SceneType.IMAGE,
                3.0,
                0.0,
                MotionType.ZOOM_IN,
                new CompositionCaptionPlan("Hello world", 0.0, 2.0, CaptionPosition.BOTTOM),
                new CompositionTransitionPlan(TransitionType.CUT, 0.0));

        List<String> command = renderer.buildImageSceneCommand(
                scene,
                Path.of("D:/tmp/scene.jpg"),
                1080,
                1920,
                Path.of("D:/tmp/out.mp4"));

        String filter = valueAfter(command, "-vf");
        assertThat(filter).contains("zoompan");
        assertThat(filter).contains("drawtext");
    }

    @Test
    void buildVideoSceneCommandContainsTrimArguments() {
        CompositionScenePlan scene = new CompositionScenePlan(
                "scene-video",
                SceneType.VIDEO,
                8.5,
                2.0,
                MotionType.NONE,
                null,
                new CompositionTransitionPlan(TransitionType.CUT, 0.0));

        List<String> command = renderer.buildVideoSceneCommand(
                scene,
                Path.of("D:/tmp/scene.mp4"),
                1920,
                1080,
                Path.of("D:/tmp/out.mp4"));

        assertThat(command).containsSequence("-ss", "2.000");
        assertThat(command).containsSequence("-t", "8.500");
        String filter = valueAfter(command, "-vf");
        assertThat(filter).doesNotContain("drawtext");
    }

    @Test
    void buildVisualConcatCommandUsesConcatFilter() {
        List<String> command = renderer.buildVisualConcatCommand(
                List.of(Path.of("a.mp4"), Path.of("b.mp4"), Path.of("c.mp4")),
                Path.of("merged.mp4"));

        String filter = valueAfter(command, "-filter_complex");
        assertThat(filter).contains("concat=n=3:v=1:a=0");
        assertThat(filter).doesNotContain("xfade");
    }

    @Test
    void buildVisualTransitionCommandUsesXfadeWhenRequested() {
        List<CompositionScenePlan> scenes = List.of(
                new CompositionScenePlan(
                        "a",
                        SceneType.IMAGE,
                        2.0,
                        0.0,
                        MotionType.NONE,
                        null,
                        new CompositionTransitionPlan(TransitionType.CUT, 0.0)),
                new CompositionScenePlan(
                        "b",
                        SceneType.IMAGE,
                        2.0,
                        0.0,
                        MotionType.NONE,
                        null,
                        new CompositionTransitionPlan(TransitionType.CROSSFADE, 0.6)));

        List<String> command = renderer.buildVisualTransitionCommand(
                List.of(Path.of("a.mp4"), Path.of("b.mp4")),
                scenes,
                Path.of("merged.mp4"));

        String filter = valueAfter(command, "-filter_complex");
        assertThat(filter).contains("xfade=transition=fade");
        assertThat(filter).contains("duration=0.600");
    }

    private String valueAfter(List<String> values, String flag) {
        int index = values.indexOf(flag);
        return values.get(index + 1);
    }
}
