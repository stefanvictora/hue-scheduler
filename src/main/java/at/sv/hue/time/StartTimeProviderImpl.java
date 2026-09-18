package at.sv.hue.time;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class StartTimeProviderImpl implements StartTimeProvider {

    private static final Pattern OFFSET_PATTERN = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m(?:in)?)?(?:(\\d+)s)?",
            Pattern.CASE_INSENSITIVE);
    private static final int SECONDS_IN_A_DAY = 86_400;
    private static final int SECONDS_IN_HALF_A_DAY = SECONDS_IN_A_DAY / 2;

    private final SunTimesProvider sunTimesProvider;
    private final Map<String, StartTimeExpression> expressionCache;

    public StartTimeProviderImpl(SunTimesProvider sunTimesProvider) {
        this.sunTimesProvider = sunTimesProvider;
        expressionCache = new ConcurrentHashMap<>();
    }

    @FunctionalInterface
    private interface StartTimeExpression {
        ZonedDateTime resolve(ZonedDateTime dateTime);
    }

    @Override
    public boolean isValid(String input) {
        try {
            compile(input);
            return true;
        } catch (InvalidStartTimeExpression e) {
            return false;
        }
    }

    @Override
    public ZonedDateTime getStart(String input, ZonedDateTime dateTime) {
        try {
            return compile(input).resolve(dateTime);
        } catch (InvalidStartTimeExpression e) {
            throw e;
        } catch (Exception e) {
            throw invalidExpression(input, e);
        }
    }

    private StartTimeExpression compile(String input) {
        try {
            StartTimeExpression cached = expressionCache.get(input);
            if (cached != null) {
                return cached;
            }
            StartTimeExpression expression = compileExpression(input);
            StartTimeExpression previous = expressionCache.putIfAbsent(input, expression);
            return previous == null ? expression : previous;
        } catch (InvalidStartTimeExpression e) {
            throw e;
        } catch (Exception e) {
            throw invalidExpression(input, e);
        }
    }

    private static InvalidStartTimeExpression invalidExpression(String input, Exception cause) {
        return new InvalidStartTimeExpression(
                "Failed to parse start time expression '" + input + "': " + cause.getMessage());
    }

    private StartTimeExpression compileExpression(String input) {
        LocalTime time = tryParseTimeString(input);
        if (time != null) {
            return dateTime -> dateTime.with(time);
        }
        if (isFunctionExpression(input)) {
            return compileFunctionExpression(input);
        }
        if (isOffsetExpression(input)) {
            return compileOffsetExpression(input);
        }
        return compileSunExpression(input);
    }

    private static boolean isFunctionExpression(String input) {
        String trimmed = input.trim();
        int openParen = trimmed.indexOf('(');
        int closeParen = trimmed.lastIndexOf(')');
        if (openParen <= 0 || closeParen != trimmed.length() - 1) {
            return false;
        }
        String functionName = trimmed.substring(0, openParen).trim();
        return functionName.matches("[A-Za-z][A-Za-z0-9_]*");
    }

    private StartTimeExpression compileFunctionExpression(String input) {
        String normalizedInput = input.trim();
        int openParen = normalizedInput.indexOf('(');
        int closeParen = normalizedInput.lastIndexOf(')');
        String functionName = normalizedInput.substring(0, openParen).trim().toLowerCase(Locale.ENGLISH);
        String argString = normalizedInput.substring(openParen + 1, closeParen);
        List<String> args = splitFunctionArguments(argString);

        return switch (functionName) {
            case "notbefore", "max" -> {
                requireArgCount(functionName, args, 2);
                StartTimeExpression a = compileExpression(args.get(0).trim());
                StartTimeExpression b = compileExpression(args.get(1).trim());
                yield dateTime -> {
                    ZonedDateTime resolvedA = a.resolve(dateTime);
                    ZonedDateTime resolvedB = b.resolve(dateTime);
                    return resolvedA.isAfter(resolvedB) ? resolvedA : resolvedB;
                };
            }
            case "notafter", "min" -> {
                requireArgCount(functionName, args, 2);
                StartTimeExpression a = compileExpression(args.get(0).trim());
                StartTimeExpression b = compileExpression(args.get(1).trim());
                yield dateTime -> {
                    ZonedDateTime resolvedA = a.resolve(dateTime);
                    ZonedDateTime resolvedB = b.resolve(dateTime);
                    return resolvedA.isBefore(resolvedB) ? resolvedA : resolvedB;
                };
            }
            case "clamp" -> {
                requireArgCount(functionName, args, 3);
                StartTimeExpression expression = compileExpression(args.get(0).trim());
                StartTimeExpression minimum = compileExpression(args.get(1).trim());
                StartTimeExpression maximum = compileExpression(args.get(2).trim());
                yield dateTime -> resolveClamp(expression, minimum, maximum, dateTime);
            }
            case "mix" -> {
                requireArgCount(functionName, args, 3);
                StartTimeExpression a = compileExpression(args.get(0).trim());
                StartTimeExpression b = compileExpression(args.get(1).trim());
                double weight = parseMixWeight(args.get(2).trim());
                yield dateTime -> resolveMix(a, b, weight, dateTime);
            }
            case "smooth" -> {
                requireArgCount(functionName, args, 2);
                StartTimeExpression expression = compileExpression(args.get(0).trim());
                double halfLifeDays = parseHalfLifeDays(args.get(1).trim());
                yield dateTime -> resolveSmooth(expression, halfLifeDays, dateTime);
            }
            default -> throw new InvalidStartTimeExpression("Unknown function: '" + functionName + "'");
        };
    }

    private static ZonedDateTime resolveClamp(StartTimeExpression expression, StartTimeExpression minimum,
                                              StartTimeExpression maximum, ZonedDateTime dateTime) {
        ZonedDateTime resolved = expression.resolve(dateTime);
        ZonedDateTime min = minimum.resolve(dateTime);
        ZonedDateTime max = maximum.resolve(dateTime);
        if (min.isAfter(max)) {
            log.warn("clamp() received inverted bounds at {}: min={}, max={}. Returning unclamped expression.",
                    dateTime, min, max);
            return resolved;
        }
        if (resolved.isBefore(min)) return min;
        if (resolved.isAfter(max)) return max;
        return resolved;
    }

    private static ZonedDateTime resolveMix(StartTimeExpression a, StartTimeExpression b, double weight,
                                            ZonedDateTime dateTime) {
        ZonedDateTime resolvedA = a.resolve(dateTime);
        ZonedDateTime resolvedB = b.resolve(dateTime);
        long mixedEpochSeconds = Math.round(
                resolvedA.toEpochSecond() * (1.0 - weight) + resolvedB.toEpochSecond() * weight);
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(mixedEpochSeconds), resolvedA.getZone());
    }

    private static ZonedDateTime resolveSmooth(StartTimeExpression expression, double halfLifeDays,
                                               ZonedDateTime dateTime) {
        double dailyDecay = Math.pow(0.5, 1.0 / halfLifeDays);
        int lookBackDays = Math.max(1, (int) Math.ceil(halfLifeDays * 8.0));
        ZonedDateTime start = expression.resolve(dateTime);
        double weight = 1.0;
        double weightSum = 0.0;
        double weightedSecondDelta = 0.0;
        for (int daysAgo = 0; daysAgo <= lookBackDays; daysAgo++) {
            ZonedDateTime previous = expression.resolve(dateTime.minusDays(daysAgo));
            weightedSecondDelta += getDelta(previous, start) * weight;
            weightSum += weight;
            weight *= dailyDecay;
        }
        long smoothedSecondOfDay = Math.round(
                start.toLocalTime().toSecondOfDay() + (weightedSecondDelta / weightSum));
        int normalizedSecondOfDay = Math.floorMod(smoothedSecondOfDay, SECONDS_IN_A_DAY);
        return dateTime.with(LocalTime.ofSecondOfDay(normalizedSecondOfDay));
    }

    private static double parseHalfLifeDays(String halfLifeArg) {
        try {
            double value = Double.parseDouble(getNumberPart(halfLifeArg));
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new InvalidStartTimeExpression(
                        "smooth() halfLife must be a finite positive number of days, got '" + halfLifeArg + "'");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidStartTimeExpression(
                    "smooth() halfLife must be a number of days (e.g. 14d), got '" + halfLifeArg + "'");
        }
    }

    private static String getNumberPart(String halfLifeArg) {
        String normalized = halfLifeArg.trim().toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "");
        if (normalized.endsWith("d")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static int getDelta(ZonedDateTime previous, ZonedDateTime start) {
        int delta = previous.toLocalTime().toSecondOfDay() - start.toLocalTime().toSecondOfDay();
        // Compensate DST jumps (typically +-1h)
        int offsetDeltaSeconds = start.getOffset().getTotalSeconds() - previous.getOffset().getTotalSeconds();
        if (offsetDeltaSeconds != 0 && Math.abs(delta) >= 2_700) {
            delta += offsetDeltaSeconds;
        }
        if (delta > SECONDS_IN_HALF_A_DAY) {
            delta -= SECONDS_IN_A_DAY;
        } else if (delta < -SECONDS_IN_HALF_A_DAY) {
            delta += SECONDS_IN_A_DAY;
        }
        return delta;
    }

    private static double parseMixWeight(String weightArg) {
        try {
            boolean percentage = weightArg.endsWith("%");
            String numericPart = percentage ? weightArg.substring(0, weightArg.length() - 1).trim() : weightArg;
            double parsed = Double.parseDouble(numericPart);
            if (!Double.isFinite(parsed)) {
                throw new InvalidStartTimeExpression(
                        "mix weight must be a finite number in [0..1] or percentage in [0%..100%], got '" +
                        weightArg + "'");
            }
            double normalized = percentage ? parsed / 100.0 : parsed;
            if (normalized < 0.0 || normalized > 1.0) {
                throw new InvalidStartTimeExpression(
                        "mix weight must be between 0 and 1 (or 0% and 100%), got " + weightArg);
            }
            return normalized;
        } catch (NumberFormatException e) {
            throw new InvalidStartTimeExpression(
                    "mix weight must be a number in [0..1] or percentage in [0%..100%], got '" + weightArg + "'");
        }
    }

    private static void requireArgCount(String functionName, List<String> args, int expected) {
        if (args.size() != expected) {
            throw new InvalidStartTimeExpression(
                    functionName + " requires exactly " + expected + " arguments, got " + args.size());
        }
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).trim().isEmpty()) {
                throw new InvalidStartTimeExpression(
                        functionName + " received an empty argument at position " + (i + 1));
            }
        }
    }

    private static List<String> splitFunctionArguments(String argString) {
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

    private static LocalTime tryParseTimeString(String input) {
        if (!Character.isDigit(input.charAt(0))) {
            return null;
        }
        try {
            return LocalTime.parse(input);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isOffsetExpression(String input) {
        return input.contains("+") || input.contains("-");
    }

    private StartTimeExpression compileOffsetExpression(String input) {
        String[] parts = input.split("[+-]", 2);
        if (parts.length != 2 || parts[1].trim().isEmpty()) {
            throw new InvalidStartTimeExpression("Invalid offset expression: '" + input + "'");
        }
        StartTimeExpression base = compileSunExpression(parts[0].trim());
        Duration offset = parseOffset(parts[1].trim());
        if (input.contains("+")) {
            return dateTime -> base.resolve(dateTime).plus(offset);
        }
        return dateTime -> base.resolve(dateTime).minus(offset);
    }

    private static Duration parseOffset(String offsetString) {
        Matcher matcher = OFFSET_PATTERN.matcher(offsetString);
        if (matcher.matches()) {
            return parseOffset(matcher);
        }
        return Duration.ofMinutes(Integer.parseInt(offsetString));
    }

    private static Duration parseOffset(Matcher matcher) {
        int hours = getIntFromGroup(matcher.group(1));
        int minutes = getIntFromGroup(matcher.group(2));
        int seconds = getIntFromGroup(matcher.group(3));
        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
    }

    private static int getIntFromGroup(String group) {
        return (group == null || group.isEmpty()) ? 0 : Integer.parseInt(group);
    }

    private StartTimeExpression compileSunExpression(String input) {
        return switch (input.toLowerCase(Locale.ENGLISH)) {
            case "astronomical_start", "astronomical_dawn" -> sunTimesProvider::getAstronomicalStart;
            case "nautical_start", "nautical_dawn" -> sunTimesProvider::getNauticalStart;
            case "civil_start", "civil_dawn" -> sunTimesProvider::getCivilStart;
            case "sunrise" -> sunTimesProvider::getSunrise;
            case "noon" -> sunTimesProvider::getNoon;
            case "golden_hour" -> sunTimesProvider::getGoldenHour;
            case "sunset" -> sunTimesProvider::getSunset;
            case "blue_hour" -> sunTimesProvider::getBlueHour;
            case "civil_end", "civil_dusk" -> sunTimesProvider::getCivilEnd;
            case "night_hour" -> sunTimesProvider::getNightHour;
            case "nautical_end", "nautical_dusk" -> sunTimesProvider::getNauticalEnd;
            case "astronomical_end", "astronomical_dusk" -> sunTimesProvider::getAstronomicalEnd;
            default -> throw new InvalidStartTimeExpression("Invalid sun keyword: '" + input + "'");
        };
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return sunTimesProvider.toDebugString(dateTime);
    }

    @Override
    public void clearCaches() {
        sunTimesProvider.clearCache();
        expressionCache.clear();
    }
}
