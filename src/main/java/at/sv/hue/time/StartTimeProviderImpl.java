package at.sv.hue.time;

import java.time.LocalTime;
import java.time.ZonedDateTime;
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
            if (isOffsetExpression(input)) {
                return parseOffsetExpression(input, dateTime);
            }
            return parseSunKeywords(input, dateTime);
        } catch (Exception e) {
            throw new InvalidStartTimeExpression("Failed to parse start time expression '" + input + "': " + e.getMessage());
        }
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
