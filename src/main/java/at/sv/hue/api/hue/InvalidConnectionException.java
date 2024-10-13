package at.sv.hue.api.hue;

public class InvalidConnectionException extends RuntimeException {
    public InvalidConnectionException(String message) {
        super(message);
    }
}
