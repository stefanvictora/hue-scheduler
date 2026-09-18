package at.sv.hue;

import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class ScheduledStateRegistryConcurrencyTest {
    private final ZonedDateTime now = ZonedDateTime.parse("2026-09-18T12:00:00+02:00[Europe/Vienna]");

    @Test
    void callbacksAllowConcurrentRegistryLookups() {
        var registry = new ScheduledStateRegistry(() -> now, mock(HueApi.class));
        var state = ScheduledState.builder().identifier(new Identifier("group", "Room")).build();
        registry.addState(state);

        try (var executor = Executors.newSingleThreadExecutor()) {
            registry.forEach(_ -> {
                var lookup = executor.submit(() -> registry.findStatesForId("group"));
                assertThat(assertDoesNotThrow(() -> lookup.get(5, TimeUnit.SECONDS),
                        "Registry lookups must complete while a callback is running"))
                        .containsExactly(state);
            });
        }
    }

    @Test
    void callbacksReceiveAConsistentCopyOfAllStateLists() {
        var registry = new ScheduledStateRegistry(() -> now, mock(HueApi.class));
        var first = ScheduledState.builder().identifier(new Identifier("first", "First")).build();
        var second = ScheduledState.builder().identifier(new Identifier("second", "Second")).build();
        registry.addState(first);
        registry.addState(second);
        List<List<ScheduledState>> captured = new ArrayList<>();
        registry.forEach(states -> {
            captured.add(states);
            registry.remove(second);
        });
        registry.remove(first);
        assertThat(captured).containsExactly(List.of(first), List.of(second));
        assertThat(registry.findStatesForId("first")).isNull();
        assertThat(registry.findStatesForId("second")).isNull();
    }
}
