package at.sv.hue;

import java.time.LocalTime;
import java.time.ZonedDateTime;

public interface SunDataProvider {

    LocalTime getSunrise(ZonedDateTime dateTime);

    LocalTime getSunset(ZonedDateTime dateTime);

    LocalTime getNauticalStart(ZonedDateTime dateTime);

    LocalTime getNauticalEnd(ZonedDateTime dateTime);

    LocalTime getCivilStart(ZonedDateTime dateTime);

    LocalTime getCivilEnd(ZonedDateTime dateTime);

    LocalTime getGoldenHour(ZonedDateTime dateTime);

    LocalTime getAstronomicalEnd(ZonedDateTime dateTime);

    LocalTime getAstronomicalStart(ZonedDateTime dateTime);

    String toDebugString(ZonedDateTime dateTime);
}
