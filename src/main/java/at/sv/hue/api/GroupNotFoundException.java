package at.sv.hue.api;

public final class GroupNotFoundException extends ResourceNotFoundException {
    public GroupNotFoundException(String message) {
        super(message);
    }
}
