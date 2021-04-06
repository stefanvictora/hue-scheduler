package at.sv.hue.time;

import java.time.LocalTime;
import java.time.ZonedDateTime;

public interface SunTimesProvider {

    LocalTime getSunrise(ZonedDateTime dateTime);

    LocalTime getSunset(ZonedDateTime dateTime);

    LocalTime getNauticalStart(ZonedDateTime dateTime);

    LocalTime getNauticalEnd(ZonedDateTime dateTime);

    LocalTime getCivilStart(ZonedDateTime dateTime);

    LocalTime getCivilEnd(ZonedDateTime dateTime);

    LocalTime getGoldenHour(ZonedDateTime dateTime);

    LocalTime getBlueHour(ZonedDateTime dateTime);

    LocalTime getNightHour(ZonedDateTime dateTime);

    LocalTime getAstronomicalEnd(ZonedDateTime dateTime);

    LocalTime getAstronomicalStart(ZonedDateTime dateTime);

    default String toDebugString(ZonedDateTime dateTime) {
        return null;
    }
}
