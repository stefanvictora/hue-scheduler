package at.sv.hue;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
            case "we", "wed", "wen", "mi" -> DayOfWeek.WEDNESDAY;
            case "th", "thu", "do" -> DayOfWeek.THURSDAY;
            case "fr", "fri" -> DayOfWeek.FRIDAY;
            case "sa", "sat" -> DayOfWeek.SATURDAY;
            case "su", "sun", "so" -> DayOfWeek.SUNDAY;
            default ->
                    throw new InvalidPropertyValue("Unknown day parameter '" + day + "'. Please check your spelling. " +
                                                   "Supported values (case insensitive): [Mo|Mon, Tu|Di|Tue, We|Wed|Mi, Th|Do|Thu, Fr|Fri, Sa|Sat, Su|So|Sun]");
        };
    }

    private static boolean overFlowsEndOfWeek(DayOfWeek rangeStart, DayOfWeek rangeEnd) {
        return rangeStart.compareTo(rangeEnd) > 0;
    }

    /**
     * Converts a set of days to a compact interval string using "," as delimiter.
     * Consecutive days are compressed into ranges (e.g., Mo,Tu,We,Th → "Mo-Th").
     * Returns {@code null} if all 7 days are present (no flag needed).
     */
    public static String formatDaysOfWeek(EnumSet<DayOfWeek> daysOfWeek) {
        if (daysOfWeek.size() == 7) {
            return null;
        }
        List<DayOfWeek> days = new ArrayList<>(daysOfWeek); // EnumSet iterates in natural enum order (Mo..Su)
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (i < days.size()) {
            int j = i;
            while (j + 1 < days.size() && days.get(j + 1).getValue() == days.get(j).getValue() + 1) {
                j++;
            }
            if (i == j) {
                segments.add(toDayString(days.get(i)));
            } else {
                segments.add(toDayString(days.get(i)) + "-" + toDayString(days.get(j)));
            }
            i = j + 1;
        }
        return String.join(",", segments);
    }

    private static String toDayString(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "Mo";
            case TUESDAY -> "Tu";
            case WEDNESDAY -> "We";
            case THURSDAY -> "Th";
            case FRIDAY -> "Fr";
            case SATURDAY -> "Sa";
            case SUNDAY -> "Su";
        };
    }
}
