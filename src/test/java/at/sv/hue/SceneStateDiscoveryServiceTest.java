package at.sv.hue;

import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SceneStateDiscoveryServiceTest {
    private static final String SCENE = "scene-1";
    private static final String GROUP = "/groups/1";
    private static final List<ScheduledLightState> ACTIONS = List.of(
            ScheduledLightState.builder().id("/lights/1").bri(100).build());
    private final HueApi api = mock(HueApi.class);
    private final Consumer<String> rescheduler = mock();
    private final Queue<Runnable> retries = new ArrayDeque<>();
    private ScheduledStateRegistry registry;
    private SceneStateDiscoveryService discovery;

    @BeforeEach
    void setUp() {
        registry = new ScheduledStateRegistry(ZonedDateTime::now, api);
        discovery = new SceneStateDiscoveryService(api,
                new StartTimeProviderImpl(new SunTimesProviderImpl(48.2, 16.4, 0)), registry,
                rescheduler, _ -> {}, retries::add, 0, 24, 350, 0.06, true, false);
        when(api.getScene(SCENE)).thenReturn(new Identifier(SCENE, "18:00"));
        when(api.getAllScenes()).thenReturn(List.of(new Identifier(SCENE, "18:00")));
        when(api.getGroupIdForScene(SCENE)).thenReturn(new Identifier(GROUP, "Room"));
        when(api.getGroupLights(GROUP)).thenReturn(List.of("/lights/1"));
        when(api.getSceneLightStates(SCENE)).thenReturn(ACTIONS);
    }

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    private ScheduledState existingDefinition() {
        discovery.discoverSceneStates();
        return registry.findStatesWithSceneId(SCENE).getFirst();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRenameKeepsDefinitionAndRetriesLatestScene(boolean connectionFailure) {
        ScheduledState original = existingDefinition();
        int generation = original.getGeneration();
        when(api.getScene(SCENE)).thenReturn(new Identifier(SCENE, "19:00"));
        RuntimeException failure = connectionFailure
                ? new BridgeConnectionFailure("offline") : new ApiFailure("503");
        when(api.getSceneLightStates(SCENE)).thenThrow(failure).thenReturn(ACTIONS);

        discovery.onSceneCreatedOrRenamed(SCENE);

        assertThat(registry.findStatesWithSceneId(SCENE)).containsExactly(original);
        assertThat(original.getGeneration()).isEqualTo(generation);
        verifyNoInteractions(rescheduler);
        assertThat(retries).hasSize(1);

        when(api.getScene(SCENE)).thenReturn(new Identifier(SCENE, "20:00"));
        retries.remove().run();

        assertThat(registry.findStatesWithSceneId(SCENE)).singleElement()
                .extracting(ScheduledState::getStartString).isEqualTo("20:00");
        assertThat(original.getGeneration()).isGreaterThan(generation);
        verify(rescheduler).accept(GROUP);
        assertThat(retries).isEmpty();
    }

    @Test
    void failedSceneLookupIsRetriedWithoutRemovingDefinition() {
        ScheduledState original = existingDefinition();
        when(api.getScene(SCENE)).thenThrow(new BridgeConnectionFailure("offline"))
                .thenReturn(new Identifier(SCENE, "19:00"));
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(registry.findStatesWithSceneId(SCENE)).containsExactly(original);
        retries.remove().run();
        assertThat(registry.findStatesWithSceneId(SCENE)).singleElement()
                .extracting(ScheduledState::getStartString).isEqualTo("19:00");
    }

    @Test
    void repeatedFailuresKeepOneActiveRetryAndNewEventSupersedesIt() {
        existingDefinition();
        when(api.getSceneLightStates(SCENE)).thenThrow(new ApiFailure("503"));
        discovery.onSceneCreatedOrRenamed(SCENE);
        for (int i = 0; i < 3; i++) {
            assertThat(retries).hasSize(1);
            retries.remove().run();
        }
        Runnable superseded = retries.remove();
        discovery.onSceneCreatedOrRenamed(SCENE);
        clearInvocations(api);

        superseded.run();

        verifyNoInteractions(api);
        assertThat(retries).hasSize(1);
        doReturn(ACTIONS).when(api).getSceneLightStates(SCENE);
        retries.remove().run();
        assertThat(retries).isEmpty();
    }

    @Test
    void temporarilyMissingLightDataDoesNotDiscardTheGroupsDefinition() {
        ScheduledState original = existingDefinition();
        when(api.getSceneLightStates(SCENE)).thenThrow(new LightNotFoundException("light cache is behind"))
                .thenReturn(ACTIONS);
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(registry.findStatesWithSceneId(SCENE)).containsExactly(original);
        retries.remove().run();
        assertThat(registry.findStatesWithSceneId(SCENE)).hasSize(1);
    }

    @Test
    void deletionCancelsQueuedRetryEvenIfTheApiStillReturnsTheScene() {
        existingDefinition();
        when(api.getSceneLightStates(SCENE)).thenThrow(new ApiFailure("503"));
        discovery.onSceneCreatedOrRenamed(SCENE);
        discovery.onSceneDeleted(SCENE);
        clearInvocations(api);

        retries.remove().run();

        verifyNoInteractions(api);
        assertThat(registry.findStatesWithSceneId(SCENE)).isEmpty();
        assertThat(retries).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20:00", "Relax", "19:00 [invalid]"})
    void newerRenameSupersedesQueuedRetry(String name) {
        existingDefinition();
        when(api.getSceneLightStates(SCENE)).thenThrow(new ApiFailure("503")).thenReturn(ACTIONS);
        discovery.onSceneCreatedOrRenamed(SCENE);
        when(api.getScene(SCENE)).thenReturn(new Identifier(SCENE, name));
        discovery.onSceneCreatedOrRenamed(SCENE);
        clearInvocations(api, rescheduler);

        retries.remove().run();

        verifyNoInteractions(api, rescheduler);
        if (name.equals("20:00")) {
            assertThat(registry.findStatesWithSceneId(SCENE)).singleElement()
                    .extracting(ScheduledState::getStartString).isEqualTo(name);
        } else {
            assertThat(registry.findStatesWithSceneId(SCENE)).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void eventDuringInFlightRetryPreventsStaleReplacement(boolean delete, boolean fail) throws Exception {
        existingDefinition();
        when(api.getSceneLightStates(SCENE)).thenThrow(new ApiFailure("503"));
        discovery.onSceneCreatedOrRenamed(SCENE);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(_ -> {
            reading.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            if (fail) throw new ApiFailure("503");
            return ACTIONS;
        }).when(api).getSceneLightStates(SCENE);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retry = executor.submit(retries.remove());
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                if (delete) {
                    discovery.onSceneDeleted(SCENE);
                } else {
                    when(api.getScene(SCENE)).thenReturn(new Identifier(SCENE, "Relax"));
                    discovery.onSceneCreatedOrRenamed(SCENE);
                }
                clearInvocations(rescheduler);
            } finally {
                release.countDown();
            }
            retry.get(5, TimeUnit.SECONDS);
        }
        assertThat(registry.findStatesWithSceneId(SCENE)).isEmpty();
        verifyNoInteractions(rescheduler);
        assertThat(retries).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyGroupIsRetainedDuringInitialDiscoveryAndCreation(boolean initialDiscovery) {
        when(api.getGroupLights(GROUP)).thenThrow(new EmptyGroupException("empty"));
        if (initialDiscovery) {
            discovery.discoverSceneStates();
        } else {
            discovery.onSceneCreatedOrRenamed(SCENE);
        }
        assertThat(registry.findStatesWithSceneId(SCENE)).singleElement()
                .satisfies(state -> assertThat(state.getGroupLightIds()).isEmpty());
        assertThat(retries).isEmpty();
    }

    @Test
    void missingGroupRemovesDefinitionWithoutRetry() {
        existingDefinition();
        when(api.getGroupIdForScene(SCENE)).thenThrow(new GroupNotFoundException("deleted"));
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(registry.findStatesWithSceneId(SCENE)).isEmpty();
        assertThat(retries).isEmpty();
    }

    @Test
    void initialDiscoveryFailureRetriesAndReschedulesWhenItRecovers() {
        when(api.getSceneLightStates(SCENE)).thenThrow(new ApiFailure("503")).thenReturn(ACTIONS);
        discovery.discoverSceneStates();
        assertThat(registry.findStatesWithSceneId(SCENE)).isEmpty();
        retries.remove().run();
        assertThat(registry.findStatesWithSceneId(SCENE)).hasSize(1);
        verify(rescheduler).accept(GROUP);
    }

    @Test
    void callbacksRestorePreviousLoggingContextOnEveryExit() {
        MDC.put("context", "outer");
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(MDC.get("context")).isEqualTo("outer");
        when(api.getScene(SCENE)).thenReturn(null);
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(MDC.get("context")).isEqualTo("outer");
        when(api.getScene(SCENE)).thenThrow(new ApiFailure("503"));
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(MDC.get("context")).isEqualTo("outer");
        retries.remove().run();
        assertThat(MDC.get("context")).isEqualTo("outer");
        discovery.onSceneDeleted(SCENE);
        assertThat(MDC.get("context")).isEqualTo("outer");
        MDC.clear();
        discovery.onSceneCreatedOrRenamed(SCENE);
        assertThat(MDC.get("context")).isNull();
    }
}
