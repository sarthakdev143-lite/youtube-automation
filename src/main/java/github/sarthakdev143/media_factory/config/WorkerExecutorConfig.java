package github.sarthakdev143.media_factory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WorkerExecutorConfig {

    @Bean(name = "videoWorkerExecutor", destroyMethod = "shutdown")
    public ExecutorService videoWorkerExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread workerThread = new Thread(r);
            workerThread.setName("media-factory-video-worker");
            workerThread.setDaemon(false);
            return workerThread;
        });
    }
}
