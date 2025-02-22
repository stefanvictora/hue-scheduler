package at.sv.hue.api;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
public class LightEventListenerImpl implements LightEventListener {

    private final ManualOverrideTracker manualOverrideTracker;
    private final ConcurrentHashMap<String, List<Runnable>> onStateWaitingList;
    private final Function<String, List<String>> affectedIdsByDeviceLookup;
    private final Predicate<String> wasRecentlyAffectedBySyncedScene;

    public LightEventListenerImpl(ManualOverrideTracker manualOverrideTracker,
                                  Function<String, List<String>> affectedIdsByDeviceLookup,
                                  Predicate<String> wasRecentlyAffectedBySyncedScene) {
        this.manualOverrideTracker = manualOverrideTracker;
        this.affectedIdsByDeviceLookup = affectedIdsByDeviceLookup;
        this.wasRecentlyAffectedBySyncedScene = wasRecentlyAffectedBySyncedScene;
        onStateWaitingList = new ConcurrentHashMap<>();
    }

    @Override
    public void onLightOff(String id) {
        // currently not needed
    }

    @Override
    public void onLightOn(String id) {
        MDC.put("context", "on-event " + id);
        manualOverrideTracker.onLightTurnedOn(id);
        if (wasRecentlyAffectedBySyncedScene.test(id)) {
            MDC.put("context", "on-event (synced) " + id);
            manualOverrideTracker.onLightTurnedOnBySyncedScene(id);
        } else {
            manualOverrideTracker.onLightTurnedOnManually(id);
        }
        rescheduleWaitingStates(id);
    }

    private void rescheduleWaitingStates(String id) {
        List<Runnable> waitingList = onStateWaitingList.remove(id);
        if (waitingList != null) {
            log.debug("Reschedule {} waiting states.", waitingList.size());
            waitingList.forEach(Runnable::run);
        }
    }

    @Override
    public void onPhysicalOn(String deviceId) {
        MDC.put("context", "on-event (physical) " + deviceId);
        affectedIdsByDeviceLookup.apply(deviceId)
                                 .forEach(id -> {
                                     MDC.put("context", "on-event (physical) " + id);
                                     manualOverrideTracker.onLightTurnedOn(id);
                                     rescheduleWaitingStates(id);
                                 });
    }

    @Override
    public void runWhenTurnedOn(String id, Runnable runnable) {
        onStateWaitingList.computeIfAbsent(id, i -> new ArrayList<>()).add(runnable);
    }
}
