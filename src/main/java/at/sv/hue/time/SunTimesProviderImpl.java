package at.sv.hue.time;

import org.shredzone.commons.suncalc.SunTimes;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class SunTimesProviderImpl implements SunTimesProvider {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SunTimes.Parameters parameters;

    public SunTimesProviderImpl(double lat, double lng, double height) {
        parameters = SunTimes.compute().at(lat, lng).height(height);
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
    public LocalTime getBlueHour(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.BLUE_HOUR).getSet().toLocalTime();
    }

    @Override
    public LocalTime getNightHour(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.NIGHT_HOUR).getSet().toLocalTime();
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
        return "astronomical dawn: " + format(getAstronomicalStart(dateTime)) +
                "\nnautical dawn: " + format(getNauticalStart(dateTime)) +
                "\ncivil dawn: " + format(getCivilStart(dateTime)) +
                "\nsunrise: " + format(getSunrise(dateTime)) +
                "\ngolden hour: " + format(getGoldenHour(dateTime)) +
                "\nsunset: " + format(getSunset(dateTime)) +
                "\nblue hour: " + format(getBlueHour(dateTime)) +
                "\ncivil dusk: " + format(getCivilEnd(dateTime)) +
                "\nnight hour: " + format(getNightHour(dateTime)) +
                "\nnautical dusk: " + format(getNauticalEnd(dateTime)) +
                "\nastronomical dusk: " + format(getAstronomicalEnd(dateTime)) +
                "";
    }

    private String format(LocalTime time) {
        return TIME_FORMATTER.format(time);
    }
}
