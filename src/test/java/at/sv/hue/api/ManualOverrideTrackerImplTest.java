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
    void onLightTurnedOn_resetsManualOverrideFlag() {
        tracker.onManuallyOverridden("1");
        tracker.onManuallyOverridden("2");

        tracker.onLightTurnedOn("1");

        assertIsManuallyOverridden("1", false);
        assertIsManuallyOverridden("2", true); // light 2 remains unaffected
    }

    @Test
    void wasTurnedOnBySyncedScene_returnsCorrectResult() {
        tracker.onLightTurnedOnBySyncedScene("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isTrue();
    }

    @Test
    void wasTurnedOnBySyncedScene_resetOnLightOff() {
        tracker.onLightTurnedOnBySyncedScene("1");

        tracker.onLightOff("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isFalse();
    }

    @Test
    void wasTurnedOnBySyncedScene_resetOnManualOverridden() {
        tracker.onLightTurnedOnBySyncedScene("1");

        tracker.onManuallyOverridden("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isFalse();
    }

    @Test
    void wasTurnedOnBySyncedScene_multipleLights_returnsCorrectResult() {
        tracker.onLightTurnedOnBySyncedScene("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isTrue();
        assertThat(tracker.wasTurnedOnBySyncedScene("2")).isFalse();
    }

    @Test
    void wasTurnedOnBySyncedScene_onLightNormallyTurnedOn_doesNotSetSyncedScene() {
        tracker.onLightTurnedOn("1");

        assertThat(tracker.wasTurnedOnBySyncedScene("1")).isFalse();
    }

    private void assertIsManuallyOverridden(String lightId, boolean expected) {
        assertThat(tracker.isManuallyOverridden(lightId)).isEqualTo(expected);
    }
}
