package at.sv.hue.api;

public interface LightEventListener {
    void onLightOff(String id);

    void onLightOn(String id, boolean physical);

    void runWhenTurnedOn(String id, Runnable runnable);
}
