package at.sv.hue;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

public final class StartTimeProviderImpl implements StartTimeProvider {

    private final SunDataProvider sunDataProvider;

    public StartTimeProviderImpl(SunDataProvider sunDataProvider) {
        this.sunDataProvider = sunDataProvider;
    }

    @Override
    public LocalTime getStart(String input, ZonedDateTime dateTime) {
        LocalTime time = tryParseTimeString(input);
        if (time != null) return time;
        if (isOffsetExpression(input)) {
            return parseOffsetExpresion(input, dateTime);
        }
        return parseSunKeywords(input, dateTime);
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
                return sunDataProvider.getSunrise(dateTime);
            case "golden_hour":
                return sunDataProvider.getGoldenHour(dateTime);
            case "sunset":
                return sunDataProvider.getSunset(dateTime);
            case "nautical_start":
                return sunDataProvider.getNauticalStart(dateTime);
            case "nautical_end":
                return sunDataProvider.getNauticalEnd(dateTime);
            case "civil_start":
                return sunDataProvider.getCivilStart(dateTime);
            case "civil_end":
                return sunDataProvider.getCivilEnd(dateTime);
            case "astronomical_start":
                return sunDataProvider.getAstronomicalStart(dateTime);
            case "astronomical_end":
                return sunDataProvider.getAstronomicalEnd(dateTime);
        }
        throw new IllegalArgumentException("Invalid sun keyword: " + input);
    }

    @Override
    public String toDebugString(ZonedDateTime dateTime) {
        return sunDataProvider.toDebugString(dateTime);
    }
}
