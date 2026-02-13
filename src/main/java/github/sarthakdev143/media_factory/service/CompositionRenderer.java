package github.sarthakdev143.media_factory.service;

import github.sarthakdev143.media_factory.model.composition.CompositionRenderPlan;

import java.io.IOException;
import java.nio.file.Path;

public interface CompositionRenderer {

    void renderComposition(CompositionRenderPlan plan, Path outputVideoPath) throws IOException, InterruptedException;
}
