package at.sv.hue.api.hass;

public class UnsupportedStateException extends RuntimeException {
    public UnsupportedStateException(String message) {
        super(message);
    }
}
