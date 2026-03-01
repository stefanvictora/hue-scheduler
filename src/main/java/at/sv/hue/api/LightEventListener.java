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

    /**
     * Marks a light as being turned off by the scheduler itself (not by a user).
     * The next off-event for this light will skip rescheduling power-transition waiting states,
     * since the off was expected and already handled.
     *
     * @param id the light id that the scheduler is about to turn off
     */
    void markSchedulerInitiatedOff(String id);
}
