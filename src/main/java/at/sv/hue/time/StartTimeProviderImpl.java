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
    public LocalTime getStart(String input, ZonedDateTime dateTime) {
        LocalTime time = tryParseTimeString(input);
        if (time != null) return time;
        try {
            if (isOffsetExpression(input)) {
                return parseOffsetExpresion(input, dateTime);
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

    private LocalTime parseOffsetExpresion(String input, ZonedDateTime dateTime) {
        String[] parts = input.split("[+-]");
        LocalTime localTime = parseSunKeywords(parts[0].trim(), dateTime);
        int offset = Integer.parseInt(parts[1].trim());
        if (input.contains("+")) {
            return localTime.plusMinutes(offset);
        } else {
            return localTime.minusMinutes(offset);
        }
    }

    private LocalTime parseSunKeywords(String input, ZonedDateTime dateTime) {
        switch (input.toLowerCase(Locale.ENGLISH)) {
            case "sunrise":
                return sunTimesProvider.getSunrise(dateTime);
            case "golden_hour":
                return sunTimesProvider.getGoldenHour(dateTime);
            case "sunset":
                return sunTimesProvider.getSunset(dateTime);
            case "nautical_start":
                return sunTimesProvider.getNauticalStart(dateTime);
            case "nautical_end":
                return sunTimesProvider.getNauticalEnd(dateTime);
            case "civil_start":
                return sunTimesProvider.getCivilStart(dateTime);
            case "civil_end":
                return sunTimesProvider.getCivilEnd(dateTime);
            case "astronomical_start":
                return sunTimesProvider.getAstronomicalStart(dateTime);
            case "astronomical_end":
                return sunTimesProvider.getAstronomicalEnd(dateTime);
        }
        throw new IllegalArgumentException("Invalid sun keyword: '" + input + "'");
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return sunTimesProvider.toDebugString(dateTime);
    }
}
