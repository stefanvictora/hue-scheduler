package at.sv.hue.api;

public interface LightEventListener {
    void onLightOff(String id);

    void onLightOn(String id);

    /**
     * Called when a physical device is turned on. In such cases we not only signal a lightOn for the contained light
     * but also for all groups the device is assigned to.
     *
     * @param deviceId the id of the physical device
     */
    void onPhysicalOn(String deviceId);

    void runOnPowerTransition(String id, Runnable runnable);
}
