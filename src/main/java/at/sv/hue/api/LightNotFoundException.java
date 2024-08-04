package at.sv.hue.api;

public final class LightNotFoundException extends ResourceNotFoundException {
    public LightNotFoundException(String message) {
        super(message);
    }
}
