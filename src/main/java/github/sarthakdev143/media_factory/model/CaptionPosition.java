package github.sarthakdev143.media_factory.model;

public enum CaptionPosition {
    TOP,
    CENTER,
    BOTTOM;

    public String yExpression() {
        return switch (this) {
            case TOP -> "h*0.08";
            case CENTER -> "(h-text_h)/2";
            case BOTTOM -> "h-text_h-h*0.08";
        };
    }
}
