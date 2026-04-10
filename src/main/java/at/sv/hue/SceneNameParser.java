package at.sv.hue;

import lombok.Builder;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static Map.Entry<Pattern, String> pattern(String regex, String astronomical_dawn) {
        return Map.entry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), astronomical_dawn);
    }

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
        String timeExpr = normalizeTimeExpression(parts.timeExpr());
        if (isInvalidTimeExpression(timeExpr)) {
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

    private static String normalizeTimeExpression(String expr) {
        if (expr.isEmpty() || Character.isDigit(expr.charAt(0))) {
            return expr;
        }
        int operatorIndex = getOperatorIndex(expr);
        String keywordPart = operatorIndex > 0 ? expr.substring(0, operatorIndex).trim() : expr;
        String mapped = mapAlias(keywordPart);
        if (mapped != null) {
            return operatorIndex > 0 ? mapped + expr.substring(operatorIndex) : mapped;
        }
        return expr;
    }

    private static String mapAlias(String keyword) {
        for (Map.Entry<Pattern, String> alias : ALIASES) {
            if (alias.getKey().matcher(keyword).matches()) {
                return alias.getValue();
            }
        }
        return null;
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
