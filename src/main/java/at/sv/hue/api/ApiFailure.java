package at.sv.hue.api;

public class ApiFailure extends RuntimeException {
    public ApiFailure(String message) {
        super(message);
    }
}
