package at.sv.hue.api;

public interface ManualOverrideTracker {

    void onManuallyOverridden(long lightId);

    boolean isManuallyOverridden(long lightId);

    void onLightTurnedOn(long lightId);

    boolean shouldEnforceSchedule(long lightId);

    void onAutomaticallyAssigned(long lightId);
}
