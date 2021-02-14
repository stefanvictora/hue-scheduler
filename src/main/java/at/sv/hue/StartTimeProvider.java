package at.sv.hue;

import java.time.LocalTime;
import java.time.ZonedDateTime;

public interface StartTimeProvider {
    LocalTime getStart(String input, ZonedDateTime dateTime);

    default String toDebugString(ZonedDateTime dateTime) {
        return null;
    }
}
