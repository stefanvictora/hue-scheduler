package at.sv.hue.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class HueEventListenerImpl implements HueEventListener {

    private final ManualOverrideTracker manualOverrideTracker;
    private final Function<String, List<Runnable>> waitingListProvider;
    private final Function<String, List<String>> lightToGroupAssignmentLookup;

    @Override
    public void onLightOff(String idv1, String uuid) {
        // currently not needed
    }

    @Override
    public void onLightOn(String idv1, String uuid, boolean physical) {
        MDC.put("context", "on-event " + idv1);
        if (physical) { // only lights can be turned on physically
            // if light has been physically turned on, we additionally signal to each group the light is assigned
            lightToGroupAssignmentLookup.apply(idv1)
                                        .forEach(groupId -> onLightOn(groupId, null, false));
        }
        manualOverrideTracker.onLightTurnedOn(idv1);
        List<Runnable> waitingList = waitingListProvider.apply(idv1);
        if (waitingList != null) {
            log.debug("Reschedule {} waiting states.", waitingList.size());
            waitingList.forEach(Runnable::run);
        }
    }
}
