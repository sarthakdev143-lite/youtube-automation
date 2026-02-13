package github.sarthakdev143.media_factory.factory;

import com.google.api.services.youtube.YouTube;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;

public interface VideoGeneratorUploaderFactory {

    VideoGeneratorUploader create(YouTube youTubeService);
}
