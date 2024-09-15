package at.sv.hue;

import at.sv.hue.api.PutCall;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScheduledStateRegistry {

    private final Map<String, List<ScheduledState>> lightStates;
    private final Supplier<ZonedDateTime> currentTime;

    public ScheduledStateRegistry(Supplier<ZonedDateTime> currentTime) {
        this.currentTime = currentTime;
        lightStates = new HashMap<>();
    }

    public void addState(ScheduledState state) {
        lightStates.computeIfAbsent(state.getId(), key -> new ArrayList<>()).add(state);
    }

    public ScheduledStateSnapshot lookupPreviousState(ScheduledStateSnapshot currentStateSnapshot) {
        List<ScheduledStateSnapshot> previousStates = lookupPreviousStatesListIgnoringSame(currentStateSnapshot);
        return previousStates.stream()
                             .findFirst()
                             .orElse(null);
    }

    private List<ScheduledStateSnapshot> lookupPreviousStatesListIgnoringSame(ScheduledStateSnapshot currentStateSnapshot) {
        ZonedDateTime definedStart = currentStateSnapshot.getDefinedStart();
        ZonedDateTime theDayBefore = definedStart.minusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return getLightStatesForId(currentStateSnapshot)
                .stream()
                .filter(currentStateSnapshot::isNotSameState)
                .flatMap(state -> Stream.of(state.getSnapshot(theDayBefore), state.getSnapshot(definedStart)))
                .sorted(Comparator.comparing(ScheduledStateSnapshot::getDefinedStart, Comparator.reverseOrder()) // todo: for a better solution it would be nice to sort by start instead of definedStart
                                  .thenComparing(snapshot -> snapshot.getScheduledState().hasTransitionBefore()))
                .distinct()
                .filter(previousStateSnapshot -> {
                    ZonedDateTime previousDefinedStart = previousStateSnapshot.getDefinedStart();
                    if (previousDefinedStart.isEqual(definedStart)) {
                        return previousStateSnapshot.hasTransitionBefore(); // workaround for back to back tr-before and zero length states
                    }
                    return previousDefinedStart.isBefore(definedStart);
                })
                .collect(Collectors.toList());
    }

    public ScheduledStateSnapshot lookupNextState(ScheduledStateSnapshot currentStateSnapshot, ZonedDateTime definedStart) {
        return lookupNextState(getLightStatesForId(currentStateSnapshot), definedStart);
    }

    private ScheduledStateSnapshot lookupNextState(List<ScheduledState> states, ZonedDateTime definedStart) {
        List<ScheduledStateSnapshot> nextStates = lookupNextStatesList(states, definedStart);
        return nextStates.stream()
                         .filter(snapshot -> snapshot.getDefinedStart().isAfter(definedStart))
                         .findFirst()
                         .orElse(null);
    }

    private List<ScheduledStateSnapshot> lookupNextStatesList(List<ScheduledState> states, ZonedDateTime definedStart) {
        ZonedDateTime theDayAfter = definedStart.plusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return states.stream()
                     .flatMap(state -> Stream.of(state.getSnapshot(definedStart), state.getSnapshot(theDayAfter)))
                     .sorted(Comparator.comparing(ScheduledStateSnapshot::getStart))
                     .distinct()
                     .collect(Collectors.toList());
    }

    public ScheduledState getLastSeenState(ScheduledStateSnapshot currentStateSnapshot) {
        return getLastSeenState(currentStateSnapshot.getId());
    }

    public ScheduledState getLastSeenState(String id) {
        List<ScheduledState> lightStatesForId = getLightStatesForId(id);
        if (lightStatesForId == null) {
            return null;
        }
        return lightStatesForId.stream()
                               .filter(state -> state.getLastSeen() != null)
                               .max(Comparator.comparing(ScheduledState::getLastSeen))
                               .orElse(null);
    }

    public List<PutCall> getCurrentActivePutCalls(List<String> groupLights) {
        return groupLights.stream()
                          .map(this::getCurrentActivePutCall)
                          .flatMap(Optional::stream)
                          .toList();
    }

    public Optional<PutCall> getCurrentActivePutCall(String id) {
        return Optional.ofNullable(getLightStatesForId(id)).flatMap(this::getCurrentActivePutCall);
    }

    private Optional<PutCall> getCurrentActivePutCall(List<ScheduledState> lightStatesForId) {
        ZonedDateTime now = currentTime.get();
        return lightStatesForId.stream()
                               .map(state -> state.getSnapshot(now))
                               .filter(snapshot -> snapshot.isCurrentlyActive(now))
                               .findFirst()
                               .map(snapshot -> snapshot.getInterpolatedFullPicturePutCall(now));
    }

    public boolean hasStatesForId(String id) {
        return lightStates.containsKey(id);
    }

    private List<ScheduledState> getLightStatesForId(ScheduledStateSnapshot snapshot) {
        return getLightStatesForId(snapshot.getId());
    }

    private List<ScheduledState> getLightStatesForId(String id) {
        return lightStates.get(id);
    }

    public Collection<List<ScheduledState>> values() {
        return lightStates.values();
    }
}
