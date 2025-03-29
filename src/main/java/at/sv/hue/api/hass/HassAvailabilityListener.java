package at.sv.hue.api.hass;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Slf4j
public class HassAvailabilityListener implements HassAvailabilityEventListener {

    @Getter
    private volatile boolean fullyStarted = false;
    private final AtomicBoolean initialCheckPerformed = new AtomicBoolean(false);
    private final Runnable onStartedCallback;

    public HassAvailabilityListener(Runnable onStartedCallback) {
        this.onStartedCallback = onStartedCallback;
    }

    @Override
    public void onStarted() {
        log.info("HA started.");
        fullyStarted = true;
        onStartedCallback.run();
    }

    public void performInitialCheck(Supplier<Boolean> checkFunction) {
        if (initialCheckPerformed.compareAndSet(false, true)) {
            if (checkFunction.get()) {
                fullyStarted = true;
                log.info("HA already available.");
            }
        }
    }
}
