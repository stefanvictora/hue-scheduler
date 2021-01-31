package at.sv.hue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

class SunDataProviderTest {

    private ZonedDateTime dateTime;
    private SunDataProviderImpl provider;

    private void assertTime(LocalTime time, int hour, int minute, int second) {
        assertThat("Time differ", time, is(LocalTime.of(hour, minute, second)));
    }

    @BeforeEach
    void setUp() {
        dateTime = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Vienna"));
        provider = new SunDataProviderImpl(() -> dateTime, 48.20, 16.39);
    }

    @Test
    void returnsCorrectTimes_dependingOnDate() {
        assertTime(provider.getSunrise(), 7, 45, 13);
        assertTime(provider.getSunset(), 16, 11, 28);
        assertTime(provider.getNauticalStart(), 6, 29, 17);
        assertTime(provider.getNauticalEnd(), 17, 27, 25);
        assertTime(provider.getCivilStart(), 7, 8, 51);
        assertTime(provider.getCivilEnd(), 16, 47, 46);
        dateTime = dateTime.plusDays(30);
        assertTime(provider.getSunrise(), 7, 23, 58);
        assertTime(provider.getSunset(), 16, 52, 42);
        assertTime(provider.getNauticalStart(), 6, 12, 59);
        assertTime(provider.getNauticalEnd(), 18, 3, 35);
        assertTime(provider.getCivilStart(), 6, 50, 27);
        assertTime(provider.getCivilEnd(), 17, 26, 13);
    }
}
