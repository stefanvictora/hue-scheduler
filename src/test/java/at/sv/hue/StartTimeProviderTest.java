package at.sv.hue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartTimeProviderTest {

    private LocalTime sunrise;
    private LocalTime sunset;
    private StartTimeProvider provider;
    private LocalTime nauticalStart;
    private LocalTime nauticalEnd;
    private LocalTime civilStart;
    private LocalTime civilEnd;
    private LocalTime goldenHour;
    private LocalTime astronomicalStart;
    private LocalTime astronomicalEnd;

    private void assertStart(String input, LocalTime time) {
        assertThat("Start differs", provider.getStart(input), is(time));
    }

    @BeforeEach
    void setUp() {
        astronomicalStart = LocalTime.of(5, 0);
        nauticalStart = LocalTime.of(6, 13);
        civilStart = LocalTime.of(6, 50);
        sunrise = LocalTime.of(7, 0);
        goldenHour = LocalTime.of(15, 0);
        sunset = LocalTime.of(16, 0);
        civilEnd = LocalTime.of(17, 26);
        nauticalEnd = LocalTime.of(18, 4);
        astronomicalEnd = LocalTime.of(19, 10);
        provider = new StartTimeProviderImpl(new SunDataProvider() {
            @Override
            public LocalTime getSunrise() {
                return sunrise;
            }

            @Override
            public LocalTime getSunset() {
                return sunset;
            }

            @Override
            public LocalTime getNauticalStart() {
                return nauticalStart;
            }

            @Override
            public LocalTime getNauticalEnd() {
                return nauticalEnd;
            }

            @Override
            public LocalTime getCivilStart() {
                return civilStart;
            }

            @Override
            public LocalTime getCivilEnd() {
                return civilEnd;
            }

            @Override
            public LocalTime getGoldenHour() {
                return goldenHour;
            }

            @Override
            public LocalTime getAstronomicalEnd() {
                return astronomicalEnd;
            }

            @Override
            public LocalTime getAstronomicalStart() {
                return astronomicalStart;
            }
        });
    }

    @Test
    void parse_simpleDateTime_returnsLocalTime() {
        assertStart("07:00", LocalTime.of(7, 0));
        assertStart("08:00", LocalTime.of(8, 0));
    }

    @Test
    void parse_simpleKeyWord_returnsSunData() {
        assertStart("sunrise", sunrise);
        assertStart("sunset", sunset);
        assertStart("nautical_start", nauticalStart);
        assertStart("nautical_end", nauticalEnd);
        assertStart("civil_start", civilStart);
        assertStart("civil_end", civilEnd);
        assertStart("golden_hour", goldenHour);
        assertStart("astronomical_start", astronomicalStart);
        assertStart("astronomical_end", astronomicalEnd);
    }

    @Test
    void parse_keyWord_withOffset_returnsCorrectData() {
        assertStart("sunrise + 30", sunrise.plusMinutes(30));
        assertStart("civil_start-10", civilStart.minusMinutes(10));
        assertStart("nautical_end- 60", nauticalEnd.minusMinutes(60));
    }

    @Test
    void parse_invalidKeyword_exception() {
        assertThrows(IllegalArgumentException.class, () -> provider.getStart("INVALID_KEYWORD"));
    }
}