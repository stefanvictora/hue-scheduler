package at.sv.hue;

import lombok.Builder;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SceneNameParser {

    private static final Set<String> SUN_KEYWORDS = Set.of(
            "astronomical_start", "astronomical_dawn",
            "nautical_start", "nautical_dawn",
            "civil_start", "civil_dawn",
            "sunrise",
            "noon",
            "golden_hour",
            "sunset",
            "blue_hour",
            "civil_end", "civil_dusk",
            "night_hour",
            "nautical_end", "nautical_dusk",
            "astronomical_end", "astronomical_dusk"
    );

    // Alternative absolute-time format patterns (all digit-starting, normalised to HH:mm)
    private static final Pattern TIME_H_MM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern TIME_H_DOT_MM = Pattern.compile("^(\\d{1,2})\\.(\\d{2})$");
    private static final Pattern TIME_H_MM_AMPM = Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*(am|pm)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_H_AMPM = Pattern.compile("^(\\d{1,2})\\s*(am|pm)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_H_H = Pattern.compile("^(\\d{1,2})\\s*h$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_H_H_MM = Pattern.compile("^(\\d{1,2})\\s*h\\s*(\\d{2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_H_UHR = Pattern.compile("^(\\d{1,2})\\s*uhr$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_H_MM_UHR = Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*uhr$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_H_DOT_MM_UHR = Pattern.compile("^(\\d{1,2})\\.(\\d{2})\\s*uhr$", Pattern.CASE_INSENSITIVE);

    private static final List<Map.Entry<Pattern, String>> ALIASES = List.of(
            // English friendly forms (space-separated; single-word forms already work via SUN_KEYWORDS)
            pattern("^astronomical\\s+dawn$", "astronomical_dawn"),
            pattern("^nautical\\s+dawn$", "nautical_dawn"),
            pattern("^civil\\s+dawn$", "civil_dawn"),
            pattern("^golden\\s+hour$", "golden_hour"),
            pattern("^blue\\s+hour$", "blue_hour"),
            pattern("^civil\\s+dusk$", "civil_dusk"),
            pattern("^night\\s+hour$", "night_hour"),
            pattern("^nautical\\s+dusk$", "nautical_dusk"),
            pattern("^astronomical\\s+dusk$", "astronomical_dusk"),
            // German forms – abbreviated (e.g. "Astr. Morgendämm.") and full (e.g. "Astronomische Morgendämmerung")
            pattern("^astr(onomische)?\\.?\\s*morgend[äa]mm(erung)?\\.?$", "astronomical_dawn"),
            pattern("^naut(ische)?\\.?\\s*morgend[äa]mm(erung)?\\.?$", "nautical_dawn"),
            pattern("^b[üu]rg(erliche)?\\.?\\s*morgend[äa]mm(erung)?\\.?$", "civil_dawn"),
            pattern("^sonnenaufgang$", "sunrise"),
            pattern("^mittag$", "noon"),
            pattern("^goldene\\s*stunde$", "golden_hour"),
            pattern("^blaue\\s*stunde$", "blue_hour"),
            pattern("^sonnenuntergang$", "sunset"),
            pattern("^b[üu]rg(erliche)?\\.?\\s*abendd[äa]mm(erung)?\\.?$", "civil_dusk"),
            pattern("^naut(ische)?\\.?\\s*abendd[äa]mm(erung)?\\.?$", "nautical_dusk"),
            pattern("^astr(onomische)?\\.?\\s*abendd[äa]mm(erung)?\\.?$", "astronomical_dusk")
    );

    private static Map.Entry<Pattern, String> pattern(String regex, String keyword) {
        return Map.entry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), keyword);
    }

    @Builder
    public record ParseResult(String timeExpression, Boolean interpolate,
                              String transitionTime, String transitionTimeBefore,
                              String daysOfWeek,
                              Boolean forced, Boolean on) {
    }

    private record SceneParts(String timeExpr, String flags) {
    }

    private SceneNameParser() {
    }

    /**
     * Parses a Hue scene name into a scheduled time expression and optional flags.
     *
     * @return a {@link ParseResult}, or {@code null} if the name is not a valid schedule pattern
     */
    public static ParseResult parse(String sceneName) {
        if (sceneName == null || sceneName.isEmpty()) {
            return null;
        }
        SceneParts parts = parseSceneName(sceneName.trim());
        if (parts == null) {
            return null;
        }
        String timeExpr = normalizeTimeExpression(parts.timeExpr());
        if (timeExpr == null) {
            return null;
        }
        ParseResult.ParseResultBuilder builder = ParseResult.builder();
        builder.timeExpression(timeExpr);
        parseFlags(parts.flags(), builder);
        return builder.build();
    }

    private static SceneParts parseSceneName(String sceneName) {
        int bracketIndex = sceneName.indexOf('[');
        if (bracketIndex >= 0) {
            if (!sceneName.endsWith("]")) {
                return null;
            }
            String timeExpr = sceneName.substring(0, bracketIndex).trim();
            String flagsPart = sceneName.substring(bracketIndex + 1, sceneName.length() - 1);
            return new SceneParts(timeExpr, flagsPart);
        }
        return new SceneParts(sceneName.trim(), null);
    }

    /**
     * Normalizes the time expression to a canonical form, or returns {@code null} if invalid.
     * Handles absolute times (various formats), sun keyword aliases, and offset expressions.
     */
    private static String normalizeTimeExpression(String expr) {
        if (expr.isEmpty()) {
            return null;
        }
        if (Character.isDigit(expr.charAt(0))) {
            return normalizeAbsoluteTime(expr);
        }
        return normalizeSunExpression(expr);
    }

    /**
     * Normalizes a digit-starting time to {@code HH:mm}; returns {@code null} if unparseable.
     */
    private static String normalizeAbsoluteTime(String expr) {
        try {
            LocalTime.parse(expr);
            return expr;
        } catch (Exception ignored) {
        }
        LocalTime t = parseAlternativeTime(expr);
        if (t == null) {
            return null;
        }
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static LocalTime parseAlternativeTime(String expr) {
        Matcher m;

        // H:mm and H.mm (24-hour)
        m = TIME_H_MM.matcher(expr);
        if (m.matches()) return timeAt(m.group(1), m.group(2));

        m = TIME_H_DOT_MM.matcher(expr);
        if (m.matches()) return timeAt(m.group(1), m.group(2));

        // H:mm Uhr and H.mm Uhr
        m = TIME_H_MM_UHR.matcher(expr);
        if (m.matches()) return timeAt(m.group(1), m.group(2));

        m = TIME_H_DOT_MM_UHR.matcher(expr);
        if (m.matches()) return timeAt(m.group(1), m.group(2));

        // h:mm am/pm and h am/pm (12-hour)
        m = TIME_H_MM_AMPM.matcher(expr);
        if (m.matches()) return convertAmPm(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3));

        m = TIME_H_AMPM.matcher(expr);
        if (m.matches()) return convertAmPm(Integer.parseInt(m.group(1)), 0, m.group(2));

        // H Uhr, Hh, H h (whole-hour forms)
        m = TIME_H_UHR.matcher(expr);
        if (m.matches()) return wholeHour(m.group(1));

        m = TIME_H_H.matcher(expr);
        if (m.matches()) return wholeHour(m.group(1));

        // H h mm, Hhmm (hour + minutes with 'h' separator)
        m = TIME_H_H_MM.matcher(expr);
        if (m.matches()) return timeAt(m.group(1), m.group(2));

        return null;
    }

    private static LocalTime timeAt(String hourStr, String minuteStr) {
        int hour = Integer.parseInt(hourStr);
        int minute = Integer.parseInt(minuteStr);
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private static LocalTime wholeHour(String hourStr) {
        int hour = Integer.parseInt(hourStr);
        if (hour > 23) {
            return null;
        }
        return LocalTime.of(hour, 0);
    }

    private static LocalTime convertAmPm(int hour, int minute, String amPm) {
        if (hour < 1 || hour > 12 || minute < 0 || minute > 59) {
            return null;
        }
        int h24;
        if ("am".equalsIgnoreCase(amPm)) {
            h24 = hour % 12;
        } else {
            h24 = (hour % 12) + 12;
        }
        return LocalTime.of(h24, minute);
    }

    /**
     * Normalizes and validates a sun keyword expression (optionally with {@code +/-} offset).
     * Resolves friendly aliases (English/German) to their technical keyword.
     * Returns {@code null} if the keyword is unknown or the offset is not a valid integer.
     */
    private static String normalizeSunExpression(String expr) {
        int operatorIndex = getOperatorIndex(expr);
        String keywordPart;
        if (operatorIndex > 0) {
            keywordPart = expr.substring(0, operatorIndex).trim();
        } else {
            keywordPart = expr;
        }
        String resolved = mapAlias(keywordPart);
        if (resolved == null) {
            if (!SUN_KEYWORDS.contains(keywordPart.toLowerCase(Locale.ENGLISH))) {
                return null;
            }
            resolved = keywordPart;
        }
        if (operatorIndex > 0) {
            if (!isValidOffset(expr.substring(operatorIndex + 1).trim())) {
                return null;
            }
            return resolved + expr.substring(operatorIndex);
        }
        return resolved;
    }

    private static int getOperatorIndex(String expr) {
        int plusIndex = expr.indexOf('+');
        int minusIndex = expr.indexOf('-');
        if (plusIndex >= 0) {
            return plusIndex;
        } else if (minusIndex >= 0) {
            return minusIndex;
        }
        return -1;
    }

    private static String mapAlias(String keyword) {
        for (Map.Entry<Pattern, String> alias : ALIASES) {
            if (alias.getKey().matcher(keyword).matches()) {
                return alias.getValue();
            }
        }
        return null;
    }

    private static boolean isValidOffset(String offsetStr) {
        try {
            Integer.parseInt(offsetStr);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void parseFlags(String flags, ParseResult.ParseResultBuilder builder) {
        if (flags == null || flags.isEmpty()) {
            return;
        }
        for (String flag : flags.split(",", -1)) {
            flag = flag.trim();
            if (flag.equals("i")) {
                builder.interpolate(Boolean.TRUE);
            } else if (flag.startsWith("tr-b:")) {
                builder.transitionTimeBefore(flag.substring("tr-b:".length()));
            } else if (flag.startsWith("tr:")) {
                builder.transitionTime(flag.substring("tr:".length()));
            } else if (flag.startsWith("days:")) {
                builder.daysOfWeek(flag.substring("days:".length()).replace(";", ","));
            } else if (flag.equals("f")) {
                builder.forced(Boolean.TRUE);
            } else if (flag.equals("off")) {
                builder.on(Boolean.FALSE);
            } else if (flag.equals("on")) {
                builder.on(Boolean.TRUE);
            }
        }
    }
}
