package at.sv.hue;

import at.sv.hue.api.HueApi;
import at.sv.hue.api.PutCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class InputToSceneMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(InputToSceneMigrator.class);

    private final HueApi api;
    private final ScheduledStateRegistry stateRegistry;
    private final Supplier<ZonedDateTime> currentTime;

    InputToSceneMigrator(HueApi api, ScheduledStateRegistry stateRegistry, Supplier<ZonedDateTime> currentTime) {
        this.api = api;
        this.stateRegistry = stateRegistry;
        this.currentTime = currentTime;
    }

    void migrate() {
        MDC.put("context", "migration");
        List<ScheduledState> states = new ArrayList<>();
        stateRegistry.forEach(states::addAll);
        int migrated = 0;
        int skipped = 0;
        ZonedDateTime now = currentTime.get();
        for (ScheduledState state : states) {
            if (!state.isGroupState()) {
                skipped++;
                continue;
            }
            String sceneName = getMigrationSceneName(state);
            List<PutCall> putCalls = getMigrationPutCalls(state, now);
            api.createOrUpdateScene(state.getId(), sceneName, putCalls);
            migrated++;
        }
        LOG.info("Input-to-scene migration finished. Migrated: {}, skipped: {}. Exiting.", migrated, skipped);
        MDC.remove("context");
    }

    private List<PutCall> getMigrationPutCalls(ScheduledState state, ZonedDateTime now) {
        // The bridge needs actions even for a schedule gap; the adapter supplies off placeholders.
        if (state.isNullState()) return List.of();
        ScheduledStateSnapshot snapshot = state.getSnapshot(now);
        ZonedDateTime definedStart = snapshot.getDefinedStart();
        List<PutCall> putCalls = stateRegistry.getPutCalls(state.getGroupLightIds(), definedStart);
        return putCalls.stream()
                       .map(putCall -> putCall.toBuilder().transitionTime(null).build())
                       .toList();
    }

    private static String getMigrationSceneName(ScheduledState state) {
        List<String> flags = new ArrayList<>();
        String days = DayOfWeeksParser.formatDaysOfWeek(state.getDaysOfWeek());
        if (days != null) {
            flags.add(days);
        }
        if (state.isNullState()) {
            flags.add("gap");
            return state.getStartString() + " [" + String.join(",", flags) + "]";
        }
        if (state.getInterpolate() == Boolean.TRUE) {
            flags.add("i");
        } else if (state.getInterpolate() == Boolean.FALSE) {
            flags.add("i:false");
        }
        if (state.getTransitionTimeBeforeString() != null) {
            flags.add("tr-b:" + state.getTransitionTimeBeforeString());
        }
        if (state.getDefinedTransitionTime() != null) {
            flags.add("tr:" + formatTransitionTime(state.getDefinedTransitionTime()));
        }
        if (state.isForced()) {
            flags.add("f");
        }
        if (state.isOff()) {
            flags.add("off");
        }
        if (state.isOn()) {
            flags.add("on");
        }
        if (flags.isEmpty()) {
            return state.getStartString();
        }
        return state.getStartString() + " [" + String.join(",", flags) + "]";
    }

    private static String formatTransitionTime(Integer definedTransitionTime) {
        Duration duration = Duration.ofMillis(definedTransitionTime * 100L);
        if (duration.isZero()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        if (duration.toHours() > 0) {
            sb.append(duration.toHours()).append("h");
        }
        if (duration.toMinutes() % 60 > 0) {
            sb.append(duration.toMinutes() % 60).append("min");
        }
        if (duration.toSeconds() % 60 > 0) {
            sb.append(duration.toSeconds() % 60).append("s");
        }
        if (duration.toMillis() % 1000L > 0) {
            sb.append(duration.toMillis() % 1000L / 100);
        }
        return sb.toString();
    }
}
