package at.sv.hue.api;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
public class LightEventListenerImpl implements LightEventListener {

    private final ManualOverrideTracker manualOverrideTracker;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> powerTransitionWaitingList;
    private final Function<String, List<String>> affectedIdsByDeviceLookup;
    private final Predicate<String> wasRecentlyAffectedBySyncedScene;
    private final Consumer<String> allowFastSceneUpdate;
    private final boolean supportsOffLightUpdates;
    private final Set<String> schedulerInitiatedOff = ConcurrentHashMap.newKeySet();

    public LightEventListenerImpl(ManualOverrideTracker manualOverrideTracker,
                                  Function<String, List<String>> affectedIdsByDeviceLookup,
                                  Predicate<String> wasRecentlyAffectedBySyncedScene,
                                  Consumer<String> allowFastSceneUpdate, boolean supportsOffLightUpdates) {
        this.manualOverrideTracker = manualOverrideTracker;
        this.affectedIdsByDeviceLookup = affectedIdsByDeviceLookup;
        this.wasRecentlyAffectedBySyncedScene = wasRecentlyAffectedBySyncedScene;
        this.allowFastSceneUpdate = allowFastSceneUpdate;
        this.supportsOffLightUpdates = supportsOffLightUpdates;
        powerTransitionWaitingList = new ConcurrentHashMap<>();
    }

    @Override
    public void onLightOff(String id) {
        manualOverrideTracker.onLightOff(id);
        if (supportsOffLightUpdates) {
            MDC.put("context", "off-event " + id);
            if (schedulerInitiatedOff.remove(id)) {
                log.trace("Scheduler initiated off: Skip rescheduling waiting states.");
            } else {
                rescheduleWaitingStates(id);
            }
            MDC.remove("context");
        }
    }

    @Override
    public void markSchedulerInitiatedOff(String id) {
        schedulerInitiatedOff.add(id);
    }

    @Override
    public void onLightOn(String id) {
        MDC.put("context", "on-event " + id);
        manualOverrideTracker.onLightTurnedOn(id);
        if (wasRecentlyAffectedBySyncedScene.test(id)) {
            MDC.put("context", "on-event (synced) " + id);
            manualOverrideTracker.onLightTurnedOnBySyncedScene(id);
        }
        allowFastSceneUpdate.accept(id);
        rescheduleWaitingStates(id);
        MDC.remove("context");
    }

    private void rescheduleWaitingStates(String id) {
        List<Runnable> waitingList = powerTransitionWaitingList.remove(id);
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
                                     allowFastSceneUpdate.accept(id);
                                     rescheduleWaitingStates(id);
                                 });
        MDC.remove("context");
    }

    @Override
    public void runOnPowerTransition(String id, Runnable runnable) {
        powerTransitionWaitingList.computeIfAbsent(id, i -> new CopyOnWriteArrayList<>()).add(runnable);
    }
}
