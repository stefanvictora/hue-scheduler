package at.sv.hue;

import org.shredzone.commons.suncalc.SunTimes;

import java.time.LocalTime;
import java.time.ZonedDateTime;

public class SunDataProviderImpl implements SunDataProvider {

    private final SunTimes.Parameters parameters;

    public SunDataProviderImpl(double lat, double lng) {
        parameters = SunTimes.compute().at(lat, lng);
    }

    @Override
    public LocalTime getSunrise(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime).getRise().toLocalTime();
    }

    @Override
    public LocalTime getSunset(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime).getSet().toLocalTime();
    }

    @Override
    public LocalTime getNauticalStart(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.NAUTICAL).getRise().toLocalTime();
    }

    @Override
    public LocalTime getNauticalEnd(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.NAUTICAL).getSet().toLocalTime();
    }

    @Override
    public LocalTime getCivilStart(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.CIVIL).getRise().toLocalTime();
    }

    @Override
    public LocalTime getCivilEnd(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.CIVIL).getSet().toLocalTime();
    }

    @Override
    public LocalTime getGoldenHour(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.GOLDEN_HOUR).getSet().toLocalTime();
    }

    @Override
    public LocalTime getAstronomicalEnd(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.ASTRONOMICAL).getSet().toLocalTime();
    }

    @Override
    public LocalTime getAstronomicalStart(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.ASTRONOMICAL).getRise().toLocalTime();
    }

    private SunTimes sunTimesFor(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.VISUAL);
    }

    private SunTimes sunTimesFor(ZonedDateTime dateTime, SunTimes.Twilight twilight) {
        return getProviderFor(dateTime).twilight(twilight).execute();
    }

    private SunTimes.Parameters getProviderFor(ZonedDateTime dateTime) {
        return parameters.on(dateTime);
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return "\nastronomical start: " + getAstronomicalStart(dateTime) +
                "\nnautical start: " + getNauticalStart(dateTime) +
                "\ncivil start: " + getCivilStart(dateTime) +
                "\nsunrise: " + getSunrise(dateTime) +
                "\ngolden hour: " + getGoldenHour(dateTime) +
                "\nsunset: " + getSunset(dateTime) +
                "\ncivil end: " + getCivilEnd(dateTime) +
                "\nnautical end: " + getNauticalEnd(dateTime) +
                "\nastronomical end: " + getAstronomicalEnd(dateTime) +
                "";
    }
}
