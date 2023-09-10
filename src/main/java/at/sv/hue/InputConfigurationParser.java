package at.sv.hue;

import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.color.RGBToXYConverter;
import at.sv.hue.time.StartTimeProvider;

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
    private final HueApi hueApi;
    private final int minTrBeforeGapInMinutes;
    private final boolean interpolateAll;

    public InputConfigurationParser(StartTimeProvider startTimeProvider, HueApi hueApi, int minTrBeforeGapInMinutes,
                                    boolean interpolateAll) {
        this.startTimeProvider = startTimeProvider;
        this.hueApi = hueApi;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.interpolateAll = interpolateAll;
    }

    public List<ScheduledState> parse(String input) {
        String[] parts = input.split("\\t|\\s{2,}");
        if (parts.length < 2)
            throw new InvalidConfigurationLine("Invalid configuration line format '" + Arrays.toString(parts) + "': at least id and start time have to be set." +
                    " Make sure to use either tabs or at least two spaces to separate the different configuration parts.");
        ArrayList<ScheduledState> states = new ArrayList<>();
        for (String idPart : parts[0].split(",")) {
            idPart = idPart.trim();
            int id;
            boolean groupState;
            String name = "";
            if (idPart.matches("g\\d+")) {
                id = Integer.parseInt(idPart.substring(1));
                name = hueApi.getGroupName(id);
                groupState = true;
            } else if (idPart.matches("\\d+")) {
                id = Integer.parseInt(idPart);
                name = hueApi.getLightName(id);
                groupState = false;
            } else {
                name = idPart;
                try {
                    id = hueApi.getGroupId(idPart);
                    groupState = true;
                } catch (GroupNotFoundException e) {
                    id = hueApi.getLightId(idPart);
                    groupState = false;
                }
            }
            LightCapabilities capabilities;
            if (groupState) {
                capabilities = hueApi.getGroupCapabilities(id);
            } else {
                capabilities = hueApi.getLightCapabilities(id);
            }
            Integer bri = null;
            Integer ct = null;
            Boolean on = null;
            Boolean force = null;
            Boolean interpolate = null;
            Double x = null;
            Double y = null;
            Integer hue = null;
            Integer sat = null;
            String transitionTimeBefore = null;
            Integer transitionTime = null;
            String effect = null;
            if (interpolateAll) {
                interpolate = Boolean.TRUE;
            }
            EnumSet<DayOfWeek> dayOfWeeks = EnumSet.noneOf(DayOfWeek.class);
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i].trim();
                String[] typeAndValue = part.split(":", 2);
                String parameter = typeAndValue[0];
                String value = typeAndValue[1];
                switch (parameter) {
                    case "bri":
                        bri = parseBrightness(value);
                        break;
                    case "ct":
                        ct = parseInteger(value, parameter);
                        if (ct >= 2_000) {
                            ct = convertToMiredCt(ct);
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
                    case "hue":
                        hue = parseInteger(value, parameter);
                        break;
                    case "sat":
                        sat = parseSaturation(value);
                        break;
                    case "color":
                        RGBToXYConverter.XYColor xyColor;
                        if (value.contains(",")) {
                            String[] rgb = value.split(",");
                            if (rgb.length != 3) {
                                throw new InvalidPropertyValue("Invalid RGB value '" + value + "'. Make sure to separate the color values with ','.");
                            }
                            xyColor = RGBToXYConverter.convert(rgb[0], rgb[1], rgb[2], capabilities.getColorGamut());
                        } else {
                            xyColor = RGBToXYConverter.convert(value, capabilities.getColorGamut());
                        }
                        x = xyColor.getX();
                        y = xyColor.getY();
                        if (bri == null) {
                            bri = xyColor.getBrightness();
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
                    default:
                        throw new UnknownStateProperty("Unknown state property '" + parameter + "' with value '" + value + "'");
                }
            }
            String start = parts[1];
            states.add(new ScheduledState(name, id, start, bri, ct, x, y, hue, sat, effect, on, transitionTimeBefore,
                    transitionTime, dayOfWeeks, startTimeProvider, capabilities, minTrBeforeGapInMinutes, force,
                    interpolate, groupState, false));
        }
        return states;
    }

    private Integer parseBrightness(String value) {
        if (value.endsWith("%")) {
            return parseBrightnessPercentValue(value);
        }
        return parseInteger(value, "bri");
    }

    private Integer parseSaturation(String value) {
        if (value.endsWith("%")) {
            return parseSaturationPercentValue(value);
        }
        return parseInteger(value, "sat");
    }

    /**
     * Calculates the brightness value [1-254]. This uses an adapted percentage range of [1%-100%]. Treating 1% as the min value. To make sure, we
     * also handle the special case of 0% and treat is as 1%.
     */
    private int parseBrightnessPercentValue(String value) {
        String percentString = value.replace("%", "").trim();
        Double percent = parseDouble(percentString, "bri");
        if (percent < 1) {
            return 1;
        }
        if (percent > 100) {
            return 254;
        }
        return (int) Math.round((254.0 - 1.0) * (percent - 1) / 99.0 + 1.0);
    }

    private int parseSaturationPercentValue(String value) {
        String percentString = value.replace("%", "").trim();
        Double percent = parseDouble(percentString, "sat");
        if (percent < 0) {
            return 0;
        }
        if (percent > 100) {
            return 254;
        }
        return (int) Math.round(254.0 * percent / 100.0);
    }

    private static Integer parseInteger(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "integer", Integer::valueOf);
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
