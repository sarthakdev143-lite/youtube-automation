package github.sarthakdev143.media_factory.model.composition;

import github.sarthakdev143.media_factory.model.OutputPreset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record CompositionRenderPlan(
        OutputPreset outputPreset,
        List<CompositionScenePlan> scenes,
        Path audioPath,
        Map<String, Path> assetPaths,
        double totalDurationSec) {

    public CompositionRenderPlan {
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
        assetPaths = assetPaths == null ? Map.of() : Map.copyOf(assetPaths);
    }
}
