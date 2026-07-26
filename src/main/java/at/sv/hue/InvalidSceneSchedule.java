package at.sv.hue;

public final class InvalidSceneSchedule extends RuntimeException {

    public InvalidSceneSchedule(String message) {
        super(message);
    }

    public InvalidSceneSchedule(String message, Throwable cause) {
        super(message, cause);
    }
}
