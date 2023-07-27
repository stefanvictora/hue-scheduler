package at.sv.hue.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class HueEventListenerImpl implements HueEventListener {

    private final ManualOverrideTracker manualOverrideTracker;
    private final Function<Integer, List<Runnable>> waitingListProvider;

    @Override
    public void onLightOff(int lightId, String uuid) {
        // currently not needed
    }

    @Override
    public void onLightOn(int lightId, String uuid) {
        manualOverrideTracker.onLightTurnedOn(lightId);
        List<Runnable> waitingList = waitingListProvider.apply(lightId);
        if (waitingList != null) {
            log.debug("Received on-event for light {}. Reschedule {} waiting states.", lightId, waitingList.size());
            waitingList.forEach(Runnable::run);
        }
    }
}
