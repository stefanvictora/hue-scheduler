package at.sv.hue.api;

public final class BridgeAuthenticationFailure extends HueApiFailure {
    public BridgeAuthenticationFailure() {
        super("Username was rejected by bridge");
    }
}
