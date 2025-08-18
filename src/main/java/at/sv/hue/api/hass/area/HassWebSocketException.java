package at.sv.hue.api.hass.area;

public class HassWebSocketException extends RuntimeException {
    public HassWebSocketException(String message) {
        super(message);
    }

    public HassWebSocketException(String message, Throwable cause) {
        super(message, cause);
    }
}
