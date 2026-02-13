package github.sarthakdev143.media_factory.integration.youtube;

import com.google.api.services.youtube.YouTube;

public interface YouTubeServiceProvider {

    YouTube getService() throws Exception;
}
