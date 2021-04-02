package at.sv.hue.api;

public final class LightNotFoundException extends RuntimeException {
    public LightNotFoundException(String message) {
        super(message);
    }
}
