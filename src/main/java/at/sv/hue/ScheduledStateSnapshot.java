package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.PutCall;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static at.sv.hue.ScheduledState.MAX_TRANSITION_TIME_MS;

@RequiredArgsConstructor
public class ScheduledStateSnapshot {
    @Getter
    private final ScheduledState scheduledState;
    @Getter
    private final ZonedDateTime definedStart;

    private ZonedDateTime cachedStart;
    @Setter
    @Getter
    private ScheduledStateSnapshot previousState;
    @Setter
    @Getter
    private ZonedDateTime end;

    public String getId() {
        return scheduledState.getId();
    }

    public String getContextName() {
        return scheduledState.getContextName();
    }

    public ZonedDateTime getStart() {
        if (cachedStart == null) {
            cachedStart = scheduledState.getStart(definedStart);
        }
        return cachedStart;
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

    public ZonedDateTime getNextDefinedStart(ZonedDateTime dateTime) {
        return scheduledState.getNextDefinedStart(dateTime, definedStart);
    }

    public boolean isSplitState() {
        return Duration.between(getStart(), definedStart).compareTo(Duration.ofMillis(MAX_TRANSITION_TIME_MS)) > 0;
    }

    public boolean isInsideSplitCallWindow(ZonedDateTime now) {
        return getNextTransitionTimeSplitStart(now).isBefore(definedStart);
    }

    public ZonedDateTime calculateNextPowerOnEnd(ZonedDateTime now) {
        if (isInsideSplitCallWindow(now)) {
            return getNextTransitionTimeSplitStart(now).minusSeconds(1);
        } else {
            return end;
        }
    }

    public long getNextInterpolationSplitDelayInMs(ZonedDateTime now) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now);
        return Duration.between(now, nextSplitStart).toMillis();
    }

    public ZonedDateTime getNextTransitionTimeSplitStart(ZonedDateTime now) {
        ZonedDateTime splitStart = getStart().plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS);
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

    public LightCapabilities getCapabilities() {
        return scheduledState.getCapabilities();
    }

    public void recordLastPutCall(PutCall putCall) {
        scheduledState.setLastPutCall(putCall);
    }

    public void recordLastSeen(ZonedDateTime lastSeen) {
        scheduledState.setLastSeen(lastSeen);
    }

    public boolean isNotSameState(ScheduledStateSnapshot state) {
        return scheduledState.isNotSameState(state.getScheduledState());
    }

    public boolean isSameState(ScheduledState state) {
        return scheduledState.isSameState(state);
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
        return now.isAfter(end);
    }

    public boolean isScheduledOn(ZonedDateTime day) {
        return scheduledState.isScheduledOn(day);
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
               "start=" + scheduledState.getFormattedStart(definedStart) +
               ", end=" + getFormattedEnd() +
               ", " + scheduledState.getFormattedProperties() +
               "}";
    }

    private String getFormattedEnd() {
        if (end == null) return "<ERROR: not set>";
        return end.toLocalDateTime().toString();
    }
}
