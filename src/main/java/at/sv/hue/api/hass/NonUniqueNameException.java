package at.sv.hue.api.hass;

public class NonUniqueNameException extends RuntimeException {
    public NonUniqueNameException(String message) {
        super(message);
    }
}
