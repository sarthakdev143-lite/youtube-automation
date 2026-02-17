package github.sarthakdev143.media_factory.service.ffmpeg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FfmpegServiceTest {

    @ParameterizedTest
    @EnumSource(FfmpegEncoderDetector.EncoderType.class)
    void buildCommandAddsVignetteFilterByDefault(FfmpegEncoderDetector.EncoderType encoderType) {
        FfmpegService ffmpegService = new FfmpegService(mock(FfmpegEncoderDetector.class));
        String defaultFilter = String.format(Locale.ROOT, "vignette=angle=%.6f", Math.PI / 5.0d);

        List<String> command = ffmpegService.buildCommand(
                Path.of("input.jpg"),
                Path.of("input.mp3"),
                Path.of("output.mp4"),
                60,
                2,
                40,
                encoderType);

        assertThat(command).containsSubsequence("-vf", defaultFilter);
    }

    @ParameterizedTest
    @CsvSource({
            "-5,vignette=angle=0.000000",
            "100,vignette=angle=1.570796"
    })
    void buildCommandClampsVignetteStrengthToSupportedRange(int requestedStrength, String expectedFilter) {
        FfmpegService ffmpegService = new FfmpegService(mock(FfmpegEncoderDetector.class));

        List<String> command = ffmpegService.buildCommand(
                Path.of("input.jpg"),
                Path.of("input.mp3"),
                Path.of("output.mp4"),
                60,
                2,
                requestedStrength,
                FfmpegEncoderDetector.EncoderType.LIBX264);

        assertThat(command).containsSubsequence("-vf", expectedFilter);
    }
}
