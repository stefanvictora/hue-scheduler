package at.sv.hue.time;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

class SunTimesProviderTest {

    private ZonedDateTime dateTime;
    private SunTimesProviderImpl provider;

    private void assertTime(ZonedDateTime time, int hour, int minute, int second) {
        assertThat("Time differs", time.toLocalTime(), is(LocalTime.of(hour, minute, second)));
    }

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Europe/Vienna");
        dateTime = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, zone);
        provider = new SunTimesProviderImpl(48.20, 16.39, 165);
    }

    @Test
    void returnsCorrectTimes_dependingOnDate() {
        assertTime(provider.getAstronomicalStart(dateTime), 5, 51, 23);
        assertTime(provider.getNauticalStart(dateTime), 6, 29, 17);
        assertTime(provider.getCivilStart(dateTime), 7, 8, 51);
        assertTime(provider.getSunrise(dateTime), 7, 42, 13);
        assertTime(provider.getNoon(dateTime), 11, 58, 13);
        assertTime(provider.getGoldenHour(dateTime), 15, 18, 3);
        assertTime(provider.getSunset(dateTime), 16, 14, 29);
        assertTime(provider.getBlueHour(dateTime), 16, 34, 3);
        assertTime(provider.getCivilEnd(dateTime), 16, 47, 46);
        assertTime(provider.getNightHour(dateTime), 17, 1, 7);
        assertTime(provider.getNauticalEnd(dateTime), 17, 27, 25);
        assertTime(provider.getAstronomicalEnd(dateTime), 18, 5, 11);
        dateTime = dateTime.plusDays(30);
        assertTime(provider.getAstronomicalStart(dateTime), 5, 36, 35);
        assertTime(provider.getNauticalStart(dateTime), 6, 12, 59);
        assertTime(provider.getCivilStart(dateTime), 6, 50, 27);
        assertTime(provider.getSunrise(dateTime), 7, 21, 13);
        assertTime(provider.getNoon(dateTime), 12, 8, 11);
        assertTime(provider.getGoldenHour(dateTime), 16, 5, 15);
        assertTime(provider.getSunset(dateTime), 16, 55, 24);
        assertTime(provider.getBlueHour(dateTime), 17, 13, 21);
        assertTime(provider.getCivilEnd(dateTime), 17, 26, 13);
        assertTime(provider.getNightHour(dateTime), 17, 38, 52);
        assertTime(provider.getNauticalEnd(dateTime), 18, 3, 35);
        assertTime(provider.getAstronomicalEnd(dateTime), 18, 40, 13);
    }

    @Test
    void returnsCorrectTime_doesNotDependOnTimeOfDay() {
        assertTime(provider.getSunset(dateTime), 16, 14, 29);
        assertTime(provider.getSunset(dateTime.withHour(16).withMinute(14).withSecond(30)), 16, 14, 29);
    }

    @Test
    void doesNotReturnNull_evenIfSunNeverSetsAtLocation_returnsDateAFewMonthsInTheFuture() {
        provider = new SunTimesProviderImpl(78.614803, 15.895517, 0); // Somewhere in Svalbard (Norway)
        ZonedDateTime nextSunset = dateTime.with(LocalDateTime.of(2021, 8, 26, 0, 24, 23));

        ZonedDateTime sunset = provider.getSunset(dateTime.withMonth(5));

        assertThat(sunset, is(nextSunset));
    }
}
