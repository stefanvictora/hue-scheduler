package at.sv.hue.time;

import java.time.ZonedDateTime;

public interface SunTimesProvider {

    ZonedDateTime getSunrise(ZonedDateTime dateTime);

    ZonedDateTime getNoon(ZonedDateTime dateTime);

    ZonedDateTime getSunset(ZonedDateTime dateTime);

    ZonedDateTime getNauticalStart(ZonedDateTime dateTime);

    ZonedDateTime getNauticalEnd(ZonedDateTime dateTime);

    ZonedDateTime getCivilStart(ZonedDateTime dateTime);

    ZonedDateTime getCivilEnd(ZonedDateTime dateTime);

    ZonedDateTime getGoldenHour(ZonedDateTime dateTime);

    ZonedDateTime getBlueHour(ZonedDateTime dateTime);

    ZonedDateTime getNightHour(ZonedDateTime dateTime);

    ZonedDateTime getAstronomicalEnd(ZonedDateTime dateTime);

    ZonedDateTime getAstronomicalStart(ZonedDateTime dateTime);

    default String toDebugString(ZonedDateTime dateTime) {
        return null;
    }
}
