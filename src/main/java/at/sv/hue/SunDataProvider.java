package at.sv.hue;

import java.time.LocalTime;

public interface SunDataProvider {

    LocalTime getSunrise();

    LocalTime getSunset();

    LocalTime getNauticalStart();

    LocalTime getNauticalEnd();

    LocalTime getCivilStart();

    LocalTime getCivilEnd();

    LocalTime getGoldenHour();

    LocalTime getAstronomicalEnd();

    LocalTime getAstronomicalStart();
}
