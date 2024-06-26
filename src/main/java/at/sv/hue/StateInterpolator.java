package at.sv.hue;

import at.sv.hue.api.PutCall;
import at.sv.hue.color.ColorModeConverter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
public final class StateInterpolator {

    private final ScheduledState state;
    private final ScheduledStateSnapshot previousState;
    private final ZonedDateTime dateTime;
    private final boolean keepPreviousPropertiesForNullTargets;

    public PutCall getInterpolatedPutCall() {
        ZonedDateTime lastDefinedStart = state.getLastDefinedStart();
        if (lastDefinedStart.isBefore(dateTime) || lastDefinedStart.isEqual(dateTime)) {
            return null; // the state is already reached
        }
        PutCall interpolatedPutCall;
        if (isDirectlyAtStartOfState()) {
            interpolatedPutCall = previousState.getPutCall(dateTime); // directly at start, just use previous put call
        } else {
            interpolatedPutCall = interpolate();
        }
        return modifyProperties(interpolatedPutCall);
    }

    private boolean isDirectlyAtStartOfState() {
        return state.getLastStart().truncatedTo(ChronoUnit.SECONDS)
                    .isEqual(dateTime.truncatedTo(ChronoUnit.SECONDS));
    }

    private PutCall modifyProperties(PutCall interpolatedPutCall) {
        interpolatedPutCall.setOn(null); // do not per default reuse "on" property for interpolation
        if (interpolatedPutCall.isNullCall()) {
            return null; // no relevant properties set, don't perform interpolations
        }
        if (state.isOn()) {
            interpolatedPutCall.setOn(true); // the current state is turning lights on, also set "on" property for interpolated state
        }
        return interpolatedPutCall;
    }

    /**
     * P = P0 + t(P1 - P0)
     */
    private PutCall interpolate() {
        BigDecimal interpolatedTime = getInterpolatedTime();
        PutCall previous = previousState.getPutCall(dateTime);
        PutCall target = state.getPutCall(dateTime);

        return interpolate(previous, target, interpolatedTime, keepPreviousPropertiesForNullTargets);
    }

    private static PutCall interpolate(PutCall previous, PutCall target, BigDecimal interpolatedTime,
                                       boolean previousPropertiesForNullTargets) {
        convertColorModeIfNeeded(previous, target);

        previous.setBri(interpolateInteger(interpolatedTime, target.getBri(), previous.getBri(), previousPropertiesForNullTargets));
        previous.setCt(interpolateInteger(interpolatedTime, target.getCt(), previous.getCt(), previousPropertiesForNullTargets));
        previous.setHue(interpolateHue(interpolatedTime, target.getHue(), previous.getHue(), previousPropertiesForNullTargets));
        previous.setSat(interpolateInteger(interpolatedTime, target.getSat(), previous.getSat(), previousPropertiesForNullTargets));
        previous.setX(interpolateDouble(interpolatedTime, target.getX(), previous.getX(), previousPropertiesForNullTargets));
        previous.setY(interpolateDouble(interpolatedTime, target.getY(), previous.getY(), previousPropertiesForNullTargets));
        return previous;
    }

    /**
     * Returns true if the previous and target put call don't have any common properties, which means that no interpolation
     * would be possible. Here we don't care about the time differences, but just the available properties of the put calls.
     */
    public static boolean hasNoOverlappingProperties(PutCall previous, PutCall target) {
        PutCall putCall = interpolate(previous, target, BigDecimal.ONE, false);
        putCall.setOn(null); // do not reuse on property
        return putCall.isNullCall();
    }

    /**
     * t = (current_time - start_time) / (end_time - start_time)
     */
    private BigDecimal getInterpolatedTime() {
        Duration durationAfterStart = Duration.between(state.getLastStart(), dateTime);
        Duration totalDuration = Duration.between(state.getLastStart(), state.getLastDefinedStart());
        return BigDecimal.valueOf(durationAfterStart.toMillis())
                         .divide(BigDecimal.valueOf(totalDuration.toMillis()), 7, RoundingMode.HALF_UP);
    }


    private static void convertColorModeIfNeeded(PutCall previousPutCall, PutCall target) {
        ColorModeConverter.convertIfNeeded(previousPutCall, target.getColorMode());
    }

    private static Integer interpolateInteger(BigDecimal interpolatedTime, Integer target, Integer previous,
                                              boolean previousPropertiesForNullTargets) {
        if (shouldReturnPrevious(target, previousPropertiesForNullTargets)) {
            return previous;
        }
        if (target == null) {
            return null;
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

    private static Double interpolateDouble(BigDecimal interpolatedTime, Double target, Double previous,
                                            boolean previousPropertiesForNullTargets) {
        if (shouldReturnPrevious(target, previousPropertiesForNullTargets)) {
            return previous;
        }
        if (target == null) {
            return null;
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
    private static Integer interpolateHue(BigDecimal interpolatedTime, Integer target, Integer previous,
                                          boolean previousPropertiesForNullTargets) {
        if (shouldReturnPrevious(target, previousPropertiesForNullTargets)) {
            return previous;
        }
        if (target == null) {
            return null;
        }
        if (previous == null) {
            return null;
        }
        if (previous == 0 && target == ScheduledState.MAX_HUE_VALUE || previous == ScheduledState.MAX_HUE_VALUE && target == 0) {
            return 0;
        }
        int diff = ((target - previous + ScheduledState.MIDDLE_HUE_VALUE) % (ScheduledState.MAX_HUE_VALUE + 1)) - ScheduledState.MIDDLE_HUE_VALUE;
        return BigDecimal.valueOf(previous)
                         .add(interpolatedTime.multiply(BigDecimal.valueOf(diff)))
                         .setScale(0, RoundingMode.HALF_UP)
                         .intValue();
    }

    private static boolean shouldReturnPrevious(Object target, boolean previousPropertiesForNullTargets) {
        return target == null && previousPropertiesForNullTargets;
    }
}
