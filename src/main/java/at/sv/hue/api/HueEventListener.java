package at.sv.hue.api;

public interface HueEventListener {
    void onLightOff(String idv1, String uuid);

    void onLightOn(String idv1, String uuid);
}
