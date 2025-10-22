package at.sv.hue;

import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.PutCall;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScheduledStateRegistry {

    private final Map<String, List<ScheduledState>> lightStates;
    private final Supplier<ZonedDateTime> currentTime;
    private final HueApi api;

    public ScheduledStateRegistry(Supplier<ZonedDateTime> currentTime, HueApi api) {
        this.currentTime = currentTime;
        this.api = api;
        lightStates = new LinkedHashMap<>();
    }

    public void addState(ScheduledState state) {
        lightStates.computeIfAbsent(state.getId(), _ -> new ArrayList<>()).add(state);
    }

    public ScheduledStateSnapshot getPreviousState(ScheduledStateSnapshot currentStateSnapshot) {
        return getDistinctPreviousStatesBefore(currentStateSnapshot).stream()
                                                                    .findFirst()
                                                                    .orElse(null);
    }

    private List<ScheduledStateSnapshot> getDistinctPreviousStatesBefore(ScheduledStateSnapshot currentState) {
        ZonedDateTime definedStart = currentState.getDefinedStart();
        ZonedDateTime theDayBefore = definedStart.minusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return findStatesForId(currentState)
                .stream()
                .filter(currentState::isNotSameState)
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

    public ScheduledStateSnapshot getNextStateAfter(ScheduledStateSnapshot currentState, ZonedDateTime definedStart) {
        ZonedDateTime theDayAfter = definedStart.plusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return findStatesForId(currentState).stream()
                                            .flatMap(state -> Stream.of(state.getSnapshot(definedStart), state.getSnapshot(theDayAfter)))
                                            .filter(snapshot -> snapshot.getDefinedStart().isAfter(definedStart))
                                            .distinct()
                                            .min(Comparator.comparing(ScheduledStateSnapshot::getStart))
                                            .orElse(null);
    }

    public ScheduledState getLastSeenState(ScheduledStateSnapshot state) {
        return getLastSeenState(state.getId());
    }

    public ScheduledState getLastSeenState(String id) {
        List<ScheduledState> lightStatesForId = findStatesForId(id);
        if (lightStatesForId == null) {
            return null;
        }
        return lightStatesForId.stream()
                               .filter(state -> state.getLastSeen() != null)
                               .max(Comparator.comparing(ScheduledState::getLastSeen))
                               .orElse(null);
    }

    public long countOverlappingGroupStatesWithMoreLights(ScheduledStateSnapshot state) {
        List<String> groupLights = getGroupLights(state);
        return getAssignedGroupsSortedBySizeDesc(groupLights)
                .stream()
                .filter(groupInfo -> groupInfo.groupLights().size() > groupLights.size())
                .map(GroupInfo::groupId)
                .filter(lightStates::containsKey)
                .count();
    }

    private List<String> getGroupLights(ScheduledStateSnapshot state) {
        if (state.isGroupState()) {
            return api.getGroupLights(state.getId());
        } else {
            return List.of(state.getId());
        }
    }

    public List<GroupInfo> getAssignedGroups(ScheduledStateSnapshot state) {
        List<String> groupLights = getGroupLights(state);
        List<GroupInfo> assignedGroups = getAssignedGroupsSortedBySizeDesc(groupLights);
        List<GroupInfo> additionalAreas = api.getAdditionalAreas(groupLights);
        return Stream.concat(assignedGroups.stream(), additionalAreas.stream())
                     .distinct()
                     .toList();
    }

    private List<GroupInfo> getAssignedGroupsSortedBySizeDesc(List<String> lightIds) {
        return lightIds.stream()
                       .map(api::getAssignedGroups)
                       .flatMap(Collection::stream)
                       .distinct()
                       .map(groupId -> new GroupInfo(groupId, api.getGroupLights(groupId)))
                       // larger groups first, so that smaller groups can override the larger ones
                       .sorted(Comparator.comparingInt((GroupInfo groupInfo) -> groupInfo.groupLights().size()).reversed())
                       .toList();
    }

    /**
     * Retrieves a list of active PutCalls for the specified group lights. Multiple overlapping state definitions are
     * resolved the following way: From biggest to smallest group the light is assigned to, then individual light
     * definition.
     *
     * @param groupLights the list of light IDs for which to retrieve the active PutCall objects
     * @return a list of active PutCall objects corresponding to the provided light IDs
     */
    public List<PutCall> getPutCalls(List<String> groupLights) {
        ZonedDateTime now = currentTime.get();
        Map<String, PutCall> putCalls = getActivePutCallsFromGroups(groupLights, now);
        findActivePutCalls(groupLights, now).stream()
                .flatMap(PutCalls::stream)
                .forEach(putCall -> putCalls.put(putCall.getId(), putCall));
        return new ArrayList<>(putCalls.values());
    }

    private Map<String, PutCall> getActivePutCallsFromGroups(List<String> groupLights, ZonedDateTime now) {
        return getAssignedGroupsSortedBySizeDesc(groupLights)
                .stream()
                .map(GroupInfo::groupId)
                .flatMap(groupId -> findActivePutCalls(groupId, now).stream())
                .flatMap(putCalls -> createOverriddenLightPutCalls(putCalls, groupLights))
                .collect(Collectors.toMap(PutCall::getId, putCall -> putCall, (_, replacement) -> replacement, LinkedHashMap::new));
    }

    private List<PutCalls> findActivePutCalls(List<String> ids, ZonedDateTime now) {
        return ids.stream()
                  .map(id -> findActivePutCalls(id, now))
                  .flatMap(Optional::stream)
                  .toList();
    }

    private Optional<PutCalls> findActivePutCalls(String id, ZonedDateTime now) {
        return Optional.ofNullable(findStatesForId(id))
                       .flatMap(lightStatesForId -> findActivePutCall(lightStatesForId, now));
    }

    private Optional<PutCalls> findActivePutCall(List<ScheduledState> lightStatesForId, ZonedDateTime now) {
        return findActiveSnapshot(lightStatesForId, now)
                .map(snapshot -> snapshot.getInterpolatedFullPicturePutCalls(now));
    }

    private Optional<ScheduledStateSnapshot> findActiveSnapshot(List<ScheduledState> lightStatesForId, ZonedDateTime now) {
        ZonedDateTime theDayBefore = now.minusDays(1);
        ZonedDateTime theDayAfter = now.plusDays(1);
        return lightStatesForId.stream()
                               .flatMap(state -> Stream.of(state.getSnapshot(theDayBefore),
                                       state.getSnapshot(now), state.getSnapshot(theDayAfter)))
                               .filter(snapshot -> snapshot.isCurrentlyActive(now))
                               .findFirst();
    }

    private Stream<PutCall> createOverriddenLightPutCalls(PutCalls otherGroupPutCalls, List<String> groupLights) {
        return findOverlappingLights(otherGroupPutCalls.getId(), groupLights)
                .stream()
                .map(lightId -> convertToLightPutCall(otherGroupPutCalls, lightId));
    }

    private List<String> findOverlappingLights(String otherGroupId, List<String> groupLights) {
        List<String> otherGroupLights = api.getGroupLights(otherGroupId);
        List<String> overlappingLights = new ArrayList<>(groupLights);
        overlappingLights.retainAll(otherGroupLights);
        return overlappingLights;
    }

    private static PutCall convertToLightPutCall(PutCalls putCalls, String lightId) {
        if (putCalls.isGeneralGroup()) {
            return putCalls.getFirst().toBuilder().id(lightId).build();
        } else {
            return putCalls.get(lightId);
        }
    }

    private List<ScheduledState> findStatesForId(ScheduledStateSnapshot snapshot) {
        return findStatesForId(snapshot.getId());
    }

    private List<ScheduledState> findStatesForId(String id) {
        return lightStates.get(id);
    }

    public Collection<List<ScheduledState>> values() {
        return lightStates.values();
    }

    public List<ScheduledStateSnapshot> findCurrentlyActiveStates() {
        ZonedDateTime now = currentTime.get();
        return values().stream()
                .map(lightStatesForId -> findActiveSnapshot(lightStatesForId, now))
                .flatMap(Optional::stream)
                .toList();
    }
}
