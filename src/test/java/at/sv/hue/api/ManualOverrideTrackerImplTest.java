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
        assertIsManuallyOverridden("1", false);

        tracker.onManuallyOverridden("1");

        assertIsManuallyOverridden("1", true);
        assertIsManuallyOverridden("2", false);
    }

    @Test
    void isManuallyOverridden_resetByAutomaticallyAssignment() {
        tracker.onManuallyOverridden("1");
        tracker.onManuallyOverridden("2");

        tracker.onAutomaticallyAssigned("1");

        assertIsManuallyOverridden("1", false);
        assertIsManuallyOverridden("2", true);
    }

    @Test
    void wasJustTurnedOn_onlyActiveAfterUserTTurnsOnLights_eventAlsoResetsManualOverrideFlag() {
        assertWasJustTurnedOn("1", false);
        assertWasJustTurnedOn("2", false);

        tracker.onManuallyOverridden("1");
        tracker.onManuallyOverridden("2");

        assertWasJustTurnedOn("1", false);
        assertWasJustTurnedOn("2", false);

        tracker.onLightTurnedOn("1");

        assertIsManuallyOverridden("1", false);
        assertIsManuallyOverridden("2", true); // light 2 remains unaffected
        assertWasJustTurnedOn("1", true);
        assertWasJustTurnedOn("2", false); // light 2 remains unaffected
    }

    @Test
    void wasJustTurnedOn_resetAfterAutomaticallyAssigned() {
        tracker.onManuallyOverridden("1");
        tracker.onLightTurnedOn("1");

        tracker.onAutomaticallyAssigned("1");

        assertWasJustTurnedOn("1", false);
    }

    @Test
    void wasJustTurnedOn_resetAfterManuallyOverridden() {
        tracker.onLightTurnedOn("1");

        tracker.onManuallyOverridden("1");

        assertWasJustTurnedOn("1", false);
    }

    @Test
    void wasActivatedBySyncedScene_returnsCorrectResult() {
        tracker.onLightTurnedOnBySyncedScene("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isTrue();
    }

    @Test
    void wasActivatedBySyncedScene_resetOnLightOff() {
        tracker.onLightTurnedOnBySyncedScene("1");

        tracker.onLightOff("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isFalse();
    }

    @Test
    void wasActivatedBySyncedScene_resetOnManualOverridden() {
        tracker.onLightTurnedOnBySyncedScene("1");

        tracker.onManuallyOverridden("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isFalse();
    }

    private void assertWasJustTurnedOn(String lightId, boolean expected) {
        assertThat(tracker.wasJustTurnedOn(lightId)).isEqualTo(expected);
    }

    private void assertIsManuallyOverridden(String lightId, boolean expected) {
        assertThat(tracker.isManuallyOverridden(lightId)).isEqualTo(expected);
    }
}
