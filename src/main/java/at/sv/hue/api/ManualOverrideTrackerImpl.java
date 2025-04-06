package at.sv.hue.api;

import com.github.benmanes.caffeine.cache.Ticker;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ManualOverrideTrackerImpl implements ManualOverrideTracker {

    private static final TrackedState DEFAULT_STATE = new TrackedState();

    private final ConcurrentHashMap<String, TrackedState> trackedStatesPerId;
    private final Map<String, Long> lastTurnedOnTimePerId;
    private final long justTurnedOnWindowDuration;
    private final Ticker ticker;

    public ManualOverrideTrackerImpl(Ticker ticker, int justTurnedOnWindowInSeconds) {
        this.ticker = ticker;
        justTurnedOnWindowDuration = justTurnedOnWindowInSeconds * 1_000_000_000L;
        trackedStatesPerId = new ConcurrentHashMap<>();
        lastTurnedOnTimePerId = new ConcurrentHashMap<>();
    }

    @Override
    public void onManuallyOverridden(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(true);
        resetJustTurnedOn(id);
        trackedState.setTurnedOnBySyncedScene(false);
    }

    @Override
    public boolean isManuallyOverridden(String id) {
        return getOrDefaultState(id).isManuallyOverridden();
    }

    @Override
    public void onLightTurnedOn(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(false);
        trackedState.setLightIsOff(false);
        lastTurnedOnTimePerId.put(id, ticker.read());
    }

    @Override
    public void onLightTurnedOnBySyncedScene(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setTurnedOnBySyncedScene(true);
    }

    @Override
    public void onLightOff(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setLightIsOff(true);
        trackedState.setTurnedOnBySyncedScene(false);
        resetJustTurnedOn(id);
    }

    @Override
    public boolean isOff(String id) {
        return getOrDefaultState(id).isLightIsOff();
    }

    @Override
    public boolean wasJustTurnedOn(String id) {
        Long last = lastTurnedOnTimePerId.get(id);
        return last != null && ticker.read() - last < justTurnedOnWindowDuration;
    }

    @Override
    public boolean wasTurnedOnBySyncedScene(String id) {
        return getOrDefaultState(id).isTurnedOnBySyncedScene();
    }

    @Override
    public void onAutomaticallyAssigned(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(false);  // maybe not needed, as this flag is overridden also on light-on events
        resetJustTurnedOn(id);
    }

    private TrackedState getOrCreateTrackedState(String id) {
        return trackedStatesPerId.computeIfAbsent(id, i -> new TrackedState());
    }

    private TrackedState getOrDefaultState(String id) {
        return trackedStatesPerId.getOrDefault(id, DEFAULT_STATE);
    }

    private void resetJustTurnedOn(String id) {
        lastTurnedOnTimePerId.remove(id);
    }

    @Getter
    @Setter
    private static final class TrackedState {
        private boolean manuallyOverridden;
        private boolean turnedOnBySyncedScene;
        private boolean lightIsOff;
    }
}
