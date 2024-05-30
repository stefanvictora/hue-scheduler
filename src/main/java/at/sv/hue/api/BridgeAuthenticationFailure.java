package at.sv.hue.api;

public final class BridgeAuthenticationFailure extends ApiFailure {
    public BridgeAuthenticationFailure() {
        super("Username / token was rejected by bridge");
    }
}
