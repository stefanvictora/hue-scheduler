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

public final class InputConfigurationParser {

    private final StartTimeProvider startTimeProvider;
    private final HueApi hueApi;

    public InputConfigurationParser(StartTimeProvider startTimeProvider, HueApi hueApi) {
        this.startTimeProvider = startTimeProvider;
        this.hueApi = hueApi;
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
            if (!groupState) {
                capabilities = hueApi.getLightCapabilities(id);
            } else {
                capabilities = LightCapabilities.NO_CAPABILITIES;
            }
            Integer bri = null;
            Integer ct = null;
            Boolean on = null;
            Boolean force = null;
            Double x = null;
            Double y = null;
            Integer hue = null;
            Integer sat = null;
            Integer transitionTimeBefore = null;
            Integer transitionTime = null;
            String effect = null;
            EnumSet<DayOfWeek> dayOfWeeks = EnumSet.noneOf(DayOfWeek.class);
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i];
                String[] typeAndValue = part.split(":");
                String parameter = typeAndValue[0];
                String value = typeAndValue[1];
                switch (parameter) {
                    case "bri":
                        bri = parseInteger(value, parameter);
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
                        transitionTimeBefore = parseTransitionTime(parameter, value);
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
                        sat = parseInteger(value, parameter);
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
                    default:
                        throw new UnknownStateProperty("Unknown state property '" + parameter + "' with value '" + value + "'");
                }
            }
            String start = parts[1];
            states.add(createState(name, id, start, bri, ct, x, y, hue, sat, effect, on, transitionTimeBefore,
                    transitionTime, dayOfWeeks, capabilities, groupState, force));
        }
        return states;
    }

    private Integer parseInteger(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "integer", Integer::valueOf);
    }

    private Double parseDouble(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "double", Double::parseDouble);
    }

    private Boolean parseBoolean(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "boolean", Boolean::parseBoolean);
    }

    private <T> T parseValueWithErrorHandling(String value, String parameter, String type, Function<String, T> function) {
        try {
            return function.apply(value);
        } catch (Exception e) {
            throw new InvalidPropertyValue("Invalid " + type + " '" + value + "' for property '" + parameter + "'.");
        }
    }

    private Integer parseTransitionTime(String parameter, String s) {
        String value = s;
        int modifier = 1;
        if (s.endsWith("s")) {
            value = s.substring(0, s.length() - 1);
            modifier = 10;
        } else if (s.endsWith("min")) {
            value = s.substring(0, s.length() - 3);
            modifier = 600;
        }
        return parseInteger(value.trim(), parameter) * modifier;
    }

    private Integer convertToMiredCt(Integer kelvin) {
        return 1_000_000 / kelvin;
    }

    private ScheduledState createState(String name, int id, String start, Integer brightness, Integer ct, Double x, Double y,
                                       Integer hue, Integer sat, String effect, Boolean on, Integer transitionTimeBefore, Integer transitionTime,
                                       EnumSet<DayOfWeek> dayOfWeeks, LightCapabilities capabilities, boolean groupState, Boolean force) {
        List<Integer> groupLights;
        if (groupState) {
            groupLights = getGroupLights(id);
        } else {
            groupLights = null;
        }
        return new ScheduledState(name, id, start, brightness, ct, x, y, hue, sat, effect, on, transitionTimeBefore,
                transitionTime, dayOfWeeks, startTimeProvider, groupState, groupLights, capabilities, force);
    }

    private List<Integer> getGroupLights(int groupId) {
        return hueApi.getGroupLights(groupId);
    }
}
