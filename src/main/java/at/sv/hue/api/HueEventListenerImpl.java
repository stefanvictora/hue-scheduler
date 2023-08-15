package at.sv.hue.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class HueEventListenerImpl implements HueEventListener {

    private final ManualOverrideTracker manualOverrideTracker;
    private final Function<String, List<Runnable>> waitingListProvider;

    @Override
    public void onLightOff(String idv1, String uuid) {
        // currently not needed
    }

    @Override
    public void onLightOn(String idv1, String uuid) {
        manualOverrideTracker.onLightTurnedOn(idv1);
        List<Runnable> waitingList = waitingListProvider.apply(idv1);
        if (waitingList != null) {
            log.debug("Received on-event for {}. Reschedule {} waiting states.", idv1, waitingList.size());
            waitingList.forEach(Runnable::run);
        }
    }
}
