package at.sv.hue;

import at.sv.hue.api.PutCall;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static at.sv.hue.ScheduledState.MAX_TRANSITION_TIME_MS;

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
        PutCall previousPutCall = getPreviousState().getFullPicturePutCall(null);
        return StateInterpolator.hasNoOverlappingProperties(previousPutCall, getPutCallIgnoringTransition());
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

    public ZonedDateTime calculateNextPowerOnEnd(ZonedDateTime now) {
        if (isInsideSplitCallWindow(now)) {
            return getNextTransitionTimeSplitStart(now).minusSeconds(1);
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

    public boolean isRetryAfterPowerOnState() {
        return scheduledState.isRetryAfterPowerOnState();
    }

    public PutCall getPutCall(ZonedDateTime now) {
        return scheduledState.getPutCall(now, definedStart);
    }

    private PutCall getPutCallIgnoringTransition() {
        return scheduledState.getPutCall(null, null);
    }

    public PutCall getInterpolatedFullPicturePutCall(ZonedDateTime now) {
        PutCall putCall = getInterpolatedPutCallIfNeeded(now);
        if (putCall != null) {
            return putCall;
        } else {
            return getFullPicturePutCall(now);
        }
    }

    public PutCall getFullPicturePutCall(ZonedDateTime now) {
        if (isNullState()) {
            return null;
        }
        PutCall putCall = getPutCall(now);
        if (putCall.getOn() == Boolean.FALSE) {
            return putCall;
        }
        ScheduledStateSnapshot previousState = this;
        while (putCall.getBri() == null || putCall.getColorMode() == ColorMode.NONE) { // stop as soon as we have brightness and color mode
            previousState = previousState.getPreviousState();
            if (previousState == null || isSameState(previousState) || previousState.isNullState()) {
                break;
            }
            PutCall previousPutCall = previousState.getPutCallIgnoringTransition();
            if (putCall.getBri() == null) {
                putCall.setBri(previousPutCall.getBri());
            }
            if (putCall.getColorMode() == ColorMode.NONE) {
                putCall.setCt(previousPutCall.getCt());
                putCall.setHue(previousPutCall.getHue());
                putCall.setSat(previousPutCall.getSat());
                putCall.setX(previousPutCall.getX());
                putCall.setY(previousPutCall.getY());
            }
        }
        return putCall;
    }

    public PutCall getInterpolatedPutCallIfNeeded(ZonedDateTime now) {
        return getInterpolatedPutCallIfNeeded(now, true);
    }

    public PutCall getNextInterpolatedSplitPutCall(ZonedDateTime now) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now).minusMinutes(getRequiredGap()); // add buffer;
        Duration between = Duration.between(now, nextSplitStart);
        if (between.isZero() || between.isNegative()) {
            return null; // we are inside the required gap, skip split call;
        }
        PutCall interpolatedSplitPutCall = getInterpolatedPutCallIfNeeded(nextSplitStart, false);
        if (interpolatedSplitPutCall == null) {
            return null; // no interpolation possible; todo: write test or remove if not needed anymore
        }
        interpolatedSplitPutCall.setTransitionTime((int) between.toMillis() / 100);
        return interpolatedSplitPutCall;
    }

    private PutCall getInterpolatedPutCallIfNeeded(ZonedDateTime dateTime, boolean keepPreviousPropertiesForNullTargets) {
        if (!hasTransitionBefore()) {
            return null;
        }
        ScheduledStateSnapshot previousState = getPreviousState();
        if (previousState == null) {
            return null;
        }
        return new StateInterpolator(this, previousState, dateTime, keepPreviousPropertiesForNullTargets)
                .getInterpolatedPutCall();
    }

    public boolean performsInterpolation(ZonedDateTime now) {
        return getInterpolatedPutCallIfNeeded(now) != null;
    }

    public void recordLastPutCall(PutCall putCall) {
        scheduledState.setLastPutCall(putCall);
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
