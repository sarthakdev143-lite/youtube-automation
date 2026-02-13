package github.sarthakdev143.media_factory.dto;

import github.sarthakdev143.media_factory.model.OutputPreset;

import java.util.List;

public record CompositionManifestRequest(
        OutputPreset outputPreset,
        List<CompositionSceneRequest> scenes) {

    public CompositionManifestRequest {
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
    }
}
