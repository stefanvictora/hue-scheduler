package at.sv.hue.api;

/**
 * Exception to signal a connection failure from the bridge. This causes the Scheduler to retry the call.
 */
public final class BridgeConnectionFailure extends RuntimeException {

    public BridgeConnectionFailure(String message) {
        super(message);
    }

    public BridgeConnectionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
