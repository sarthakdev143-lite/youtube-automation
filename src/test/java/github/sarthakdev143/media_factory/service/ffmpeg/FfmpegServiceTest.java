package github.sarthakdev143.media_factory.service.ffmpeg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FfmpegServiceTest {

    @ParameterizedTest
    @EnumSource(FfmpegEncoderDetector.EncoderType.class)
    void buildCommandAddsVignetteFilterByDefault(FfmpegEncoderDetector.EncoderType encoderType) {
        FfmpegService ffmpegService = new FfmpegService(mock(FfmpegEncoderDetector.class));

        List<String> command = ffmpegService.buildCommand(
                Path.of("input.jpg"),
                Path.of("input.mp3"),
                Path.of("output.mp4"),
                60,
                2,
                encoderType);

        assertThat(command).containsSubsequence("-vf", "vignette");
    }
}
