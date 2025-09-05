package at.sv.hue;

import at.sv.hue.api.PutCall;
import at.sv.hue.color.ColorModeConverter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@RequiredArgsConstructor
public final class StateInterpolator {

    private static final int MAX_HUE_VALUE = 65535;
    private static final int MIDDLE_HUE_VALUE = 32768;

    private final ScheduledStateSnapshot state;
    private final ScheduledStateSnapshot previousState;
    private final ZonedDateTime dateTime;

    public PutCalls getInterpolatedPutCalls() {
        if (state.isAlreadyReached(dateTime)) {
            return null; // the state is already reached
        }
        return interpolate();
    }

    /**
     * P = P0 + t(P1 - P0)
     */
    private PutCalls interpolate() {
        BigDecimal interpolatedTime = getInterpolatedTime();
        PutCalls previous = previousState.getFullPicturePutCalls(dateTime);
        PutCalls target = state.getFullPicturePutCalls(dateTime);

        return interpolate(previous, target, interpolatedTime);
    }

    private PutCalls interpolate(PutCalls previous, PutCalls target, BigDecimal interpolatedTime) {
        List<PutCall> putCalls = new ArrayList<>();
        if (previous.isGeneralGroup()) {
            PutCall previousPutCall = previous.toList().getFirst();
            target.toList().forEach(targetPutCall -> {
                PutCall putCall = interpolate(previousPutCall, targetPutCall, interpolatedTime);
                putCall.setId(targetPutCall.getId());
                putCalls.add(putCall);
            });
        } else if (target.isGeneralGroup()) {
            PutCall targetPutCall = target.toList().getFirst();
            previous.toList().forEach(previousPutCall -> {
                PutCall putCall = interpolate(previousPutCall, targetPutCall, interpolatedTime);
                putCalls.add(putCall);
            });
        } else {
            previous.toList().forEach(previousPutCall -> {
                PutCall targetPutCall = target.get(previousPutCall.getId());
                if (targetPutCall == null) {
                    return; // skip lights that are not part of the target state
                }
                PutCall putCall = interpolate(previousPutCall, targetPutCall, interpolatedTime);
                putCalls.add(putCall);
            });
        }
        return new PutCalls(previous.getId(), putCalls, previous.getTransitionTime(), previous.isGroupUpdate());
    }

    private PutCall interpolate(PutCall previous, PutCall target, BigDecimal interpolatedTime) {
        PutCall putCall = copy(previous);
        convertColorModeIfNeeded(putCall, target);

        putCall.setBri(interpolateInteger(interpolatedTime, getTargetBri(target), putCall.getBri()));
        putCall.setCt(interpolateInteger(interpolatedTime, target.getCt(), putCall.getCt()));
        putCall.setHue(interpolateHue(interpolatedTime, target.getHue(), putCall.getHue()));
        putCall.setSat(interpolateInteger(interpolatedTime, target.getSat(), putCall.getSat()));
        putCall.setX(interpolateDouble(interpolatedTime, target.getX(), putCall.getX()));
        putCall.setY(interpolateDouble(interpolatedTime, target.getY(), putCall.getY()));
        putCall.setGradient(interpolateGradient(interpolatedTime, target.getGradient(), putCall.getGradient()));

        putCall.setOn(null); // do not per default reuse "on" property for interpolation
        if (target.isOn()) {
            putCall.setOn(true); // the current state is turning lights on, also set "on" property for interpolated state
        }

        return putCall;
    }

    private static Integer getTargetBri(PutCall target) {
        if (target.isOff()) {
            return 0;
        }
        return target.getBri();
    }

    private static PutCall copy(PutCall putCall) {
        return putCall.toBuilder().build();
    }

    /**
     * Returns true if the previous and target put call don't have any differing common properties, which means that no interpolation
     * would be possible. Here we don't care about the time differences, but just the available properties of the put calls.
     */
    public static boolean hasNoOverlappingProperties(PutCall previous, PutCall target) {
        PutCall putCall = copy(previous);
        putCall.setOn(null); // do not reuse on property
        putCall.setEffect(null); // interpolation between effects not supported
        convertColorModeIfNeeded(putCall, target);
        removeEqualProperties(putCall, target);
        return putCall.isNullCall();
    }

    private static void removeEqualProperties(PutCall putCall, PutCall target) {
        if (isEqualOrNotAvailableAtTarget(putCall, getTargetConsideringOff(target), PutCall::getBri)) {
            putCall.setBri(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getCt)) {
            putCall.setCt(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getHue)) {
            putCall.setHue(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getSat)) {
            putCall.setSat(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getX)) {
            putCall.setX(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getY)) {
            putCall.setY(null);
        }
    }

    private static PutCall getTargetConsideringOff(PutCall target) {
        if (target.isOff()) {
            PutCall modifiedTarget = copy(target);
            modifiedTarget.setBri(0);
            return modifiedTarget;
        }
        return target;
    }

    private static boolean isEqualOrNotAvailableAtTarget(PutCall putCall, PutCall target, Function<PutCall, Object> function) {
        return Objects.equals(function.apply(putCall), function.apply(target)) || function.apply(target) == null;
    }

    /**
     * t = (current_time - start_time) / (end_time - start_time)
     */
    private BigDecimal getInterpolatedTime() {
        Duration durationAfterStart = Duration.between(state.getStart(), dateTime);
        Duration totalDuration = Duration.between(state.getStart(), state.getDefinedStart());
        return BigDecimal.valueOf(durationAfterStart.toMillis())
                         .divide(BigDecimal.valueOf(totalDuration.toMillis()), 7, RoundingMode.HALF_UP);
    }


    private static void convertColorModeIfNeeded(PutCall previousPutCall, PutCall target) {
        ColorModeConverter.convertIfNeeded(previousPutCall, target.getColorMode());
    }

    private Integer interpolateInteger(BigDecimal interpolatedTime, Integer target, Integer previous) {
        if (target == null) {
            return previous;
        }
        if (previous == null) {
            return null;
        }
        BigDecimal diff = BigDecimal.valueOf(target - previous);
        return BigDecimal.valueOf(previous)
                         .add(interpolatedTime.multiply(diff))
                         .setScale(0, RoundingMode.HALF_UP)
                         .intValue();
    }

    private Double interpolateDouble(BigDecimal interpolatedTime, Double target, Double previous) {
        if (target == null) {
            return previous;
        }
        if (previous == null) {
            return null;
        }
        BigDecimal diff = BigDecimal.valueOf(target - previous);
        return BigDecimal.valueOf(previous)
                         .add(interpolatedTime.multiply(diff))
                         .setScale(5, RoundingMode.HALF_UP)
                         .doubleValue();
    }

    /**
     * Perform special interpolation for hue values, as they wrap around i.e. 0 and 65535 are both considered red.
     * This means that we need to decide in which direction we want to interpolate, to get the smoothest transition.
     */
    private Integer interpolateHue(BigDecimal interpolatedTime, Integer target, Integer previous) {
        if (target == null) {
            return previous;
        }
        if (previous == null) {
            return null;
        }
        if (previous == 0 && target == MAX_HUE_VALUE || previous == MAX_HUE_VALUE && target == 0) {
            return 0;
        }
        int diff = ((target - previous + MIDDLE_HUE_VALUE) % (MAX_HUE_VALUE + 1)) - MIDDLE_HUE_VALUE;
        return BigDecimal.valueOf(previous)
                         .add(interpolatedTime.multiply(BigDecimal.valueOf(diff)))
                         .setScale(0, RoundingMode.HALF_UP)
                         .intValue();
    }

    private Gradient interpolateGradient(BigDecimal t, Gradient target, Gradient previous) {
        if (target == null) {
            return previous;
        }
        if (previous == null) {
            return null;
        }
        // todo: empty mode == interpolated_palette; consider this
        if (!Objects.equals(previous.mode(), target.mode())) {
            return null; // invariant for now
        }

        final var previousPoints = previous.points();
        final var targetPoints = target.points();
        final int n = Math.max(previousPoints.size(), targetPoints.size());
        List<Pair<Double, Double>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // Evaluate both gradients at the same normalized position
            double pos;
            if (n == 1) {
                pos = 0.0;
            } else {
                pos = (double) i / (double) (n - 1);
            }

            Pair<Double, Double> p = evalAt(previousPoints, pos); // previous at pos
            Pair<Double, Double> q = evalAt(targetPoints, pos); // target at pos

            Double x = interpolateDouble(t, q.first(), p.first());
            Double y = interpolateDouble(t, q.second(), p.second());
            out.add(Pair.of(x, y));
        }
        return new Gradient(out, target.mode());
    }

    /**
     * Linear evaluate an XY pair list at normalized position pos ∈ [0,1].
     */
    private static Pair<Double, Double> evalAt(List<Pair<Double, Double>> points, double position) {
        int n = points.size();
        if (n == 1) {
            return points.getFirst();
        }

        double f = position * (n - 1);
        int i0 = (int) Math.floor(f);
        int i1 = Math.min(i0 + 1, n - 1);
        double w = f - i0;

        var a = points.get(i0);
        var b = points.get(i1);
        double x = a.first() + w * (b.first() - a.first());
        double y = a.second() + w * (b.second() - a.second());
        return Pair.of(x, y);
    }

}
