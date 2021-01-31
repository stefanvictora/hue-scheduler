package at.sv.hue;

import java.time.LocalTime;

public interface StartTimeProvider {
    LocalTime getStart(String input);
}
