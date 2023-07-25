package at.sv.hue.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ManualOverrideTrackerImplTest {

    private ManualOverrideTrackerImpl tracker;

    @BeforeEach
    void setUp() {
        tracker = new ManualOverrideTrackerImpl();
    }

    @Test
    void isManuallyOverridden_multipleLights_returnsCorrectResult() {
        assertIsManuallyOverridden(1, false);

        tracker.onManuallyOverridden(1);

        assertIsManuallyOverridden(1, true);
        assertIsManuallyOverridden(2, false);
    }

    @Test
    void isManuallyOverridden_resetByAutomaticallyAssignment() {
        tracker.onManuallyOverridden(1);
        tracker.onManuallyOverridden(2);

        tracker.onAutomaticallyAssigned(1);

        assertIsManuallyOverridden(1, false);
        assertIsManuallyOverridden(2, true);
    }

    @Test
    void shouldEnforceSchedule_onlyActiveAfterUserTTurnsOnLights_eventAlsoResetsManualOverrideFlag() {
        assertShouldEnforceSchedule(1, false);
        assertShouldEnforceSchedule(2, false);

        tracker.onManuallyOverridden(1);
        tracker.onManuallyOverridden(2);

        assertShouldEnforceSchedule(1, false);
        assertShouldEnforceSchedule(2, false);

        tracker.onLightTurnedOn(1);

        assertIsManuallyOverridden(1, false);
        assertIsManuallyOverridden(2, true); // light 2 remains unaffected
        assertShouldEnforceSchedule(1, true);
        assertShouldEnforceSchedule(2, false); // light 2 remains unaffected
    }

    @Test
    void shouldEnforceSchedule_resetAfterAutomaticallyAssigned() {
        tracker.onManuallyOverridden(1);
        tracker.onLightTurnedOn(1);

        tracker.onAutomaticallyAssigned(1);

        assertShouldEnforceSchedule(1, false);
    }

    private void assertShouldEnforceSchedule(int lightId, boolean expected) {
        assertThat(tracker.shouldEnforceSchedule(lightId)).isEqualTo(expected);
    }

    private void assertIsManuallyOverridden(int lightId, boolean expected) {
        assertThat(tracker.isManuallyOverridden(lightId)).isEqualTo(expected);
    }
}
