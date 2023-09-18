package at.sv.hue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public final class EndTimeAdjuster {

    private final ScheduledState state;
    private final ScheduledStateSnapshot nextState;
    private final ZonedDateTime definedStart;

    public EndTimeAdjuster(ScheduledState state, ScheduledStateSnapshot nextState, ZonedDateTime definedStart) {
        this.state = state;
        this.nextState = nextState;
        this.definedStart = definedStart;
    }

    public void calculateAndSetEndTime() {
        if (state.isScheduledOn(nextState.getStart()) && isTodayOrNextDay(nextState)) {
            setEndToStartOfNextState(nextState);
        } else {
            setEndToEndOfDay();
        }
    }

    private boolean isTodayOrNextDay(ScheduledStateSnapshot nextState) {
        return Duration.between(definedStart.truncatedTo(ChronoUnit.DAYS),
                nextState.getStart().truncatedTo(ChronoUnit.DAYS)).toDays() <= 1;
    }

    private void setEndToStartOfNextState(ScheduledStateSnapshot nextState) {
        state.setEnd(nextState.getStart().minusSeconds(1));
    }

    private void setEndToEndOfDay() {
        state.setEnd(definedStart.plusDays(1).truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    }
}
