package at.sv.hue;

import at.sv.hue.api.PutCall;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PutCalls {

    @Getter
    private final String id;
    private final List<PutCall> putCalls;
    @Getter
    private final boolean groupUpdate;
    private final Map<String, PutCall> map;
    @Getter
    @Setter
    private Integer transitionTime;

    public PutCalls(String id, List<PutCall> putCalls, Integer transitionTime, boolean groupUpdate) {
        this.id = id;
        this.putCalls = convertPutCallsIfNeeded(id, putCalls, groupUpdate);
        this.transitionTime = transitionTime;
        this.groupUpdate = groupUpdate;
        map = this.putCalls.stream().collect(Collectors.toMap(PutCall::getId, Function.identity()));
    }

    private static List<PutCall> convertPutCallsIfNeeded(String id, List<PutCall> putCalls, boolean groupUpdate) {
        if (groupUpdate && putCalls.size() == 1) { // for non groups the id of the putCall is already the light id
            return List.of(putCalls.getFirst().toBuilder()
                                   .id(id) // use group id instead of light id
                                   .build());
        }
        return putCalls;
    }

    public Stream<PutCall> stream() {
        return putCalls.stream()
                       .map(putCall -> {
                           putCall.setTransitionTime(transitionTime);
                           return putCall;
                       });
    }

    public List<PutCall> toList() {
        return stream().toList();
    }

    public PutCall getFirst() {
        return toList().getFirst();
    }

    public PutCall get(String id) {
        return map.get(id);
    }

    public PutCalls map(Function<PutCall, PutCall> mapper) {
        return new PutCalls(id, putCalls.stream()
                                        .map(mapper)
                                        .collect(Collectors.toList()), transitionTime, groupUpdate);
    }

    public void resetOn() {
        putCalls.forEach(PutCall::resetOn);
    }

    public boolean isGeneralGroup() {
        return groupUpdate && putCalls.size() == 1;
    }

    public boolean hasSameLightStates(PutCalls other) {
        return allMatch(other, PutCall::hasSameLightState);
    }

    public boolean hasNotSimilarLightState(PutCalls other, int brightnessThreshold,
                                           int colorTemperatureThresholdKelvin,
                                           double colorThreshold) {
        return anyMatch(other, (pc1, pc2) -> pc1.hasNotSimilarLightState(pc2,
                                                                           brightnessThreshold,
                                                                           colorTemperatureThresholdKelvin,
                                                                           colorThreshold));
    }

    private boolean anyMatch(PutCalls other, BiPredicate<PutCall, PutCall> predicate) {
        return matchWith(other).stream().anyMatch(pair -> pair.test(predicate));
    }

    public boolean allMatch(PutCalls other, BiPredicate<PutCall, PutCall> predicate) {
        return matchWith(other).stream().allMatch(pair -> pair.test(predicate));
    }

    private List<Pair<PutCall, PutCall>> matchWith(PutCalls target) {
        List<Pair<PutCall, PutCall>> pairs = new ArrayList<>();
        if (isGeneralGroup()) {
            PutCall sourcePutCall = putCalls.getFirst();
            target.putCalls.forEach(targetPutCall -> pairs.add(Pair.of(sourcePutCall, targetPutCall)));
        } else if (target.isGeneralGroup()) {
            PutCall targetPutCall = target.putCalls.getFirst();
            putCalls.forEach(sourcePutCall -> pairs.add(Pair.of(sourcePutCall, targetPutCall)));
        } else {
            target.map.forEach((id, putCall) -> pairs.add(Pair.of(map.getOrDefault(id, null), putCall)));
        }
        return pairs;
    }

    @Override
    public String toString() {
        return "PutCalls{" +
               putCalls +
               ", group=" + groupUpdate +
               getFormattedTransitionTimeIfSet() +
               '}';
    }

    private String getFormattedTransitionTimeIfSet() {
        if (transitionTime == null) return "";
        return ", tr=" + transitionTime + " (" + Duration.ofMillis(transitionTime * 100L) + ")";
    }
}
