package at.sv.hue.time;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StartTimeProviderImpl implements StartTimeProvider {

    private final SunTimesProvider sunTimesProvider;
    private final Map<String, LocalTime> timeCache;

    public StartTimeProviderImpl(SunTimesProvider sunTimesProvider) {
        this.sunTimesProvider = sunTimesProvider;
        timeCache = new ConcurrentHashMap<>();
    }

    @Override
    public ZonedDateTime getStart(String input, ZonedDateTime dateTime) {
        LocalTime time = tryParseTimeString(input);
        if (time != null) return dateTime.with(time);
        try {
            if (isFunctionExpression(input)) {
                return parseFunctionExpression(input, dateTime);
            }
            if (isOffsetExpression(input)) {
                return parseOffsetExpression(input, dateTime);
            }
            return parseSunKeywords(input, dateTime);
        } catch (InvalidStartTimeExpression e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidStartTimeExpression("Failed to parse start time expression '" + input + "': " + e.getMessage());
        }
    }

    private boolean isFunctionExpression(String input) {
        String lower = input.toLowerCase(Locale.ENGLISH);
        return lower.startsWith("notbefore(") || lower.startsWith("notafter(")
                || lower.startsWith("clamp(") || lower.startsWith("min(") || lower.startsWith("max(");
    }

    private ZonedDateTime parseFunctionExpression(String input, ZonedDateTime dateTime) {
        int openParen = input.indexOf('(');
        int closeParen = input.lastIndexOf(')');
        if (openParen == -1 || closeParen == -1 || closeParen <= openParen) {
            throw new InvalidStartTimeExpression("Invalid function syntax: '" + input + "'");
        }
        if (closeParen != input.length() - 1) {
            throw new InvalidStartTimeExpression("Unexpected text after closing parenthesis: '" + input + "'");
        }
        String functionName = input.substring(0, openParen).trim().toLowerCase(Locale.ENGLISH);
        String argString = input.substring(openParen + 1, closeParen);
        List<String> args = splitFunctionArguments(argString);

        switch (functionName) {
            case "notbefore", "max" -> {
                requireArgCount(functionName, args, 2);
                ZonedDateTime a = getStart(args.get(0).trim(), dateTime);
                ZonedDateTime b = getStart(args.get(1).trim(), dateTime);
                return a.isAfter(b) ? a : b;
            }
            case "notafter", "min" -> {
                requireArgCount(functionName, args, 2);
                ZonedDateTime a = getStart(args.get(0).trim(), dateTime);
                ZonedDateTime b = getStart(args.get(1).trim(), dateTime);
                return a.isBefore(b) ? a : b;
            }
            case "clamp" -> {
                requireArgCount(functionName, args, 3);
                ZonedDateTime expr = getStart(args.get(0).trim(), dateTime);
                ZonedDateTime minT = getStart(args.get(1).trim(), dateTime);
                ZonedDateTime maxT = getStart(args.get(2).trim(), dateTime);
                if (expr.isBefore(minT)) return minT;
                if (expr.isAfter(maxT)) return maxT;
                return expr;
            }
            default -> throw new InvalidStartTimeExpression("Unknown function: '" + functionName + "'");
        }
    }

    private void requireArgCount(String functionName, List<String> args, int expected) {
        if (args.size() != expected) {
            throw new InvalidStartTimeExpression(functionName + " requires exactly " + expected + " arguments, got " + args.size());
        }
    }

    private List<String> splitFunctionArguments(String argString) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argString.length(); i++) {
            char c = argString.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(argString.substring(start, i));
                start = i + 1;
            }
        }
        args.add(argString.substring(start));
        return args;
    }

    private LocalTime tryParseTimeString(String input) {
        if (!Character.isDigit(input.charAt(0))) {
            return null;
        }

        return timeCache.computeIfAbsent(input, k -> {
            try {
                return LocalTime.parse(input);
            } catch (Exception ignore) {
                return null;
            }
        });
    }

    private boolean isOffsetExpression(String input) {
        return input.contains("+") || input.contains("-");
    }

    private ZonedDateTime parseOffsetExpression(String input, ZonedDateTime dateTime) {
        String[] parts = input.split("[+-]");
        ZonedDateTime startTime = parseSunKeywords(parts[0].trim(), dateTime);
        int offset = Integer.parseInt(parts[1].trim());
        if (input.contains("+")) {
            return startTime.plusMinutes(offset);
        } else {
            return startTime.minusMinutes(offset);
        }
    }

    private ZonedDateTime parseSunKeywords(String input, ZonedDateTime dateTime) {
        return switch (input.toLowerCase(Locale.ENGLISH)) {
            case "astronomical_start", "astronomical_dawn" -> sunTimesProvider.getAstronomicalStart(dateTime);
            case "nautical_start", "nautical_dawn" -> sunTimesProvider.getNauticalStart(dateTime);
            case "civil_start", "civil_dawn" -> sunTimesProvider.getCivilStart(dateTime);
            case "sunrise" -> sunTimesProvider.getSunrise(dateTime);
            case "noon" -> sunTimesProvider.getNoon(dateTime);
            case "golden_hour" -> sunTimesProvider.getGoldenHour(dateTime);
            case "sunset" -> sunTimesProvider.getSunset(dateTime);
            case "blue_hour" -> sunTimesProvider.getBlueHour(dateTime);
            case "civil_end", "civil_dusk" -> sunTimesProvider.getCivilEnd(dateTime);
            case "night_hour" -> sunTimesProvider.getNightHour(dateTime);
            case "nautical_end", "nautical_dusk" -> sunTimesProvider.getNauticalEnd(dateTime);
            case "astronomical_end", "astronomical_dusk" -> sunTimesProvider.getAstronomicalEnd(dateTime);
            default -> throw new IllegalArgumentException("Invalid sun keyword: '" + input + "'");
        };
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return sunTimesProvider.toDebugString(dateTime);
    }

    @Override
    public void clearCaches() {
        sunTimesProvider.clearCache();
        timeCache.clear();
    }
}
