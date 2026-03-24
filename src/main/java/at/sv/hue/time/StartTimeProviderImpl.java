package at.sv.hue.time;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public final class StartTimeProviderImpl implements StartTimeProvider {

    private final SunTimesProvider sunTimesProvider;
    private final Map<String, LocalTime> timeCache;

    public StartTimeProviderImpl(SunTimesProvider sunTimesProvider) {
        this.sunTimesProvider = sunTimesProvider;
        timeCache = new ConcurrentHashMap<>();
    }

    @Override
    public ZonedDateTime getStart(String input, ZonedDateTime dateTime) {
        try {
            LocalTime time = tryParseTimeString(input);
            if (time != null) return dateTime.with(time);
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
        String trimmed = input.trim();
        int openParen = trimmed.indexOf('(');
        int closeParen = trimmed.lastIndexOf(')');
        if (openParen <= 0 || closeParen != trimmed.length() - 1) {
            return false;
        }
        String functionName = trimmed.substring(0, openParen).trim();
        return functionName.matches("[A-Za-z][A-Za-z0-9_]*");
    }

    private ZonedDateTime parseFunctionExpression(String input, ZonedDateTime dateTime) {
        String normalizedInput = input.trim();
        int openParen = normalizedInput.indexOf('(');
        int closeParen = normalizedInput.lastIndexOf(')');
        String functionName = normalizedInput.substring(0, openParen).trim().toLowerCase(Locale.ENGLISH);
        String argString = normalizedInput.substring(openParen + 1, closeParen);
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
                if (minT.isAfter(maxT)) {
                    log.warn("{} received inverted bounds at {}: min={}, max={}. Returning unclamped expression.",
                            functionName, dateTime, minT, maxT);
                    return expr;
                }
                if (expr.isBefore(minT)) return minT;
                if (expr.isAfter(maxT)) return maxT;
                return expr;
            }
            case "mix" -> {
                requireArgCount(functionName, args, 3);
                ZonedDateTime a = getStart(args.get(0).trim(), dateTime);
                ZonedDateTime b = getStart(args.get(1).trim(), dateTime);
                double weight = parseMixWeight(args.get(2).trim());
                long mixedEpochSeconds = Math.round(a.toEpochSecond() * weight + b.toEpochSecond() * (1.0 - weight));
                return ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(mixedEpochSeconds), a.getZone());
            }
            default -> throw new InvalidStartTimeExpression("Unknown function: '" + functionName + "'");
        }
    }

    private double parseMixWeight(String weightArg) {
        try {
            boolean isPercentage = weightArg.endsWith("%");
            String numericPart = isPercentage ? weightArg.substring(0, weightArg.length() - 1).trim() : weightArg;
            double parsed = Double.parseDouble(numericPart);
            if (!Double.isFinite(parsed)) {
                throw new InvalidStartTimeExpression("mix weight must be a finite number in [0..1] or percentage in [0%..100%], got '" + weightArg + "'");
            }
            double normalized = isPercentage ? parsed / 100.0 : parsed;
            if (normalized < 0.0 || normalized > 1.0) {
                throw new InvalidStartTimeExpression("mix weight must be between 0 and 1 (or 0% and 100%), got " + weightArg);
            }
            return normalized;
        } catch (NumberFormatException e) {
            throw new InvalidStartTimeExpression("mix weight must be a number in [0..1] or percentage in [0%..100%], got '" + weightArg + "'");
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
