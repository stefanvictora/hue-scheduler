package at.sv.hue.time;

import java.time.ZonedDateTime;

public interface StartTimeProvider {
    /**
     * @param input    a ISO_LOCAL_TIME formatted string, or a sun keyword with optional offset
     * @param dateTime the date to use a reference for resolving sun times
     * @return the start time corresponding to the input and dateTime
     * @throws InvalidStartTimeExpression if the input is neither a valid {@link java.time.format.DateTimeFormatter#ISO_LOCAL_TIME}
     *                                    or a supported sun keyword with optional offset.
     */
    ZonedDateTime getStart(String input, ZonedDateTime dateTime);

    default String toDebugString(ZonedDateTime dateTime) {
        return null;
    }
}
