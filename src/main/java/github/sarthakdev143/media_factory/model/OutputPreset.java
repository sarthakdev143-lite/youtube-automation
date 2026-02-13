package github.sarthakdev143.media_factory.model;

public enum OutputPreset {
    LANDSCAPE_16_9(1920, 1080),
    PORTRAIT_9_16(1080, 1920),
    SQUARE_1_1(1080, 1080);

    private final int width;
    private final int height;

    OutputPreset(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
