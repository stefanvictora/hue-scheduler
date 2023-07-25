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
        log.trace("Received off-event for light {}", lightId);
        manualOverrideTracker.onLightTurnedOff(lightId);
    }

    @Override
    public void onLightOn(int lightId, String uuid) {
        log.trace("Received on-event for light {}", lightId);
        List<Runnable> waitingList = waitingListProvider.apply(lightId);
        if (waitingList != null) {
            log.trace("Reschedule {} waiting states for light {}", waitingList.size(), lightId);
            waitingList.forEach(Runnable::run);
        }
    }
}
