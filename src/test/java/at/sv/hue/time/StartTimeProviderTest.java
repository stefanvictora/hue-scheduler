package at.sv.hue.time;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(provider.getStart(input, dateTime)).isEqualTo(time);
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

    // --- notBefore / max tests ---

    @Test
    void notBefore_returnsLater_whenExprIsAlreadyLater() {
        // sunrise = 07:00, constraint = 06:30 → sunrise is later → 07:00
        assertStart("notBefore(sunrise, 06:30)", sunrise);
    }

    @Test
    void notBefore_returnsConstraint_whenExprIsEarlier() {
        // sunrise = 07:00, constraint = 08:00 → constraint is later → 08:00
        assertStart("notBefore(sunrise, 08:00)", now.with(LocalTime.of(8, 0)));
    }

    @Test
    void notBefore_withBothFixedTimes() {
        assertStart("notBefore(06:30, 07:00)", now.with(LocalTime.of(7, 0)));
        assertStart("notBefore(08:00, 07:00)", now.with(LocalTime.of(8, 0)));
    }

    // --- notAfter / min tests ---

    @Test
    void notAfter_returnsEarlier_whenExprIsAlreadyEarlier() {
        // sunset = 16:00, constraint = 17:00 → sunset is earlier → 16:00
        assertStart("notAfter(sunset, 17:00)", sunset);
    }

    @Test
    void notAfter_returnsConstraint_whenExprIsLater() {
        // sunset = 16:00, constraint = 15:00 → constraint is earlier → 15:00
        assertStart("notAfter(sunset, 15:00)", now.with(LocalTime.of(15, 0)));
    }

    // --- clamp tests ---

    @Test
    void clamp_returnsExpr_whenWithinBounds() {
        // sunrise = 07:00, min = 06:00, max = 08:00 → within bounds → 07:00
        assertStart("clamp(sunrise, 06:00, 08:00)", sunrise);
    }

    @Test
    void clamp_returnsMin_whenExprIsBelowMin() {
        // sunrise = 07:00, min = 07:30, max = 08:00 → below min → 07:30
        assertStart("clamp(sunrise, 07:30, 08:00)", now.with(LocalTime.of(7, 30)));
    }

    @Test
    void clamp_returnsMax_whenExprIsAboveMax() {
        // sunrise = 07:00, min = 06:00, max = 06:30 → above max → 06:30
        assertStart("clamp(sunrise, 06:00, 06:30)", now.with(LocalTime.of(6, 30)));
    }

    // --- Alias tests ---

    @Test
    void max_isSameAsNotBefore() {
        assertStart("max(06:30, sunrise)", sunrise);
        assertStart("max(sunrise, 08:00)", now.with(LocalTime.of(8, 0)));
    }

    @Test
    void min_isSameAsNotAfter() {
        assertStart("min(sunset, 17:00)", sunset);
        assertStart("min(sunset, 15:00)", now.with(LocalTime.of(15, 0)));
    }

    // --- Offset + constraint tests ---

    @Test
    void notBefore_withOffset_returnsLater() {
        // sunrise+30 = 07:30, constraint = 07:00 → 07:30
        assertStart("notBefore(sunrise+30, 07:00)", sunrise.plusMinutes(30));
    }

    @Test
    void notBefore_withOffset_returnsConstraint() {
        // sunrise+30 = 07:30, constraint = 08:00 → 08:00
        assertStart("notBefore(sunrise+30, 08:00)", now.with(LocalTime.of(8, 0)));
    }

    @Test
    void clamp_withOffset() {
        // sunrise-15 = 06:45, within [06:30, 08:00] → 06:45
        assertStart("clamp(sunrise-15, 06:30, 08:00)", sunrise.minusMinutes(15));
    }

    // --- Nesting tests ---

    @Test
    void nesting_notAfter_notBefore_equalsClamp() {
        // notBefore(sunrise, 06:30) = max(07:00, 06:30) = 07:00
        // notAfter(07:00, 08:00) = min(07:00, 08:00) = 07:00
        assertStart("notAfter(notBefore(sunrise, 06:30), 08:00)", sunrise);
    }

    @Test
    void nesting_notAfter_notBefore_clampsToMin() {
        // notBefore(sunrise, 08:00) = max(07:00, 08:00) = 08:00
        // notAfter(08:00, 09:00) = min(08:00, 09:00) = 08:00
        assertStart("notAfter(notBefore(sunrise, 08:00), 09:00)", now.with(LocalTime.of(8, 0)));
    }

    @Test
    void nesting_notAfter_notBefore_clampsToMax() {
        // notBefore(sunrise, 06:00) = max(07:00, 06:00) = 07:00
        // notAfter(07:00, 06:30) = min(07:00, 06:30) = 06:30
        assertStart("notAfter(notBefore(sunrise, 06:00), 06:30)", now.with(LocalTime.of(6, 30)));
    }

    // --- Case-insensitivity tests ---

    @Test
    void caseInsensitive_notBefore() {
        assertStart("NotBefore(sunrise, 08:00)", now.with(LocalTime.of(8, 0)));
        assertStart("NOTBEFORE(sunrise, 08:00)", now.with(LocalTime.of(8, 0)));
    }

    @Test
    void caseInsensitive_clamp() {
        assertStart("CLAMP(sunrise, 06:00, 08:00)", sunrise);
        assertStart("Clamp(sunrise, 07:30, 08:00)", now.with(LocalTime.of(7, 30)));
    }

    @Test
    void caseInsensitive_minMax() {
        assertStart("MAX(06:30, sunrise)", sunrise);
        assertStart("MIN(sunset, 15:00)", now.with(LocalTime.of(15, 0)));
    }

    // --- Whitespace handling ---

    @Test
    void whitespace_insideArguments_isTrimmed() {
        assertStart("notBefore( sunrise , 08:00 )", now.with(LocalTime.of(8, 0)));
        assertStart("clamp( sunrise , 06:00 , 08:00 )", sunrise);
    }

    // --- Error cases: wrong argument count ---

    @Test
    void notBefore_wrongArgCount_tooFew() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(sunrise)", now));
    }

    @Test
    void notBefore_wrongArgCount_tooMany() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(sunrise, 06:30, 08:00)", now));
    }

    @Test
    void notAfter_wrongArgCount_tooFew() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notAfter(sunrise)", now));
    }

    @Test
    void notAfter_wrongArgCount_tooMany() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notAfter(sunrise, 06:30, 08:00)", now));
    }

    @Test
    void min_wrongArgCount_tooFew() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("min(sunrise)", now));
    }

    @Test
    void min_wrongArgCount_tooMany() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("min(sunrise, 06:30, 08:00)", now));
    }

    @Test
    void max_wrongArgCount_tooFew() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("max(sunrise)", now));
    }

    @Test
    void max_wrongArgCount_tooMany() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("max(sunrise, 06:30, 08:00)", now));
    }

    @Test
    void clamp_wrongArgCount_tooFew() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("clamp(sunrise, 06:30)", now));
    }

    @Test
    void clamp_wrongArgCount_tooMany() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("clamp(sunrise, 06:00, 08:00, 10:00)", now));
    }

    // --- Error cases: invalid syntax ---

    @Test
    void unknownFunction_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("unknownFunc(sunrise, 06:30)", now));
    }

    @Test
    void missingClosingParenthesis_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(sunrise, 06:30", now));
    }

    @Test
    void trailingTextAfterClosingParen_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(sunrise, 06:30)extra", now));
    }

    @Test
    void emptyArguments_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore()", now));
    }

    @Test
    void emptyFirstArgument_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(, 06:30)", now));
    }

    @Test
    void emptySecondArgument_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(sunrise,)", now));
    }

    // --- Error cases: invalid sub-expressions ---

    @Test
    void invalidSubExpression_inNotBefore_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notBefore(INVALID, 06:30)", now));
    }

    @Test
    void invalidSubExpression_inNotAfter_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notAfter(INVALID, 06:30)", now));
    }

    @Test
    void invalidSubExpression_inClamp_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("clamp(INVALID, 06:00, 08:00)", now));
    }

    @Test
    void clamp_withInvertedBounds_returnsUnclampedExpression() {
        assertStart("clamp(sunrise, 08:00, 06:00)", sunrise);
    }

    @Test
    void nestedInvalidSubExpression_throwsException() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("notAfter(notBefore(INVALID, 06:30), 08:00)", now));
    }

    // --- Date propagation ---

    @Test
    void notAfter_usesCorrectDate() {
        // nextDay: sunrise resolves to nextDaySunrise (07:10) instead of regular sunrise (07:00)
        // notAfter(sunrise, noon) = min(07:10, 12:58) = 07:10 = nextDaySunrise
        // Confirms dateTime is correctly passed through to sub-expressions
        assertStart("notAfter(sunrise, noon)", nextDay, nextDaySunrise);
    }

    // --- mix tests ---

    @Test
    void mix_withDecimalWeight_interpolatesTimes() {
        // mix(sunrise=07:00, 08:00, 0.25) = 07:45
        assertStart("mix(sunrise, 08:00, 0.25)", now.with(LocalTime.of(7, 45)));
    }

    @Test
    void mix_withTwoSolarTimes_works() {
        // mix(golden_hour=15:00, sunset=16:00, 0.5) = 15:30
        assertStart("mix(golden_hour, sunset, 0.5)", now.with(LocalTime.of(15, 30)));
    }

    @Test
    void mix_resolvesToNoon() {
        assertStart("mix(00:30, 23:30, 0.5)", now.with(LocalTime.NOON));
    }

    @Test
    void mix_invertedParameters_invalidUsage_usesUnmixedInput() {
        assertStart("mix(23:30, 00:30, 0.5)", now.with(LocalTime.of(23, 30)));
    }

    @Test
    void mix_withPercentageWeight_interpolatesTimes() {
        // mix(sunrise=07:00, 08:00, 25%) = 07:45
        assertStart("mix(sunrise, 08:00, 25%)", now.with(LocalTime.of(7, 45)));
    }

    @Test
    void mix_withWeightOne_returnsFirstArgument() {
        assertStart("mix(sunrise, 08:00, 1)", sunrise);
    }

    @Test
    void mix_withWeightZero_returnsSecondArgument() {
        assertStart("mix(sunrise, 08:00, 0)", now.with(LocalTime.of(8, 0)));
    }

    @Test
    void mix_canBeComposedWithClamp() {
        assertStart("clamp(mix(sunrise, 08:00, 0.35), 06:30, 08:00)", now.with(LocalTime.of(7, 39)));
    }

    @Test
    void mix_rejectsWeightOutsideRange() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("mix(sunrise, 08:00, 1.1)", now));
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("mix(sunrise, 08:00, 110%)", now));
    }

    @Test
    void mix_rejectsInvalidWeightFormat() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("mix(sunrise, 08:00, sunrise)", now));
    }

    @Test
    void mix_rejectsNaNWeight() {
        InvalidStartTimeExpression exception = assertThrows(InvalidStartTimeExpression.class,
                () -> provider.getStart("mix(sunrise, 08:00, NaN)", now));
        assertThat(exception.getMessage()).contains("'NaN'");
    }

    @Test
    void mix_rejectsInfinityWeight() {
        InvalidStartTimeExpression exception = assertThrows(InvalidStartTimeExpression.class,
                () -> provider.getStart("mix(sunrise, 08:00, Infinity)", now));
        assertThat(exception.getMessage()).contains("'Infinity'");
    }

    @Test
    void mix_rejectsInfinityPercentageWeight() {
        InvalidStartTimeExpression exception = assertThrows(InvalidStartTimeExpression.class,
                () -> provider.getStart("mix(sunrise, 08:00, Infinity%)", now));
        assertThat(exception.getMessage()).contains("'Infinity%'");
    }

    @Test
    void mix_wrongArgCount() {
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("mix(sunrise, 08:00)", now));
        assertThrows(InvalidStartTimeExpression.class, () -> provider.getStart("mix(sunrise, 08:00, 0.5, 0.1)", now));
    }
}
