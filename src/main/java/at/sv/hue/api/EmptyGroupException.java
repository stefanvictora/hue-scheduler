package at.sv.hue.api;

public final class EmptyGroupException extends ApiFailure {
    public EmptyGroupException(String message) {
        super(message);
    }
}
