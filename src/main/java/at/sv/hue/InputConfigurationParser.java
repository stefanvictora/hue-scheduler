package at.sv.hue;

import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.hass.HassSupportedEntityType;
import at.sv.hue.color.CTToRGBConverter;
import at.sv.hue.color.RGBToXYConverter;
import at.sv.hue.time.StartTimeProvider;

import java.awt.*;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InputConfigurationParser {

    private static final Pattern TR_PATTERN = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)min)?(?:(\\d+)s)?(\\d*)?",
            Pattern.CASE_INSENSITIVE);

    private final StartTimeProvider startTimeProvider;
    private final HueApi api;
    private final int minTrBeforeGapInMinutes;
    private final int brightnessOverrideThreshold;
    private final int colorTemperatureOverrideThresholdKelvin;
    private final double colorOverrideThreshold;
    private final boolean interpolateAll;

    public InputConfigurationParser(StartTimeProvider startTimeProvider, HueApi api, int minTrBeforeGapInMinutes,
                                    int brightnessOverrideThreshold, int colorTemperatureOverrideThresholdKelvin,
                                    double colorOverrideThreshold, boolean interpolateAll) {
        this.startTimeProvider = startTimeProvider;
        this.api = api;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.colorOverrideThreshold = colorOverrideThreshold;
        this.interpolateAll = interpolateAll;
        this.brightnessOverrideThreshold = brightnessOverrideThreshold;
        this.colorTemperatureOverrideThresholdKelvin = colorTemperatureOverrideThresholdKelvin;
    }

    public List<ScheduledState> parse(String input) {
        String[] parts = input.split("\\t+|\\s{2,}");
        if (parts.length < 2)
            throw new InvalidConfigurationLine("Invalid configuration line format '" + Arrays.toString(parts) + "': at least id and start time have to be set." +
                                               " Make sure to use either tabs or at least two spaces to separate the different configuration parts.");
        ArrayList<ScheduledState> states = new ArrayList<>();
        for (String reference : parts[0].split(",")) {
            reference = reference.trim();
            boolean groupState;
            Identifier identifier;
            if (reference.matches("g\\d+")) {
                identifier = api.getGroupIdentifier("/groups/" + reference.substring(1));
                groupState = true;
            } else if (reference.matches("\\d+")) {
                identifier = api.getLightIdentifier("/lights/" + reference);
                groupState = false;
            } else if (HassSupportedEntityType.isSupportedEntityType(reference)) {
                identifier = api.getLightIdentifier(reference);
                groupState = false;
            } else {
                try {
                    identifier = api.getGroupIdentifierByName(reference);
                    groupState = true;
                } catch (GroupNotFoundException e) {
                    identifier = api.getLightIdentifierByName(reference);
                    groupState = false;
                }
            }
            LightCapabilities capabilities;
            if (groupState) {
                capabilities = api.getGroupCapabilities(identifier.id());
            } else {
                capabilities = api.getLightCapabilities(identifier.id());
            }
            Integer bri = null;
            Integer ct = null;
            Boolean on = null;
            Boolean force = null;
            Boolean interpolate = null;
            Double x = null;
            Double y = null;
            String transitionTimeBefore = null;
            Integer transitionTime = null;
            String effect = null;
            String scene = null;
            if (interpolateAll) {
                interpolate = Boolean.TRUE;
            }
            EnumSet<DayOfWeek> dayOfWeeks = EnumSet.noneOf(DayOfWeek.class);
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i].trim();
                String[] typeAndValue = part.split(":", 2);
                if (typeAndValue.length != 2) {
                    throw new InvalidConfigurationLine("Invalid state property '" + part + "': " +
                                                       "Each state property has to be in the format 'property:value'.");
                }
                String parameter = typeAndValue[0];
                String value = typeAndValue[1];
                switch (parameter) {
                    case "bri":
                        bri = parseBrightness(value);
                        break;
                    case "ct":
                        ct = parseInteger(value, parameter);
                        if (ct >= 1_000) {
                            ct = convertToMiredCt(ct);
                        }
                        if (capabilities.isColorSupported() && ct > capabilities.getCtMax()) {
                            int[] rgb = CTToRGBConverter.approximateRGBFromMired(ct);
                            RGBToXYConverter.XYColor xyColor = RGBToXYConverter.rgbToXY(rgb[0], rgb[1], rgb[2],
                                    capabilities.getColorGamut());
                            x = xyColor.x();
                            y = xyColor.y();
                            ct = null;
                        }
                        break;
                    case "on":
                        on = Boolean.valueOf(value);
                        break;
                    case "tr":
                        transitionTime = parseTransitionTime(parameter, value);
                        break;
                    case "tr-before":
                        transitionTimeBefore = value;
                        break;
                    case "x":
                        x = parseDouble(value, parameter);
                        break;
                    case "y":
                        y = parseDouble(value, parameter);
                        break;
                    case "color":
                        int red;
                        int green;
                        int blue;
                        if (value.contains(",")) {
                            String[] rgb = value.split(",");
                            if (rgb.length != 3) {
                                throw new InvalidPropertyValue("Invalid RGB value '" + value + "'. Make sure to separate the color values with ','.");
                            }
                            red = parseInteger(rgb[0], "color");
                            green = parseInteger(rgb[1], "color");
                            blue = parseInteger(rgb[2], "color");
                        } else {
                            Color color = Color.decode(value);
                            red = color.getRed();
                            green = color.getGreen();
                            blue = color.getBlue();
                        }
                        RGBToXYConverter.XYColor xyColor = RGBToXYConverter.rgbToXY(red, green, blue,
                                capabilities.getColorGamut());
                        x = xyColor.x();
                        y = xyColor.y();
                        if (bri == null) {
                            bri = xyColor.brightness();
                        }
                        break;
                    case "effect":
                        effect = value;
                        break;
                    case "days":
                        DayOfWeeksParser.parseDayOfWeeks(value, dayOfWeeks);
                        break;
                    case "force":
                        force = parseBoolean(value, parameter);
                        break;
                    case "interpolate":
                        interpolate = parseBoolean(value, parameter);
                        break;
                    case "scene":
                        scene = value;
                        break;
                    default:
                        throw new UnknownStateProperty("Unknown state property '" + parameter + "' with value '" + value + "'");
                }
            }
            String start = parts[1];

            List<ScheduledLightState> scheduledLightStates;
            if (scene != null) {
                if (ct != null || x != null || y != null || effect != null) {
                    throw new InvalidConfigurationLine(
                            "When 'scene' is used, only 'on', 'bri', 'tr', 'tr-before', 'days', 'force', 'interpolate' are allowed.");
                }

                scheduledLightStates = api.getSceneLightState(identifier.id(), scene);
                if (bri != null) {
                    scheduledLightStates = scaleBrightness(bri, scheduledLightStates);
                }
                if (on == Boolean.TRUE) {
                    scheduledLightStates = turnOnIfNotOff(scheduledLightStates);
                }
                if (on == Boolean.FALSE) {
                    throw new InvalidConfigurationLine("Can't combine 'on:false' with a scene: " + Arrays.toString(parts));
                }
            } else {
                ScheduledLightStateValidator validator = new ScheduledLightStateValidator(identifier, groupState, capabilities,
                        capBrightness(bri), ct, x, y, on, effect);
                scheduledLightStates = List.of(validator.getScheduledLightState());
            }

            states.add(new ScheduledState(identifier, start, scheduledLightStates, transitionTimeBefore,
                    transitionTime, dayOfWeeks, startTimeProvider, minTrBeforeGapInMinutes, brightnessOverrideThreshold,
                    colorTemperatureOverrideThresholdKelvin, colorOverrideThreshold, force, interpolate, groupState, false));
        }
        return states;
    }

    private static List<ScheduledLightState> scaleBrightness(Integer targetBrightness, List<ScheduledLightState> scheduledLightStates) {
        return scheduledLightStates.stream()
                                   .map(state -> {
                                       if (state.getBri() != null) {
                                           // Proportional scaling: targetBrightness acts as percentage of maximum
                                           double scaleFactor = targetBrightness / 254.0;
                                           int newBri = capBrightness((int) Math.round(state.getBri() * scaleFactor));
                                           return state.toBuilder().bri(newBri).build();
                                       }
                                       return state;
                                   })
                                   .toList();
    }

    private static Integer capBrightness(Integer targetBrightness) {
        if (targetBrightness == null) {
            return null;
        }
        return Math.max(1, Math.min(254, targetBrightness));
    }

    private static List<ScheduledLightState> turnOnIfNotOff(List<ScheduledLightState> scheduledLightStates) {
        return scheduledLightStates.stream().map(InputConfigurationParser::turnOnIfNotOff).toList();
    }

    private static ScheduledLightState turnOnIfNotOff(ScheduledLightState state) {
        if (state.isOff()) {
            return state;
        }
        return state.toBuilder().on(true).build();
    }

    private Integer parseBrightness(String value) {
        if (value.endsWith("%")) {
            return parseBrightnessPercentValue(value);
        }
        return parseInteger(value, "bri");
    }

    /**
     * Calculates the brightness value [1-254]. This uses an adapted percentage range of [1%-100%]. Treating 1% as the min value. To make sure, we
     * also handle the special case of 0% and treat is as 1%.
     */
    private int parseBrightnessPercentValue(String value) {
        String percentString = value.replace("%", "").trim();
        Double percent = parseDouble(percentString, "bri");
        return parseBrightnessPercentValue(percent);
    }

    static int parseBrightnessPercentValue(double percent) {
        if (percent < 1) {
            return 1;
        }
        return (int) Math.round((254.0 - 1.0) * (percent - 1) / 99.0 + 1.0);
    }

    private static Integer parseInteger(String value, String parameter) {
        return parseValueWithErrorHandling(value.trim(), parameter, "integer", Integer::valueOf);
    }

    private Double parseDouble(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "double", Double::parseDouble);
    }

    private Boolean parseBoolean(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "boolean", Boolean::parseBoolean);
    }

    private static <T> T parseValueWithErrorHandling(String value, String parameter, String type, Function<String, T> function) {
        try {
            return function.apply(value);
        } catch (Exception e) {
            throw new InvalidPropertyValue("Invalid " + type + " '" + value + "' for property '" + parameter + "'.");
        }
    }

    public static int parseTransitionTime(String parameter, String s) {
        Matcher matcher = TR_PATTERN.matcher(s);
        if (!matcher.matches()) {
            throw new InvalidPropertyValue("Invalid transition time '" + s + "' for property '" + parameter + "'.");
        }
        int hours = getIntFromGroup(matcher.group(1), parameter);
        int minutes = getIntFromGroup(matcher.group(2), parameter);
        int seconds = getIntFromGroup(matcher.group(3), parameter);
        int milliseconds = getIntFromGroup(matcher.group(4), parameter);
        return hours * 36000 + minutes * 600 + seconds * 10 + milliseconds;
    }

    private static int getIntFromGroup(String group, String parameter) {
        return (group == null || group.isEmpty()) ? 0 : parseInteger(group, parameter);
    }

    private Integer convertToMiredCt(Integer kelvin) {
        return 1_000_000 / kelvin;
    }
}
