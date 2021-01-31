package at.sv.hue;

import java.time.LocalTime;
import java.util.Locale;

public final class StartTimeProviderImpl implements StartTimeProvider {

    private final SunDataProvider sunDataProvider;

    public StartTimeProviderImpl(SunDataProvider sunDataProvider) {
        this.sunDataProvider = sunDataProvider;
    }

    @Override
    public LocalTime getStart(String input) {
        LocalTime time = tryParseTimeString(input);
        if (time != null) return time;
        if (isOffsetExpression(input)) {
            return parseOffsetExpresion(input);
        }
        return parseSunKeywords(input);
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

    private LocalTime parseOffsetExpresion(String input) {
        String[] parts = input.split("[+-]");
        LocalTime localTime = parseSunKeywords(parts[0].trim());
        int offset = Integer.parseInt(parts[1].trim());
        if (input.contains("+")) {
            return localTime.plusMinutes(offset);
        } else {
            return localTime.minusMinutes(offset);
        }
    }

    private LocalTime parseSunKeywords(String input) {
        switch (input.toLowerCase(Locale.ENGLISH)) {
            case "sunrise":
                return sunDataProvider.getSunrise();
            case "sunset":
                return sunDataProvider.getSunset();
            case "nautical_start":
                return sunDataProvider.getNauticalStart();
            case "nautical_end":
                return sunDataProvider.getNauticalEnd();
            case "civil_start":
                return sunDataProvider.getCivilStart();
            case "civil_end":
                return sunDataProvider.getCivilEnd();
        }
        throw new IllegalArgumentException("Invalid sun keyword: " + input);
    }
}
