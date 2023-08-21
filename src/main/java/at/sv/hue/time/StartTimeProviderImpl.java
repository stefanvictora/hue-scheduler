package at.sv.hue.time;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

public final class StartTimeProviderImpl implements StartTimeProvider {

    private final SunTimesProvider sunTimesProvider;

    public StartTimeProviderImpl(SunTimesProvider sunTimesProvider) {
        this.sunTimesProvider = sunTimesProvider;
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
        LocalTime time = null;
        try {
            time = LocalTime.parse(input);
        } catch (Exception ignore) {
        }
        return time;
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
        switch (input.toLowerCase(Locale.ENGLISH)) {
            case "astronomical_start":
            case "astronomical_dawn":
                return sunTimesProvider.getAstronomicalStart(dateTime);
            case "nautical_start":
            case "nautical_dawn":
                return sunTimesProvider.getNauticalStart(dateTime);
            case "civil_start":
            case "civil_dawn":
                return sunTimesProvider.getCivilStart(dateTime);
            case "sunrise":
                return sunTimesProvider.getSunrise(dateTime);
            case "noon":
                return sunTimesProvider.getNoon(dateTime);
            case "golden_hour":
                return sunTimesProvider.getGoldenHour(dateTime);
            case "sunset":
                return sunTimesProvider.getSunset(dateTime);
            case "blue_hour":
                return sunTimesProvider.getBlueHour(dateTime);
            case "civil_end":
            case "civil_dusk":
                return sunTimesProvider.getCivilEnd(dateTime);
            case "night_hour":
                return sunTimesProvider.getNightHour(dateTime);
            case "nautical_end":
            case "nautical_dusk":
                return sunTimesProvider.getNauticalEnd(dateTime);
            case "astronomical_end":
            case "astronomical_dusk":
                return sunTimesProvider.getAstronomicalEnd(dateTime);
        }
        throw new IllegalArgumentException("Invalid sun keyword: '" + input + "'");
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return sunTimesProvider.toDebugString(dateTime);
    }
}
