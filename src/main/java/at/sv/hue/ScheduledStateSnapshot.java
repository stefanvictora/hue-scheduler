package at.sv.hue;

import at.sv.hue.api.PutCall;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static at.sv.hue.ScheduledState.MAX_TRANSITION_TIME_MS;

@Slf4j
@RequiredArgsConstructor
public class ScheduledStateSnapshot {
    @Getter
    private final ScheduledState scheduledState;
    @Getter
    private final ZonedDateTime definedStart;
    private final Function<ScheduledStateSnapshot, ScheduledStateSnapshot> previousStateLookup;
    private final BiFunction<ScheduledStateSnapshot, ZonedDateTime, ScheduledStateSnapshot> nextStateLookup;

    private volatile ZonedDateTime cachedStart;
    private volatile ZonedDateTime cachedEnd;
    private volatile ScheduledStateSnapshot cachedPreviousState;
    private volatile ScheduledStateSnapshot cachedNextState;

    public String getId() {
        return scheduledState.getId();
    }

    public String getContextName() {
        return scheduledState.getContextName();
    }

    public ZonedDateTime getStart() {
        if (cachedStart == null) {
            synchronized (this) {
                if (cachedStart == null) {
                    cachedStart = calculateStart();
                }
            }
        }
        return cachedStart;
    }

    private ZonedDateTime calculateStart() {
        if (hasTransitionBefore()) {
            int transitionTimeBefore = getTransitionTimeBefore();
            return definedStart.minus(transitionTimeBefore, ChronoUnit.MILLIS);
        }
        return definedStart;
    }

    private int getTransitionTimeBefore() {
        if (getPreviousState() == null || getPreviousState().isNullState() || hasNoOverlappingProperties()) {
            return 0;
        }
        if (scheduledState.hasTransitionTimeBeforeString()) {
            return scheduledState.parseTransitionTimeBeforeString(definedStart);
        } else {
            return getTimeUntilPreviousState();
        }
    }

    private boolean hasNoOverlappingProperties() {
        PutCalls previousPutCalls = getPreviousState().getFullPicturePutCalls(null);
        PutCalls putCalls = getPutCallsIgnoringTransition();
        return previousPutCalls.allMatch(putCalls, StateInterpolator::hasNoOverlappingProperties);
    }

    private int getTimeUntilPreviousState() {
        return (int) Duration.between(getPreviousState().getDefinedStart(), definedStart).toMillis();
    }

    public ScheduledStateSnapshot getPreviousState() {
        if (cachedPreviousState == null) {
            synchronized (this) {
                if (cachedPreviousState == null) {
                    cachedPreviousState = previousStateLookup.apply(this);
                }
            }
        }
        return cachedPreviousState;
    }

    public ZonedDateTime getEnd() {
        if (cachedEnd == null) {
            synchronized (this) {
                if (cachedEnd == null) {
                    cachedEnd = calculateEnd();
                }
            }
        }
        return cachedEnd;
    }

    private ZonedDateTime calculateEnd() {
        return new EndTimeCalculator(this, getNextState()).calculateAndGetEndTime();
    }

    public ScheduledStateSnapshot getNextState() {
        if (cachedNextState == null) {
            synchronized (this) {
                if (cachedNextState == null) {
                    cachedNextState = nextStateLookup.apply(this, definedStart);
                }
            }
        }
        return cachedNextState;
    }

    public void overwriteEnd(ZonedDateTime newEnd) {
        cachedEnd = newEnd;
    }

    public boolean isCurrentlyActive(ZonedDateTime now) {
        return (getStart().isBefore(now) || getStart().isEqual(now)) && getEnd().isAfter(now);
    }

    public boolean hasGapBefore() {
        ScheduledStateSnapshot previousState = getPreviousState();
        return previousState != null && previousState.isNullState();
    }

    public boolean hasTransitionBefore() {
        return scheduledState.hasTransitionBefore();
    }

    public Integer getDefinedTransitionTime() {
        return scheduledState.getDefinedTransitionTime();
    }

    public boolean isOn() {
        return scheduledState.isOn();
    }

    public boolean isOff() {
        return scheduledState.isOff();
    }

    public boolean isForced() {
        return scheduledState.isForced();
    }

    public boolean isNullState() {
        return scheduledState.isNullState();
    }

    public boolean isTemporary() {
        return scheduledState.isTemporary();
    }

    public boolean isGroupState() {
        return scheduledState.isGroupState();
    }

    public boolean hasOtherPropertiesThanOn() {
        return scheduledState.hasOtherPropertiesThanOn();
    }

    public ScheduledStateSnapshot getNextDaySnapshot(ZonedDateTime now) {
        return scheduledState.getSnapshot(getNextDefinedStart(now));
    }

    private ZonedDateTime getNextDefinedStart(ZonedDateTime now) {
        ZonedDateTime nextDefinedStart = calculateNextDefinedStart(now);
        if (shouldScheduleNextDay(nextDefinedStart, now)) {
            return calculateNextDefinedStart(now.plusDays(1));
        } else {
            return nextDefinedStart;
        }
    }

    /**
     * Returns the next defined start after the last one, starting from the given dateTime.
     * We loop over getDefinedStart with increased days until we find the first start that is after the last one.
     */
    private ZonedDateTime calculateNextDefinedStart(ZonedDateTime dateTime) {
        ZonedDateTime next = scheduledState.getDefinedStart(dateTime);
        while (next.isBefore(definedStart) || next.equals(definedStart)) {
            dateTime = dateTime.plusDays(1);
            next = scheduledState.getDefinedStart(dateTime);
        }
        return next;
    }

    private boolean shouldScheduleNextDay(ZonedDateTime nextDefinedStart, ZonedDateTime now) {
        ScheduledStateSnapshot nextState = nextStateLookup.apply(this, nextDefinedStart);
        return nextState.getStart().isBefore(now) || nextState.getStart().isEqual(now);
    }

    public boolean isInsideSplitCallWindow(ZonedDateTime now) {
        return getNextTransitionTimeSplitStart(now).isBefore(definedStart);
    }

    public ZonedDateTime calculateNextPowerTransitionEnd(ZonedDateTime now) {
        if (isInsideSplitCallWindow(now)) {
            return getNextTransitionTimeSplitStart(now).minusSeconds(1);
        } else if (isOff() && hasTransitionBefore()) {
            return getDefinedStart().minusSeconds(1);
        } else {
            return null; // will be calculated on demand
        }
    }

    public long getNextInterpolationSplitDelayInMs(ZonedDateTime now) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now);
        return Duration.between(now, nextSplitStart).toMillis();
    }

    private ZonedDateTime getNextTransitionTimeSplitStart(ZonedDateTime now) {
        ZonedDateTime splitStart = getStart().plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS); // directly start with next
        while (splitStart.isBefore(now) || splitStart.isEqual(now)) {
            splitStart = splitStart.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS);
        }
        return splitStart;
    }

    public int getRequiredGap() {
        return scheduledState.getRequiredGap();
    }

    public boolean isTriggeredByPowerTransition() {
        return scheduledState.isTriggeredByPowerTransition();
    }

    public PutCalls getPutCalls(ZonedDateTime now) {
        return scheduledState.getPutCalls(now, definedStart);
    }

    private PutCalls getPutCallsIgnoringTransition() {
        return scheduledState.getPutCalls(null, null);
    }

    public PutCalls getInterpolatedFullPicturePutCalls(ZonedDateTime now) {
        PutCalls putCalls = getInterpolatedPutCallsIfNeeded(now);
        if (putCalls != null) {
            return putCalls;
        } else {
            return getFullPicturePutCalls(now);
        }
    }

    public PutCalls getFullPicturePutCalls(ZonedDateTime now) {
        if (isNullState()) {
            return null;
        }
        PutCalls putCalls = getPutCalls(now);
        return putCalls.map(putCall -> {
            if (putCall.getOn() == Boolean.FALSE) {
                return putCall;
            }
            ScheduledStateSnapshot previousState = this;
            while (putCall.getBri() == null || putCall.getColorMode() == ColorMode.NONE) { // stop as soon as we have brightness and color mode
                previousState = previousState.getPreviousState();
                if (previousState == null || isSameState(previousState) || previousState.isNullState()) {
                    break;
                }
                PutCalls previousPutCalls = previousState.getPutCallsIgnoringTransition();
                PutCall previousPutCall;
                if (previousPutCalls.isGeneralGroup()) {
                    previousPutCall = previousPutCalls.getFirst();
                } else if (putCalls.isGeneralGroup()) { // optimization
                    break; // we cannot map specific lights to a general group
                } else {
                    previousPutCall = previousPutCalls.get(putCall.getId());
                    if (previousPutCall == null) {
                        break; // no previous put call found, stop here
                    }
                }
                if (putCall.getBri() == null) {
                    putCall.setBri(previousPutCall.getBri());
                }
                if (shouldCopyEffect(putCall)) {
                    putCall.setEffect(previousPutCall.getEffect());
                }
                if (shouldParameteriseEffect(putCall)) {
                    Effect effect = putCall.getEffect();
                    if (hasNoColorProperties(effect)) {
                        Effect.EffectBuilder builder = effect.toBuilder();
                        builder.x(previousPutCall.getX());
                        builder.y(previousPutCall.getY());
                        builder.ct(previousPutCall.getCt());
                        putCall.setEffect(builder.build());
                    }
                } else if (putCall.getColorMode() == ColorMode.NONE) {
                    putCall.setCt(previousPutCall.getCt());
                    putCall.setX(previousPutCall.getX());
                    putCall.setY(previousPutCall.getY());
                    putCall.setGradient(previousPutCall.getGradient());
                }
            }
            return putCall;
        });
    }

    private static boolean shouldCopyEffect(PutCall putCall) {
        return putCall.getEffect() == null && putCall.getGradient() == null; // effects are not compatible with gradients
    }

    private static boolean shouldParameteriseEffect(PutCall putCall) {
        return putCall.getEffect() != null && !putCall.getEffect().isNone();
    }

    private static boolean hasNoColorProperties(Effect effect) {
        return effect.x() == null && effect.y() == null && effect.ct() == null;
    }

    public PutCalls getNextInterpolatedSplitPutCalls(ZonedDateTime now) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now).minusMinutes(getRequiredGap()); // add buffer;
        Duration between = Duration.between(now, nextSplitStart);
        if (between.isZero() || between.isNegative()) {
            return null; // we are inside the required gap, skip split call;
        }
        PutCalls interpolatedSplitPutCalls = getInterpolatedPutCallsIfNeeded(nextSplitStart);
        if (interpolatedSplitPutCalls == null) {
            return null; // no interpolation possible; todo: write test or remove if not needed anymore
        }
        interpolatedSplitPutCalls.setTransitionTime((int) between.toMillis() / 100);
        return interpolatedSplitPutCalls;
    }

    public PutCalls getInterpolatedPutCallsIfNeeded(ZonedDateTime dateTime) {
        if (!hasTransitionBefore()) {
            return null;
        }
        ScheduledStateSnapshot previousState = getPreviousState();
        if (previousState == null) {
            return null;
        }
        return new StateInterpolator(this, previousState, dateTime).getInterpolatedPutCalls();
    }

    public boolean performsInterpolation(ZonedDateTime now) {
        return getInterpolatedPutCallsIfNeeded(now) != null;
    }

    public ZonedDateTime getNextSignificantPropertyChangeTime(PutCall currentPutCall, ZonedDateTime now, int brightnessThreshold,
                                                              int colorTemperatureThresholdKelvin, double colorThreshold) {
        if (!hasTransitionBefore()) {
            return null;
        }
        ScheduledStateSnapshot previousState = getPreviousState();
        if (previousState == null) {
            return null;
        }
        if (isAlreadyReached(now)) {
            return null; // the state is already reached
        }
        if (currentPutCall == null) {
            currentPutCall = getInterpolatedFullPicturePutCall(now);
        }
        ZonedDateTime nextTime = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime endTime = getDefinedStart();
        while (nextTime.isBefore(endTime)) {
            StateInterpolator futureInterpolator = new StateInterpolator(this, previousState, nextTime, true);
            PutCall future = futureInterpolator.getInterpolatedPutCall();
            if (future != null && currentPutCall.hasNotSimilarLightState(future, brightnessThreshold,
                    colorTemperatureThresholdKelvin, colorThreshold)) {
                log.trace("Next property change in {}: {}. Current: {}",
                        Duration.between(now, nextTime), future, currentPutCall);
                return nextTime;
            }
            nextTime = nextTime.plusMinutes(1);
        }
        return endTime; // Ensure last update at the end time
    }

    public void recordLastPutCalls(PutCalls putCalls) {
        scheduledState.setLastPutCalls(putCalls);
    }

    public void recordLastSeen(ZonedDateTime lastSeen) {
        scheduledState.setLastSeen(lastSeen);
    }

    public boolean isNotSameState(ScheduledState state) {
        return !isSameState(state);
    }

    public boolean isSameState(ScheduledState state) {
        return scheduledState.isSameState(state);
    }

    public boolean isSameState(ScheduledStateSnapshot state) {
        return isSameState(state.getScheduledState());
    }

    /**
     * Returns the milliseconds until the state should be scheduled based on the previously calculated start.
     *
     * @param now the current time
     * @return the delay in ms until this state should be scheduled, not negative.
     * Returning 0 if it should be directly scheduled.
     */
    public long getDelayUntilStart(ZonedDateTime now) {
        Duration between = Duration.between(now, getStart());
        if (between.isNegative()) {
            return 0;
        } else {
            return between.toMillis();
        }
    }

    public boolean endsBefore(ZonedDateTime now) {
        return now.isAfter(getEnd());
    }

    public boolean isAlreadyReached(ZonedDateTime now) {
        ZonedDateTime definedStart = getDefinedStart();
        return definedStart.isBefore(now) || definedStart.isEqual(now);
    }

    public boolean isScheduledOn(ZonedDateTime day) {
        return isScheduledOn(DayOfWeek.from(day));
    }

    public boolean isScheduledOn(DayOfWeek... day) {
        return scheduledState.isScheduledOn(day);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ScheduledStateSnapshot that = (ScheduledStateSnapshot) o;
        return scheduledState.equals(that.scheduledState) && definedStart.equals(that.definedStart);
    }

    @Override
    public int hashCode() {
        int result = scheduledState.hashCode();
        result = 31 * result + definedStart.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return scheduledState.getFormattedName() +
               " {" +
               "start=" + getFormattedStart() +
               ", end=" + getFormattedEnd() +
               ", " + scheduledState.getFormattedProperties() +
               "}";
    }

    private String getFormattedStart() {
        if (cachedStart != null) {
            return scheduledState.getStartString() + " (" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(cachedStart) + ")";
        } else {
            return scheduledState.getStartString();
        }
    }

    private String getFormattedEnd() {
        return getEnd().toLocalDateTime().toString();
    }
}
