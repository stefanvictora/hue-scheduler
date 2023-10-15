package at.sv.hue.time;

import org.shredzone.commons.suncalc.SunTimes;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class SunTimesProviderImpl implements SunTimesProvider {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final double lat;
    private final double lng;
    private final double height;

    public SunTimesProviderImpl(double lat, double lng, double height) {
        this.lat = lat;
        this.lng = lng;
        this.height = height;
    }

    @Override
    public ZonedDateTime getSunrise(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime).getRise();
    }

    @Override
    public ZonedDateTime getNoon(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime).getNoon();
    }

    @Override
    public ZonedDateTime getSunset(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime).getSet();
    }

    @Override
    public ZonedDateTime getNauticalStart(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.NAUTICAL).getRise();
    }

    @Override
    public ZonedDateTime getNauticalEnd(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.NAUTICAL).getSet();
    }

    @Override
    public ZonedDateTime getCivilStart(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.CIVIL).getRise();
    }

    @Override
    public ZonedDateTime getCivilEnd(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.CIVIL).getSet();
    }

    @Override
    public ZonedDateTime getGoldenHour(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.GOLDEN_HOUR).getSet();
    }

    @Override
    public ZonedDateTime getBlueHour(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.BLUE_HOUR).getSet();
    }

    @Override
    public ZonedDateTime getNightHour(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.NIGHT_HOUR).getSet();
    }

    @Override
    public ZonedDateTime getAstronomicalEnd(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.ASTRONOMICAL).getSet();
    }

    @Override
    public ZonedDateTime getAstronomicalStart(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.ASTRONOMICAL).getRise();
    }

    private SunTimes sunTimesFor(ZonedDateTime dateTime) {
        return sunTimesFor(dateTime, SunTimes.Twilight.VISUAL);
    }

    private SunTimes sunTimesFor(ZonedDateTime dateTime, SunTimes.Twilight twilight) {
        return getProviderFor(dateTime).twilight(twilight).execute();
    }

    private SunTimes.Parameters getProviderFor(ZonedDateTime dateTime) {
        return SunTimes.compute().at(lat, lng).height(height).on(dateTime.with(LocalTime.MIDNIGHT));
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return "astronomical_dawn: " + format(getAstronomicalStart(dateTime)) +
               "\nnautical_dawn: " + format(getNauticalStart(dateTime)) +
               "\ncivil_dawn: " + format(getCivilStart(dateTime)) +
               "\nsunrise: " + format(getSunrise(dateTime)) +
               "\nnoon: " + format(getNoon(dateTime)) +
               "\ngolden_hour: " + format(getGoldenHour(dateTime)) +
               "\nsunset: " + format(getSunset(dateTime)) +
               "\nblue_hour: " + format(getBlueHour(dateTime)) +
               "\ncivil_dusk: " + format(getCivilEnd(dateTime)) +
               "\nnight_hour: " + format(getNightHour(dateTime)) +
               "\nnautical_dusk: " + format(getNauticalEnd(dateTime)) +
               "\nastronomical_dusk: " + format(getAstronomicalEnd(dateTime)) +
               "";
    }

    private String format(ZonedDateTime time) {
        return TIME_FORMATTER.format(time);
    }
}
