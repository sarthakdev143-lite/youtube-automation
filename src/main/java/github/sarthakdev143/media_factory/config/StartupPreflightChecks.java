package github.sarthakdev143.media_factory.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "media-factory.preflight.enabled", havingValue = "true", matchIfMissing = true)
public class StartupPreflightChecks implements ApplicationRunner {

    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";
    private static final String CREDENTIALS_PATH_ENV = "YOUTUBE_CREDENTIALS_PATH";
    private static final Path DEFAULT_CREDENTIALS_PATH = Path.of("secrets/credentials.json");
    private static final int FFMPEG_CHECK_TIMEOUT_SECONDS = 10;

    @Override
    public void run(ApplicationArguments args) {
        checkFfmpegConfiguration();
        checkCredentialsConfiguration();
    }

    private void checkFfmpegConfiguration() {
        String configuredPath = System.getenv(FFMPEG_PATH_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path ffmpegPath = Path.of(configuredPath);
            if (!Files.isRegularFile(ffmpegPath)) {
                throw new IllegalStateException(
                        "FFmpeg binary not found at " + ffmpegPath.toAbsolutePath()
                                + ". Set " + FFMPEG_PATH_ENV + " to a valid ffmpeg executable path.");
            }
            return;
        }

        try {
            Process process = new ProcessBuilder(DEFAULT_FFMPEG_BINARY, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(FFMPEG_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                throw new IllegalStateException(
                        "FFmpeg is not available on PATH. Install FFmpeg or set " + FFMPEG_PATH_ENV + ".");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "FFmpeg is not available on PATH. Install FFmpeg or set " + FFMPEG_PATH_ENV + ".",
                    e);
        }
    }

    private void checkCredentialsConfiguration() {
        String configuredPath = System.getenv(CREDENTIALS_PATH_ENV);
        Path credentialsPath = (configuredPath == null || configuredPath.isBlank())
                ? DEFAULT_CREDENTIALS_PATH
                : Path.of(configuredPath);

        if (!Files.isRegularFile(credentialsPath)) {
            throw new IllegalStateException(
                    "YouTube credentials file not found at " + credentialsPath.toAbsolutePath()
                            + ". Set " + CREDENTIALS_PATH_ENV + " or place credentials.json in the app working directory.");
        }

        if (!Files.isReadable(credentialsPath)) {
            throw new IllegalStateException(
                    "YouTube credentials file is not readable at " + credentialsPath.toAbsolutePath() + ".");
        }
    }
}
