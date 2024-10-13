package at.sv.hue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public final class EndTimeCalculator {

    private final ScheduledStateSnapshot state;
    private final ScheduledStateSnapshot nextState;

    public EndTimeCalculator(ScheduledStateSnapshot state, ScheduledStateSnapshot nextState) {
        this.state = state;
        this.nextState = nextState;
    }

    public ZonedDateTime calculateAndGetEndTime() {
        if (state.isScheduledOn(nextState.getStart()) && isTodayOrNextDay(nextState)) {
            return getEndAsStartOfNextState();
        } else {
            return getEndAsEndOfDay();
        }
    }

    private boolean isTodayOrNextDay(ScheduledStateSnapshot nextState) {
        return Duration.between(state.getDefinedStart().truncatedTo(ChronoUnit.DAYS),
                nextState.getStart().truncatedTo(ChronoUnit.DAYS)).toDays() <= 1;
    }

    private ZonedDateTime getEndAsStartOfNextState() {
        return nextState.getStart().minusSeconds(1);
    }

    private ZonedDateTime getEndAsEndOfDay() {
        return state.getDefinedStart().plusDays(1).truncatedTo(ChronoUnit.DAYS).minusSeconds(1);
    }
}
