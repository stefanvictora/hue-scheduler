package at.sv.hue.api;

public class ResourceNotFoundException extends ApiFailure {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
