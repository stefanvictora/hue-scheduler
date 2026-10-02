package at.sv.hue.api.hue;

import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.PutCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class HueApiFastSceneUpdateTest {
    private static final int REGULAR_DELAY_MS = 20_000;
    private static final int FAST_DELAY_MS = 2_000;

    private final HttpResourceProvider resources = mock(HttpResourceProvider.class);
    private final LongConsumer sleep = mock(LongConsumer.class);
    private final AtomicLong elapsedNanos = new AtomicLong();
    private HueApiImpl api;

    @BeforeEach
    void setUp() throws Exception {
        api = new HueApiImpl(resources, "localhost", _ -> {}, 5, "HueTemp", "hue_sch:temp",
                REGULAR_DELAY_MS, FAST_DELAY_MS, elapsedNanos::get, sleep);
        mockGroupWithOneLightOff();
        mockSourceAndTemporaryScenes();
    }

    @Test
    void regularUpdateWaitsBeforeRecall() throws Exception {
        applyBrightness(100);

        assertSceneUpdatedThenRecalled("TEMP", REGULAR_DELAY_MS);
    }

    @Test
    void fastWindowCoversConsecutiveInterpolationUpdatesWithOffLights() {
        api.allowFastSceneUpdate("GROUP");

        applyBrightness(100);
        applyBrightness(150);

        assertSleepDelays(FAST_DELAY_MS, FAST_DELAY_MS);
    }

    @Test
    void unchangedTemporarySceneDoesNotConsumeFastWindow() {
        applyBrightness(100);
        clearInvocations(sleep);
        api.allowFastSceneUpdate("GROUP");

        applyBrightness(100);
        assertSleepDelays();
        applyBrightness(150);

        assertSleepDelays(FAST_DELAY_MS);
    }

    @Test
    void directRecallDoesNotSleepOrConsumeFastWindow() {
        api.allowFastSceneUpdate("GROUP");

        api.putSceneState("GROUP", "SOURCE", List.of(
                PutCall.builder().id("A").bri(254).build(),
                PutCall.builder().id("B").bri(127).build()));
        assertSleepDelays();
        applyBrightness(100);

        assertSleepDelays(FAST_DELAY_MS);
    }

    @Test
    void fastWindowExpiresAfterThirtySecondsEvenWhenUsed() {
        api.allowFastSceneUpdate("GROUP");
        applyBrightness(100);
        setElapsedSeconds(29);
        applyBrightness(150);
        setElapsedSeconds(30);
        applyBrightness(200);

        assertSleepDelays(FAST_DELAY_MS, FAST_DELAY_MS, REGULAR_DELAY_MS);
    }

    @Test
    void anotherInteractiveEventRestartsFastWindow() {
        api.allowFastSceneUpdate("GROUP");
        setElapsedSeconds(29);
        api.allowFastSceneUpdate("GROUP");
        setElapsedSeconds(31);
        applyBrightness(100);
        setElapsedSeconds(59);
        applyBrightness(150);

        assertSleepDelays(FAST_DELAY_MS, REGULAR_DELAY_MS);
    }

    @Test
    void fastWindowDoesNotAffectOtherGroups() {
        api.allowFastSceneUpdate("OTHER_GROUP");
        applyBrightness(100);

        assertSleepDelays(REGULAR_DELAY_MS);
    }

    @Test
    void newlyCreatedTemporarySceneAlsoUsesFastDelay() throws Exception {
        mockNoScenesAndCreationResult("NEW_TEMP");
        api.allowFastSceneUpdate("GROUP");

        applyBrightness(100);

        assertSceneCreatedThenRecalled("NEW_TEMP", FAST_DELAY_MS);
    }

    private void applyBrightness(int brightness) {
        // B remains off, so this requires the temporary scene rather than recalling SOURCE.
        api.putSceneState("GROUP", "SOURCE", List.of(PutCall.builder().id("A").bri(brightness).build()));
    }

    private void setElapsedSeconds(long seconds) {
        elapsedNanos.set(Duration.ofSeconds(seconds).toNanos());
    }

    private void assertSleepDelays(long... expectedDelays) {
        ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);
        verify(sleep, times(expectedDelays.length)).accept(delays.capture());
        assertThat(delays.getAllValues().stream().mapToLong(Long::longValue).toArray())
                .containsExactly(expectedDelays);
        verifyNoMoreInteractions(sleep);
    }

    private void assertSceneUpdatedThenRecalled(String sceneId, long delayInMs) throws Exception {
        InOrder order = inOrder(resources, sleep);
        order.verify(resources).putResource(eq(url("/scene/" + sceneId)), contains("\"actions\""));
        assertDelayThenRecall(order, sceneId, delayInMs);
    }

    private void assertSceneCreatedThenRecalled(String sceneId, long delayInMs) throws Exception {
        InOrder order = inOrder(resources, sleep);
        order.verify(resources).postResource(eq(url("/scene")), contains("\"actions\""));
        assertDelayThenRecall(order, sceneId, delayInMs);
    }

    private void assertDelayThenRecall(InOrder order, String sceneId, long delayInMs) throws Exception {
        order.verify(sleep).accept(delayInMs);
        order.verify(resources).putResource(eq(url("/scene/" + sceneId)), contains("\"recall\""));
        assertSleepDelays(delayInMs);
    }

    private void mockGroupWithOneLightOff() throws Exception {
        respond("/grouped_light", """
                [{
                  "id": "GROUP",
                  "owner": {"rid": "ZONE", "rtype": "zone"}
                }]
                """);
        respond("/zone", """
                [{
                  "id": "ZONE",
                  "type": "zone",
                  "children": [{"rid": "A", "rtype": "light"}, {"rid": "B", "rtype": "light"}],
                  "services": [{"rid": "GROUP", "rtype": "grouped_light"}]
                }]
                """);
        respond("/light", """
                [
                  {"id": "A", "on": {"on": true}, "dimming": {"brightness": 100}},
                  {"id": "B", "on": {"on": false}, "dimming": {"brightness": 50}}
                ]
                """);
    }

    private void mockSourceAndTemporaryScenes() throws Exception {
        respond("/scene", """
                [
                  {
                    "id": "SOURCE",
                    "metadata": {"name": "12:00"},
                    "group": {"rid": "ZONE", "rtype": "zone"},
                    "actions": [
                      {
                        "target": {"rid": "A", "rtype": "light"},
                        "action": {"on": {"on": true}, "dimming": {"brightness": 100}}
                      },
                      {
                        "target": {"rid": "B", "rtype": "light"},
                        "action": {"on": {"on": true}, "dimming": {"brightness": 50}}
                      }
                    ]
                  },
                  {
                    "id": "TEMP",
                    "metadata": {"name": "HueTemp", "appdata": "hue_sch:temp"},
                    "group": {"rid": "ZONE", "rtype": "zone"},
                    "actions": [
                      {
                        "target": {"rid": "A", "rtype": "light"},
                        "action": {"on": {"on": true}, "dimming": {"brightness": 20}}
                      },
                      {
                        "target": {"rid": "B", "rtype": "light"},
                        "action": {"on": {"on": false}}
                      }
                    ]
                  }
                ]
                """);
    }

    private void mockNoScenesAndCreationResult(String sceneId) throws Exception {
        respond("/scene", "[]");
        when(resources.postResource(eq(url("/scene")), any())).thenReturn("""
                {"errors": [], "data": [{"rid": "%s", "rtype": "scene"}]}
                """.formatted(sceneId));
    }

    private void respond(String path, String data) throws Exception {
        when(resources.getResource(url(path))).thenReturn("{\"errors\":[],\"data\":" + data + "}");
    }

    private static URL url(String path) throws Exception {
        return URI.create("https://localhost/clip/v2/resource" + path).toURL();
    }
}
