package at.sv.hue;

public final class InvalidColorTemperatureValue extends RuntimeException {
    public InvalidColorTemperatureValue(String message) {
        super(message);
    }
}
