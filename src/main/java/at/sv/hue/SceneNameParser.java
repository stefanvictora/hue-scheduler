package at.sv.hue;

import lombok.Builder;

import java.time.LocalTime;
import java.util.Locale;
import java.util.Set;

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

    @Builder
    public record ParseResult(String timeExpression, Boolean interpolate,
                              String transitionTime, String transitionTimeBefore) {
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
        if (isInvalidTimeExpression(parts.timeExpr())) {
            return null;
        }
        ParseResult.ParseResultBuilder builder = ParseResult.builder();
        builder.timeExpression(parts.timeExpr());
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

    private static boolean isInvalidTimeExpression(String expr) {
        if (expr.isEmpty()) {
            return true;
        }
        // Digit-first → attempt to parse as LocalTime (HH:mm or HH:mm:ss)
        if (Character.isDigit(expr.charAt(0))) {
            try {
                LocalTime.parse(expr);
                return false;
            } catch (Exception e) {
                return true;
            }
        }
        // Offset expression: <sunKeyword>[+-]<integer>
        int operatorIndex = getOperatorIndex(expr);
        if (operatorIndex > 0) {
            String keyword = expr.substring(0, operatorIndex).trim().toLowerCase(Locale.ENGLISH);
            if (!SUN_KEYWORDS.contains(keyword)) {
                return true;
            }
            String offsetStr = expr.substring(operatorIndex + 1).trim();
            try {
                Integer.parseInt(offsetStr);
                return false;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        // Just sun keyword
        return !SUN_KEYWORDS.contains(expr.toLowerCase(Locale.ENGLISH));
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
            }
        }
    }
}
