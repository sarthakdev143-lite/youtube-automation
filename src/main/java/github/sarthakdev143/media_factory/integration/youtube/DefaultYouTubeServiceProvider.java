package github.sarthakdev143.media_factory.integration.youtube;

import com.google.api.services.youtube.YouTube;
import org.springframework.stereotype.Component;

@Component
public class DefaultYouTubeServiceProvider implements YouTubeServiceProvider {

    @Override
    public YouTube getService() throws Exception {
        return YouTubeServiceFactory.getService();
    }
}
