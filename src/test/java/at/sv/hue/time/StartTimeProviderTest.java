package at.sv.hue.time;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartTimeProviderTest {

    private ZonedDateTime sunrise;
    private ZonedDateTime noon;
    private ZonedDateTime sunset;
    private StartTimeProvider provider;
    private ZonedDateTime nauticalStart;
    private ZonedDateTime nauticalEnd;
    private ZonedDateTime civilStart;
    private ZonedDateTime civilEnd;
    private ZonedDateTime goldenHour;
    private ZonedDateTime blueHour;
    private ZonedDateTime nightHour;
    private ZonedDateTime astronomicalStart;
    private ZonedDateTime astronomicalEnd;
    private ZonedDateTime now;
    private ZonedDateTime nextDay;
    private ZonedDateTime nextDaySunrise;

    private void assertStart(String input, ZonedDateTime time) {
        assertStart(input, now, time);
    }

    private void assertStart(String input, ZonedDateTime dateTime, ZonedDateTime time) {
        assertThat("Start differs", provider.getStart(input, dateTime), is(time));
    }

    @BeforeEach
    void setUp() {
        now = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Vienna"));
        nextDay = now.plusDays(1);
        astronomicalStart = now.with(LocalTime.of(5, 0));
        nauticalStart = now.with(LocalTime.of(6, 13));
        civilStart = now.with(LocalTime.of(6, 50));
        sunrise = now.with(LocalTime.of(7, 0));
        noon = now.with(LocalTime.of(12, 58));
        nextDaySunrise = now.with(LocalTime.of(7, 10));
        goldenHour = now.with(LocalTime.of(15, 0));
        sunset = now.with(LocalTime.of(16, 0));
        blueHour = now.with(LocalTime.of(16, 15));
        civilEnd = now.with(LocalTime.of(17, 26));
        nightHour = now.with(LocalTime.of(17, 45));
        nauticalEnd = now.with(LocalTime.of(18, 4));
        astronomicalEnd = now.with(LocalTime.of(19, 10));
        provider = new StartTimeProviderImpl(new SunTimesProvider() {
            @Override
            public ZonedDateTime getSunrise(ZonedDateTime dateTime) {
                if (dateTime.equals(nextDay)) {
                    return nextDaySunrise;
                } else {
                    return sunrise;
                }
            }

            @Override
            public ZonedDateTime getNoon(ZonedDateTime dateTime) {
                return noon;
            }

            @Override
            public ZonedDateTime getSunset(ZonedDateTime dateTime) {
                return sunset;
            }

            @Override
            public ZonedDateTime getNauticalStart(ZonedDateTime dateTime) {
                return nauticalStart;
            }

            @Override
            public ZonedDateTime getNauticalEnd(ZonedDateTime dateTime) {
                return nauticalEnd;
            }

            @Override
            public ZonedDateTime getCivilStart(ZonedDateTime dateTime) {
                return civilStart;
            }

            @Override
            public ZonedDateTime getCivilEnd(ZonedDateTime dateTime) {
                return civilEnd;
            }

            @Override
            public ZonedDateTime getGoldenHour(ZonedDateTime dateTime) {
                return goldenHour;
            }

            @Override
            public ZonedDateTime getBlueHour(ZonedDateTime dateTime) {
                return blueHour;
            }

            @Override
            public ZonedDateTime getNightHour(ZonedDateTime dateTime) {
                return nightHour;
            }

            @Override
            public ZonedDateTime getAstronomicalEnd(ZonedDateTime dateTime) {
                return astronomicalEnd;
            }

            @Override
            public ZonedDateTime getAstronomicalStart(ZonedDateTime dateTime) {
                return astronomicalStart;
            }

            @Override
            public String toDebugString(ZonedDateTime dateTime) {
                return null;
            }

            @Override
            public void clearCache() {

            }
        });
    }

    @Test
    void parse_simpleDateTime_returnsLocalTime() {
        assertStart("07:00", now.with(LocalTime.of(7, 0)));
        assertStart("08:00", now.with(LocalTime.of(8, 0)));
        assertStart("12:59", now.with(LocalTime.of(12, 59)));
        assertStart("17:05", now.with(LocalTime.of(17, 5)));
    }

    @Test
    void parse_simpleKeyWord_returnsSunData() {
        assertStart("sunrise", sunrise);
        assertStart("noon", noon);
        assertStart("sunset", sunset);
        assertStart("nautical_start", nauticalStart);
        assertStart("nautical_dawn", nauticalStart);
        assertStart("nautical_end", nauticalEnd);
        assertStart("nautical_dusk", nauticalEnd);
        assertStart("civil_start", civilStart);
        assertStart("civil_dawn", civilStart);
        assertStart("civil_end", civilEnd);
        assertStart("civil_dusk", civilEnd);
        assertStart("golden_hour", goldenHour);
        assertStart("blue_hour", blueHour);
        assertStart("night_hour", nightHour);
        assertStart("astronomical_start", astronomicalStart);
        assertStart("astronomical_dawn", astronomicalStart);
        assertStart("astronomical_end", astronomicalEnd);
        assertStart("astronomical_dusk", astronomicalEnd);
    }

    @Test
    void parse_simpleKeyWord_usesStartTime() {
        assertStart("sunrise", nextDay, nextDaySunrise);
    }

    @Test
    void parse_keyWord_withOffset_returnsCorrectData() {
        assertStart("sunrise + 30", sunrise.plusMinutes(30));
        assertStart("civil_start-10", civilStart.minusMinutes(10));
        assertStart("nautical_end- 60", nauticalEnd.minusMinutes(60));
    }

    @Test
    void parse_keyWord_withOffset_invalidExpression() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("sunrise + sunrise", now));
    }

    @Test
    void parse_invalidOffsetExpression() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("+10", now));
    }

    @Test
    void parse_invalidKeyword_exception() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("INVALID_KEYWORD", now));
    }
}
