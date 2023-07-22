package at.sv.hue.api;

public interface HueEventListener {
    void onLightOff(int lightId, String uuid);

    void onLightOn(int lightId, String uuid);
}
