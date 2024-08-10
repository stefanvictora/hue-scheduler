package at.sv.hue;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public final class EndTimeAdjuster {

    private final ScheduledStateSnapshot state;
    private final ScheduledStateSnapshot nextState;

    public EndTimeAdjuster(ScheduledStateSnapshot state, ScheduledStateSnapshot nextState) {
        this.state = state;
        this.nextState = nextState;
    }

    public void calculateAndSetEndTime() {
        if (state.isScheduledOn(nextState.getStart()) && isTodayOrNextDay(nextState)) {
            setEndToStartOfNextState(nextState);
        } else {
            setEndToEndOfDay();
        }
    }

    private boolean isTodayOrNextDay(ScheduledStateSnapshot nextState) {
        return Duration.between(state.getDefinedStart().truncatedTo(ChronoUnit.DAYS),
                nextState.getStart().truncatedTo(ChronoUnit.DAYS)).toDays() <= 1;
    }

    private void setEndToStartOfNextState(ScheduledStateSnapshot nextState) {
        state.setEnd(nextState.getStart().minusSeconds(1));
    }

    private void setEndToEndOfDay() {
        state.setEnd(state.getDefinedStart().plusDays(1).truncatedTo(ChronoUnit.DAYS).minusSeconds(1));
    }
}
