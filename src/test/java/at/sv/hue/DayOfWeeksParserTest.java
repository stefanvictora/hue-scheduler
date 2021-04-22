package at.sv.hue;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;

import static java.time.DayOfWeek.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DayOfWeeksParserTest {

    private void parse(String input, DayOfWeek... days) {
        EnumSet<DayOfWeek> dayOfWeeks = EnumSet.noneOf(DayOfWeek.class);
        DayOfWeeksParser.parseDayOfWeeks(input, dayOfWeeks);

        assertThat("Day of weeks differ for '" + input + "'.", dayOfWeeks, is(EnumSet.copyOf(Arrays.asList(days))));
    }

    @Test
    void canParseSingle() {
        parse("Fr", FRIDAY);
    }

    @Test
    void canParseRange_single_simple() {
        parse("Mo-We", MONDAY, TUESDAY, WEDNESDAY);
    }

    @Test
    void canParseRange_single_same() {
        parse("Mo-Mo", MONDAY);
    }

    @Test
    void canParseRange_multiple_simple() {
        parse("Mo-We, Fr-Su", MONDAY, TUESDAY, WEDNESDAY, FRIDAY, SATURDAY, SUNDAY);
    }

    @Test
    void canParseRange_complex_canOverflow_startsFromStartOfNextWeekAgain() {
        parse("Fr-Tu", FRIDAY, SATURDAY, SUNDAY, MONDAY, TUESDAY);
    }

    @Test
    void range_invalid_exception() {
        assertThrows(InvalidPropertyValue.class, () -> parse("Fr-"));
    }
}
