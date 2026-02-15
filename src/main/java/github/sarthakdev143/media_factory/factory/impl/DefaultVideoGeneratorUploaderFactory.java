package github.sarthakdev143.media_factory.factory.impl;

import com.google.api.services.youtube.YouTube;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.FfmpegCommandBuilder;
import github.sarthakdev143.media_factory.integration.video.FfmpegProcessRunner;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import org.springframework.stereotype.Component;

@Component
public class DefaultVideoGeneratorUploaderFactory implements VideoGeneratorUploaderFactory {

    private final FfmpegCommandBuilder ffmpegCommandBuilder;
    private final FfmpegProcessRunner ffmpegProcessRunner;

    public DefaultVideoGeneratorUploaderFactory(
            FfmpegCommandBuilder ffmpegCommandBuilder,
            FfmpegProcessRunner ffmpegProcessRunner) {
        this.ffmpegCommandBuilder = ffmpegCommandBuilder;
        this.ffmpegProcessRunner = ffmpegProcessRunner;
    }

    @Override
    public VideoGeneratorUploader create(YouTube youTubeService) {
        return new VideoGeneratorUploader(youTubeService, ffmpegCommandBuilder, ffmpegProcessRunner);
    }
}
