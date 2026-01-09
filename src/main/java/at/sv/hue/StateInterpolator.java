package at.sv.hue;

import at.sv.hue.api.PutCall;
import at.sv.hue.color.ColorModeConverter;
import at.sv.hue.color.XYColorGamutCorrection;
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

    private final ScheduledStateSnapshot state;
    private final ScheduledStateSnapshot previousState;
    private final ZonedDateTime dateTime;

    public PutCalls getInterpolatedPutCalls() {
        if (state.isAlreadyReached(dateTime)) {
            return null; // no interpolation needed anymore
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
            PutCall previousPutCall = previous.getFirst();
            target.toList().forEach(targetPutCall -> {
                PutCall putCall = interpolate(previousPutCall, targetPutCall, interpolatedTime);
                putCall.setId(targetPutCall.getId());
                putCalls.add(putCall);
            });
        } else if (target.isGeneralGroup()) {
            PutCall targetPutCall = target.getFirst();
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

        putCall.setBri(interpolateInteger(interpolatedTime, getBriConsideringOff(putCall), getBriConsideringOff(target))); // todo: for target we currently can't use off since this conflicts with off light updates
        putCall.setCt(interpolateInteger(interpolatedTime, putCall.getCt(), target.getCt()));
        var xy = interpolateXY(interpolatedTime, putCall.getXY(), target.getXY(), target.getGamut());
        if (xy != null) {
            putCall.setX(xy.first());
            putCall.setY(xy.second());
        } else {
            putCall.setX(null);
            putCall.setY(null);
        }
        putCall.setGradient(interpolateGradient(interpolatedTime, putCall.getGradient(), target.getGradient(),
                target.getGamut()));

        putCall.setOn(null); // do not per default reuse "on" property for interpolation
        if (target.isOn()) {
            putCall.setOn(true); // the current state is turning lights on, also set "on" property for interpolated state
        }
        if (previous.isOff() && target.isOff()) {
            putCall.setOn(false);
            putCall.setBri(null);
        }
        if (putCall.getBri() != null && putCall.getBri() == 0) {
            putCall.setBri(1); // min brightness is 1 if light is on
        }

        return putCall;
    }

    private static Integer getBriConsideringOff(PutCall putCall) {
        if (putCall.isOff()) {
            return 0;
        }
        return putCall.getBri();
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
        putCall.setBri(getBriConsideringOff(putCall));
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
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getX)) {
            putCall.setX(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, PutCall::getY)) {
            putCall.setY(null);
        }
        if (isEqualOrNotAvailableAtTarget(putCall, target, StateInterpolator::getGradientPoints)) { // we don't care about gradient mode here
            putCall.setGradient(null);
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

    private static List<Pair<Double, Double>> getGradientPoints(PutCall putCall) {
        if (putCall.getGradient() == null) {
            return null;
        }
        return putCall.getGradient().points();
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

    private Integer interpolateInteger(BigDecimal interpolatedTime, Integer previous, Integer target) {
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

    private Pair<Double, Double> interpolateXY(BigDecimal interpolatedTime, Pair<Double, Double> previous,
                                               Pair<Double, Double> target, Double[][] gamut) {
        if (target == null) { // todo: no mutation coverage
            return previous;
        }
        if (previous == null) { // todo: no mutation coverage
            return null;
        }

        Double x = interpolateDouble(interpolatedTime, previous.first(), target.first());
        Double y = interpolateDouble(interpolatedTime, previous.second(), target.second());

        XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
        return Pair.of(correction.getX(), correction.getY());
    }

    private Double interpolateDouble(BigDecimal interpolatedTime, Double previous, Double target) {
        BigDecimal diff = BigDecimal.valueOf(target - previous);
        return BigDecimal.valueOf(previous)
                         .add(interpolatedTime.multiply(diff))
                         .setScale(5, RoundingMode.HALF_UP)
                         .doubleValue();
    }

    private Gradient interpolateGradient(BigDecimal interpolatedTime, Gradient previous, Gradient target,
                                         Double[][] gamut) {
        if (target == null) { // todo: no mutation coverage
            return previous;
        }
        if (previous == null) { // todo: no mutation coverage
            return null;
        }

        final var previousPoints = previous.points();
        final var targetPoints = target.points();
        final int maxPoints = Math.max(previousPoints.size(), targetPoints.size());
        List<Pair<Double, Double>> points = new ArrayList<>(maxPoints);
        // API guarantees minimum 2 gradient points, so maxPoints >= 2 and division is safe
        for (int i = 0; i < maxPoints; i++) {
            double pos = (double) i / (double) (maxPoints - 1);

            var p = evalAt(previousPoints, pos);
            var t = evalAt(targetPoints, pos);

            Double x = interpolateDouble(interpolatedTime, p.first(), t.first());
            Double y = interpolateDouble(interpolatedTime, p.second(), t.second());

            XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
            points.add(Pair.of(correction.getX(), correction.getY()));
        }
        return new Gradient(points, target.mode());
    }

    /**
     * Evaluate within a polyline of xy points at normalized position in [0,1].
     */
    private static Pair<Double, Double> evalAt(List<Pair<Double, Double>> points, double position) {
        int n = points.size();

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
