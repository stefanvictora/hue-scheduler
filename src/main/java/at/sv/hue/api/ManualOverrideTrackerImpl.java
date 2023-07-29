package at.sv.hue.api;

import lombok.Data;

import java.util.HashMap;

public class ManualOverrideTrackerImpl implements ManualOverrideTracker {

    private static final TrackedState DEFAULT_STATE = new TrackedState();

    private final HashMap<Long, TrackedState> trackedStatesPerLight;

    public ManualOverrideTrackerImpl() {
        trackedStatesPerLight = new HashMap<>();
    }

    @Override
    public void onManuallyOverridden(long lightId) {
        getOrCreateTrackedState(lightId).setManuallyOverridden(true);
    }

    @Override
    public boolean isManuallyOverridden(long lightId) {
        return getOrDefaultState(lightId).isManuallyOverridden();
    }

    @Override
    public void onLightTurnedOn(long lightId) {
        TrackedState trackedState = getOrCreateTrackedState(lightId);
        trackedState.setManuallyOverridden(false);
        trackedState.setEnforceSchedule(true);
    }

    @Override
    public boolean shouldEnforceSchedule(long lightId) {
        return getOrDefaultState(lightId).isEnforceSchedule();
    }

    @Override
    public void onAutomaticallyAssigned(long lightId) {
        TrackedState trackedState = getOrCreateTrackedState(lightId);
        trackedState.setManuallyOverridden(false);  // maybe not needed, as this flag is overridden also on light-on events
        trackedState.setEnforceSchedule(false);
    }

    private TrackedState getOrCreateTrackedState(long lightId) {
        return trackedStatesPerLight.computeIfAbsent(lightId, id -> new TrackedState());
    }

    private TrackedState getOrDefaultState(long lightId) {
        return trackedStatesPerLight.getOrDefault(lightId, DEFAULT_STATE);
    }

    @Data
    private static final class TrackedState {
        private boolean manuallyOverridden;
        private boolean enforceSchedule;
    }
}
