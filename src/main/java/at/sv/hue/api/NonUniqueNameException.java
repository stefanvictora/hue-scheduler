package at.sv.hue.api;

public class NonUniqueNameException extends RuntimeException {
    public NonUniqueNameException(String message) {
        super(message);
    }
}
