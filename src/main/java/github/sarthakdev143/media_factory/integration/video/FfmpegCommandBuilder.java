package github.sarthakdev143.media_factory.integration.video;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FfmpegCommandBuilder {

    private final FfmpegCapabilities capabilities;

    public FfmpegCommandBuilder(FfmpegCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public String ffmpegBinary() {
        return capabilities.ffmpegBinary();
    }

    public boolean nvencAvailable() {
        return capabilities.nvencAvailable();
    }

    public List<String> buildBasicImageAudioCommand(
            String imagePath,
            String audioPath,
            int durationSeconds,
            String outputPath,
            boolean useNvenc) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegBinary());
        command.add("-y");
        command.add("-stream_loop");
        command.add("-1");
        command.add("-i");
        command.add(imagePath);
        command.add("-stream_loop");
        command.add("-1");
        command.add("-i");
        command.add(audioPath);
        command.add("-t");
        command.add(String.valueOf(durationSeconds));
        command.add("-shortest");
        command.add("-r");
        command.add("30");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        appendVideoEncoding(command, useNvenc);
        command.add(outputPath);
        return command;
    }

    public void appendVideoEncoding(List<String> command, boolean useNvenc) {
        command.add("-c:v");
        command.add(useNvenc ? "h264_nvenc" : "libx264");
        command.add("-preset");
        command.add(useNvenc ? "p4" : "veryfast");
        if (useNvenc) {
            command.add("-tune");
            command.add("hq");
            command.add("-cq");
            command.add("23");
            command.add("-b:v");
            command.add("0");
        } else {
            command.add("-crf");
            command.add("23");
        }
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-movflags");
        command.add("+faststart");
    }
}
