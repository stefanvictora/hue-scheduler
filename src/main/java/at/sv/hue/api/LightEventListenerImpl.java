package at.sv.hue.api;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
public class LightEventListenerImpl implements LightEventListener {

    private final ManualOverrideTracker manualOverrideTracker;
    private final ConcurrentHashMap<String, List<Runnable>> onStateWaitingList;
    private final Function<String, List<String>> lightToGroupAssignmentLookup;

    public LightEventListenerImpl(ManualOverrideTracker manualOverrideTracker,
                                  Function<String, List<String>> lightToGroupAssignmentLookup) {
        this.manualOverrideTracker = manualOverrideTracker;
        this.lightToGroupAssignmentLookup = lightToGroupAssignmentLookup;
        onStateWaitingList = new ConcurrentHashMap<>();
    }

    @Override
    public void onLightOff(String id, String uuid) {
        // currently not needed
    }

    @Override
    public void onLightOn(String id, String uuid, boolean physical) {
        if (physical) { // only lights can be turned on physically
            MDC.put("context", "on-event " + id);
            // if light has been physically turned on, we additionally signal to each group the light is assigned
            lightToGroupAssignmentLookup.apply(id)
                                        .forEach(groupId -> onLightOn(groupId, null, false));
        }
        MDC.put("context", "on-event " + id);
        manualOverrideTracker.onLightTurnedOn(id);
        List<Runnable> waitingList = onStateWaitingList.remove(id);
        if (waitingList != null) {
            log.debug("Reschedule {} waiting states.", waitingList.size());
            waitingList.forEach(Runnable::run);
        }
    }

    @Override
    public void runWhenTurnedOn(String id, Runnable runnable) {
        onStateWaitingList.computeIfAbsent(id, i -> new ArrayList<>()).add(runnable);
    }
}
