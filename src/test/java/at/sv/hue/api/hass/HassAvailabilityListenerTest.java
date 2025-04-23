package at.sv.hue.api.hass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HassAvailabilityListenerTest {

    private Runnable onRestartedCallback;
    private HassAvailabilityListener listener;

    @BeforeEach
    void setUp() {
        onRestartedCallback = mock(Runnable.class);
        listener = new HassAvailabilityListener(onRestartedCallback);
    }

    @Test
    void onStarted_calledTwoTimes_onlyTriggersRestartedCallbackSecondTime() {
        onStarted();

        assertThat(listener.isFullyStarted()).isTrue();
        verifyNoInteractions(onRestartedCallback);

        onStarted();

        verify(onRestartedCallback, times(1)).run();

        onStarted();

        verify(onRestartedCallback, times(2)).run();
    }

    @Test
    void performInitialCheck_conditionIsFalse_doesNotSetFullyStarted_secondCallIgnored() {
        listener.performInitialCheck(() -> false);

        assertThat(listener.isFullyStarted()).isFalse();

        listener.performInitialCheck(() -> true);

        assertThat(listener.isFullyStarted()).isFalse(); // still false
    }

    @Test
    void performInitialCheck_conditionIsTrue_setsFullyStartedFlag_secondCallIgnored() {
        listener.performInitialCheck(() -> true);

        assertThat(listener.isFullyStarted()).isTrue();

        listener.performInitialCheck(() -> false);

        assertThat(listener.isFullyStarted()).isTrue(); // still true
    }

    private void onStarted() {
        listener.onStarted();
    }

}
