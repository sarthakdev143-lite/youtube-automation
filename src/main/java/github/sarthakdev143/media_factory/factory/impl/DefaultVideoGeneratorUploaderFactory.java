package github.sarthakdev143.media_factory.factory.impl;

import com.google.api.services.youtube.YouTube;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import org.springframework.stereotype.Component;

@Component
public class DefaultVideoGeneratorUploaderFactory implements VideoGeneratorUploaderFactory {

    @Override
    public VideoGeneratorUploader create(YouTube youTubeService) {
        return new VideoGeneratorUploader(youTubeService);
    }
}
