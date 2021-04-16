package at.sv.hue.api;

public final class BridgeConnectionFailure extends RuntimeException {

    public BridgeConnectionFailure(String message) {
        super(message);
    }

    public BridgeConnectionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
