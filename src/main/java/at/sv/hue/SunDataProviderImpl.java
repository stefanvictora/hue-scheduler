package at.sv.hue;

import org.shredzone.commons.suncalc.SunTimes;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

public class SunDataProviderImpl implements SunDataProvider {

    private final Supplier<ZonedDateTime> timeProvider;
    private final SunTimes.Parameters parameters;

    public SunDataProviderImpl(Supplier<ZonedDateTime> timeProvider, double lat, double lng) {
        this.timeProvider = timeProvider;
        parameters = SunTimes.compute().at(lat, lng);
    }

    @Override
    public LocalTime getSunrise() {
        return sunTimesForCurrentDay().getRise().toLocalTime();
    }

    @Override
    public LocalTime getSunset() {
        return sunTimesForCurrentDay().getSet().toLocalTime();
    }

    @Override
    public LocalTime getNauticalStart() {
        return sunTimesForCurrentDay(SunTimes.Twilight.NAUTICAL).getRise().toLocalTime();
    }

    @Override
    public LocalTime getNauticalEnd() {
        return sunTimesForCurrentDay(SunTimes.Twilight.NAUTICAL).getSet().toLocalTime();
    }

    @Override
    public LocalTime getCivilStart() {
        return sunTimesForCurrentDay(SunTimes.Twilight.CIVIL).getRise().toLocalTime();
    }

    @Override
    public LocalTime getCivilEnd() {
        return sunTimesForCurrentDay(SunTimes.Twilight.CIVIL).getSet().toLocalTime();
    }

    @Override
    public LocalTime getGoldenHour() {
        return sunTimesForCurrentDay(SunTimes.Twilight.GOLDEN_HOUR).getSet().toLocalTime();
    }

    @Override
    public LocalTime getAstronomicalEnd() {
        return sunTimesForCurrentDay(SunTimes.Twilight.ASTRONOMICAL).getSet().toLocalTime();
    }

    @Override
    public LocalTime getAstronomicalStart() {
        return sunTimesForCurrentDay(SunTimes.Twilight.ASTRONOMICAL).getRise().toLocalTime();
    }

    private SunTimes sunTimesForCurrentDay() {
        return sunTimesForCurrentDay(SunTimes.Twilight.VISUAL);
    }

    private SunTimes sunTimesForCurrentDay(SunTimes.Twilight twilight) {
        return getProviderForCurrentDay().twilight(twilight).execute();
    }

    private SunTimes.Parameters getProviderForCurrentDay() {
        return parameters.on(timeProvider.get());
    }

    @Override
    public String toString() {
        return "\nastronomical start: " + getAstronomicalStart() +
                "\nnautical start: " + getNauticalStart() +
                "\ncivil start: " + getCivilStart() +
                "\nsunrise: " + getSunrise() +
                "\ngolden hour: " + getGoldenHour() +
                "\nsunset: " + getSunset() +
                "\ncivil end: " + getCivilEnd() +
                "\nnautical end: " + getNauticalEnd() +
                "\nastronomical end: " + getAstronomicalEnd() +
                "";
    }
}
