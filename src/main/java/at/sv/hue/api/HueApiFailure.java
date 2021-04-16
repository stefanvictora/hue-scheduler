package at.sv.hue.api;

public class HueApiFailure extends RuntimeException {
    public HueApiFailure(String message) {
        super(message);
    }
}
