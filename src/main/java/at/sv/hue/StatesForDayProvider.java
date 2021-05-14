package at.sv.hue;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.List;

public interface StatesForDayProvider {
    default List<ScheduledState> getStatesOnDay(ZonedDateTime day) {
        return getStatesOnDay(day, DayOfWeek.from(day));
    }

    List<ScheduledState> getStatesOnDay(ZonedDateTime day, DayOfWeek... days);
}
