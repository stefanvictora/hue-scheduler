package at.sv.hue;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

public final class EndTimeAdjuster {

    private final StatesOfDayProvider statesOfDayProvider;
    private final ScheduledState state;
    private final ZonedDateTime day;

    public EndTimeAdjuster(ScheduledState state, ZonedDateTime day, StatesOfDayProvider statesOnDayProvider) {
        this.state = state;
        this.day = day;
        this.statesOfDayProvider = statesOnDayProvider;
    }

    public void calculateAndSetEndTime() {
        List<ScheduledState> statesOnDay = getStatesOnDay();
        int index = statesOnDay.indexOf(state);
        if (isLastState(statesOnDay, index)) {
            if (isAlsoScheduledTheDayAfter()) {
                setEndToStartOfFirstStateTheDayAfter();
            } else {
                setEndToEndOfDay();
            }
        } else {
            setEndToStartOfFollowingState(statesOnDay.get(index + 1));
        }
    }

    private List<ScheduledState> getStatesOnDay() {
        return getStatesOnDay(day);
    }

    private List<ScheduledState> getStatesOnDay(ZonedDateTime day) {
        return statesOfDayProvider.getStatesOnDay(day);
    }

    private boolean isLastState(List<ScheduledState> states, int index) {
        return index + 1 >= states.size();
    }

    private boolean isAlsoScheduledTheDayAfter() {
        return state.isScheduledOn(day.plusDays(1));
    }

    private void setEndToStartOfFirstStateTheDayAfter() {
        List<ScheduledState> statesTheDayAfter = getStatesOnDay(day.plusDays(1));
        state.setEnd(statesTheDayAfter.get(0).getStart(day.plusDays(1)).minusSeconds(1));
    }

    private void setEndToEndOfDay() {
        state.setEnd(day.with(LocalTime.MIDNIGHT.minusSeconds(1)));
    }

    private void setEndToStartOfFollowingState(ScheduledState followingState) {
        state.setEnd(followingState.getStart(day).minusSeconds(1));
    }

    public interface StatesOfDayProvider {
        List<ScheduledState> getStatesOnDay(ZonedDateTime day);
    }
}
