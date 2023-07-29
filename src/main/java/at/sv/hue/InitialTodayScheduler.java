package at.sv.hue;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class InitialTodayScheduler {
    private final ZonedDateTime now;
    private final StatesForDayProvider statesForDayProvider;
    private final Consumer<ScheduledState> schedule;
    private final Consumer<ScheduledState> scheduleNextDay;
    private final List<ScheduledState> todaysStates;

    public InitialTodayScheduler(ZonedDateTime now, StatesForDayProvider statesForDayProvider,
                                 Consumer<ScheduledState> schedule, Consumer<ScheduledState> scheduleNextDay) {
        this.now = now;
        this.statesForDayProvider = statesForDayProvider;
        this.schedule = schedule;
        this.scheduleNextDay = scheduleNextDay;
        todaysStates = sortByLatestStartTimeFirst(statesForDayProvider.getStatesOnDay(now));
    }

    private List<ScheduledState> sortByLatestStartTimeFirst(List<ScheduledState> states) {
        states.sort(Comparator.comparing(scheduledState -> scheduledState.getStart(now), Comparator.reverseOrder()));
        return states;
    }

    public void scheduleStatesStartingToday() {
        if (hasNoStatesToday()) return;
        if (allInTheFuture()) {
            scheduleTemporaryCopyOfLatestStateImmediately();
        }
        for (int i = 0; i < todaysStates.size(); i++) {
            ScheduledState state = todaysStates.get(i);
            schedule.accept(state);
            if (state.isInThePastOrNow(now) && hasMorePastStates(i)) {
                addRemainingTodayStatesTheNextDay(i);
                break;
            }
        }
    }

    private boolean hasNoStatesToday() {
        return todaysStates.isEmpty();
    }

    private boolean allInTheFuture() {
        return !getEarliestStateToday().isInThePastOrNow(now);
    }

    private ScheduledState getEarliestStateToday() {
        return todaysStates.get(todaysStates.size() - 1);
    }

    private void scheduleTemporaryCopyOfLatestStateImmediately() {
        List<ScheduledState> statesScheduledBothTodayAndYesterday = getStatesScheduledBothTodayAndYesterday();
        if (statesScheduledBothTodayAndYesterday.isEmpty()) return;
        schedule.accept(createTemporaryCopyOfLatestState(statesScheduledBothTodayAndYesterday));
    }

    private ScheduledState createTemporaryCopyOfLatestState(List<ScheduledState> statesScheduledBothTodayAndYesterday) {
        ScheduledState latestState = sortByLatestStartTimeFirst(statesScheduledBothTodayAndYesterday).get(0);
        return ScheduledState.createTemporaryCopy(latestState, now, getRightBeforeStartOfEarliestStateToday());
    }

    private ZonedDateTime getRightBeforeStartOfEarliestStateToday() {
        return getEarliestStateToday().getStart(now).minusSeconds(1);
    }

    private List<ScheduledState> getStatesScheduledBothTodayAndYesterday() {
        DayOfWeek today = DayOfWeek.from(now);
        return statesForDayProvider.getStatesOnDay(now, today, today.minus(1));
    }

    private boolean hasMorePastStates(int i) {
        return todaysStates.size() > i + 1;
    }

    private void addRemainingTodayStatesTheNextDay(int i) {
        getRemainingToday(i).forEach(scheduleNextDay);
    }

    private List<ScheduledState> getRemainingToday(int i) {
        return todaysStates.subList(i + 1, todaysStates.size());
    }
}
