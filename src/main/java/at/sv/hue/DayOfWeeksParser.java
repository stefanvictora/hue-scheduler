package at.sv.hue;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Locale;

public final class DayOfWeeksParser {

    private DayOfWeeksParser() {
    }

    public static void parseDayOfWeeks(String value, EnumSet<DayOfWeek> dayOfWeeks) {
        String[] days = value.split(",");
        for (String day : days) {
            if (day.contains("-")) {
                String[] rangeStartAndEnd = getAndAssertDayRange(day);
                DayOfWeek rangeStart = parseDay(rangeStartAndEnd[0]);
                DayOfWeek rangeEnd = parseDay(rangeStartAndEnd[1]);
                if (overFlowsEndOfWeek(rangeStart, rangeEnd)) {
                    dayOfWeeks.addAll(EnumSet.range(rangeStart, DayOfWeek.SUNDAY));
                    dayOfWeeks.addAll(EnumSet.range(DayOfWeek.MONDAY, rangeEnd));
                } else {
                    dayOfWeeks.addAll(EnumSet.range(rangeStart, rangeEnd));
                }
            } else {
                dayOfWeeks.add(parseDay(day));
            }
        }
    }

    private static String[] getAndAssertDayRange(String day) {
        String[] rangeStartAndEnd = day.split("-");
        if (rangeStartAndEnd.length != 2) {
            throw new InvalidPropertyValue("Invalid day range definition '" + day + "'. Please make sure to separate the days with a single dash ('-').");
        }
        return rangeStartAndEnd;
    }

    private static DayOfWeek parseDay(String day) {
        return switch (day.trim().toLowerCase(Locale.ENGLISH)) {
            case "mo", "mon" -> DayOfWeek.MONDAY;
            case "tu", "tue", "di" -> DayOfWeek.TUESDAY;
            case "we", "wen", "mi" -> DayOfWeek.WEDNESDAY;
            case "th", "thu", "do" -> DayOfWeek.THURSDAY;
            case "fr", "fri" -> DayOfWeek.FRIDAY;
            case "sa", "sat" -> DayOfWeek.SATURDAY;
            case "su", "sun", "so" -> DayOfWeek.SUNDAY;
            default ->
                    throw new InvalidPropertyValue("Unknown day parameter '" + day + "'. Please check your spelling. " +
                                                   "Supported values (case insensitive): [Mo|Mon, Tu|Di|Tue, We|Mi|Wen, Th|Do|Thu, Fr|Fri, Sa|Sat Su|So|Sun]");
        };
    }

    private static boolean overFlowsEndOfWeek(DayOfWeek rangeStart, DayOfWeek rangeEnd) {
        return rangeStart.compareTo(rangeEnd) > 0;
    }
}
