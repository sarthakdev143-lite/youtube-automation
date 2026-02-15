package github.sarthakdev143.media_factory.service;

import github.sarthakdev143.media_factory.model.composition.CompositionRenderPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

public interface CompositionRenderer {

    void renderComposition(CompositionRenderPlan plan, Path outputVideoPath) throws IOException, InterruptedException;

    default void renderComposition(
            CompositionRenderPlan plan,
            Path outputVideoPath,
            String jobId,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        renderComposition(plan, outputVideoPath);
    }
}
