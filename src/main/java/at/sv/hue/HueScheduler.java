package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.color.RGBToXYConverter;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@Command(name = "HueScheduler", version = "1.0-SNAPSHOT", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<Integer, List<ScheduledState>> lightStates;
    @Parameters(description = "The IP address of your Philips Hue Bridge.")
    String ip;
    @Parameters(description = "The Philips Hue Bridge username created for Hue Scheduler during setup.")
    String username;
    @Parameters(paramLabel = "FILE", description = "The configuration file containing your schedules.")
    Path inputFile;
    @Option(names = "--lat", required = true, description = "The latitude of your location.")
    double latitude;
    @Option(names = "--long", required = true, description = "The longitude of your location.")
    double longitude;
    @Option(names = "--elevation", description = "The optional elevation (in meters) of your location, used to provide more accurate sunrise and sunset times.",
            defaultValue = "0.0")
    double elevation;
    @Option(names = "--retry-delay", paramLabel = "<delay>",
            description = "The maximum amount of seconds Hue Scheduler should wait before retrying to control a light that was not reachable." +
                    " Default: ${DEFAULT-VALUE} seconds.",
            defaultValue = "5")
    int maxRetryDelayInSeconds;
    @Option(names = "--confirm-delay", hidden = true, defaultValue = "6")
    int confirmDelayInSeconds;
    private HueApi hueApi;
    private StateScheduler stateScheduler;
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;
    private Supplier<Integer> retryDelay;

    public HueScheduler() {
        lightStates = new HashMap<>();
    }

    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler, StartTimeProvider startTimeProvider,
                        Supplier<ZonedDateTime> currentTime, Supplier<Integer> retryDelayInMs, int confirmDelayInSeconds) {
        this();
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        this.retryDelay = retryDelayInMs;
        this.confirmDelayInSeconds = confirmDelayInSeconds;
    }

    public static void main(String[] args) {
        int execute = new CommandLine(new HueScheduler()).execute(args);
        if (execute != 0) {
            System.exit(execute);
        }
    }

    @Override
    public void run() {
        hueApi = new HueApiImpl(new HttpResourceProviderImpl(), ip, username);
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        currentTime = ZonedDateTime::now;
        retryDelay = this::getRandomRetryDelayMs;
        assertInputIsReadable();
        parseInput();
        start();
    }

    private StartTimeProviderImpl createStartTimeProvider(double latitude, double longitude, double elevation) {
        return new StartTimeProviderImpl(new SunTimesProviderImpl(latitude, longitude, elevation));
    }

    private StateSchedulerImpl createStateScheduler() {
        return new StateSchedulerImpl(Executors.newSingleThreadScheduledExecutor(), ZonedDateTime::now);
    }

    private void assertInputIsReadable() {
        if (!Files.isReadable(inputFile)) {
            System.err.println("Given input file '" + inputFile.toAbsolutePath() + "' does not exist or is not readable!");
            System.exit(1);
        }
    }

    private void parseInput() {
        try {
            Files.lines(inputFile)
                 .filter(s -> !s.isEmpty())
                 .filter(s -> !s.startsWith("//") && !s.startsWith("#"))
                 .forEachOrdered(input -> {
                     try {
                         addState(input);
                     } catch (Exception e) {
                         System.err.println("Failed to parse configuration line '" + input + "':\n" + e.getClass().getSimpleName() + ": " + e.getLocalizedMessage());
                         System.exit(2);
                     }
                 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int getRandomRetryDelayMs() {
        return ThreadLocalRandom.current().nextInt(1000, maxRetryDelayInSeconds * 1000 + 1);
    }

    public void addState(String input) {
        String[] parts = input.split("\\t|\\s{4}");
        if (parts.length < 2)
            throw new InvalidConfigurationLine("Invalid configuration line '" + Arrays.toString(parts) + "': at least id and start time have to be set!");
        for (String idPart : parts[0].split(",")) {
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
            Double x = null;
            Double y = null;
            Integer hue = null;
            Integer sat = null;
            Integer transitionTimeBefore = null;
            Integer transitionTime = null;
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
                        break;
                    case "k":
                        ct = convertToMiredCt(parseInteger(value, parameter));
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
                    case "hex":
                        RGBToXYConverter.XYColor xyColor = RGBToXYConverter.convert(value, capabilities.getColorGamut());
                        x = xyColor.getX();
                        y = xyColor.getY();
                        if (bri == null) {
                            bri = xyColor.getBrightness();
                        }
                        break;
                    case "rgb":
                        String[] rgb = value.split(",");
                        if (rgb.length != 3) {
                            throw new InvalidPropertyValue("Invalid RGB value '" + value + "'. Make sure to separate the colors with ','.");
                        }
                        RGBToXYConverter.XYColor xy = RGBToXYConverter.convert(rgb[0], rgb[1], rgb[2], capabilities.getColorGamut());
                        x = xy.getX();
                        y = xy.getY();
                        if (bri == null) {
                            bri = xy.getBrightness();
                        }
                        break;
                    default:
                        throw new UnknownStateProperty("Unknown state property '" + parameter + "' with value '" + value + "'");
                }
            }
            String start = parts[1];
            if (groupState) {
                addGroupState(name, id, start, bri, ct, x, y, hue, sat, on, transitionTimeBefore, transitionTime);
            } else {
                addState(name, id, start, bri, ct, x, y, hue, sat, on, transitionTimeBefore, transitionTime, capabilities);
            }
        }
    }

    private Integer parseInteger(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "integer", Integer::valueOf);
    }

    private Double parseDouble(String value, String parameter) {
        return parseValueWithErrorHandling(value, parameter, "double", Double::parseDouble);
    }

    private <T> T parseValueWithErrorHandling(String value, String parameter, String type, Function<String, T> function) {
        try {
            return function.apply(value);
        } catch (Exception e) {
            throw new InvalidPropertyValue("Invalid " + type + " value '" + value + " for property '" + parameter + "'.");
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

    public void addState(String name, int lampId, String start, Integer brightness, Integer ct, Double x, Double y,
                         Integer hue, Integer sat, Boolean on, Integer transitionTimeBefore, Integer transitionTime,
                         LightCapabilities capabilities) {
        lightStates.computeIfAbsent(lampId, ArrayList::new)
                   .add(new ScheduledState(name, lampId, start, brightness, ct, x, y, hue, sat, on, transitionTimeBefore,
                           transitionTime, startTimeProvider, capabilities));
    }

    public void addGroupState(String name, int groupId, String start, Integer brightness, Integer ct, Double x, Double y,
                              Integer hue, Integer sat, Boolean on, Integer transitionTimeBefore, Integer transitionTime) {
        lightStates.computeIfAbsent(getGroupId(groupId), ArrayList::new)
                   .add(new ScheduledState(name, groupId, getGroupLights(groupId).get(0), start, brightness, ct, x, y,
                           hue, sat, on, transitionTimeBefore, transitionTime, startTimeProvider, true,
                           LightCapabilities.NO_CAPABILITIES));
    }

    private int getGroupId(int id) {
        return id + 1000;
    }

    private List<Integer> getGroupLights(int groupId) {
        return hueApi.getGroupLights(groupId);
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        lightStates.forEach((id, states) -> {
            calculateAndSetEndTimes(now, states);
            scheduleStates(now, states);
        });
        scheduleSunDataInfoLog();
    }

    private void calculateAndSetEndTimes(ZonedDateTime now, List<ScheduledState> states) {
        sortByTimeAscending(states, now);
        for (int i = 1; i < states.size(); i++) {
            ScheduledState previous = states.get(i - 1);
            ScheduledState current = states.get(i);
            previous.setEnd(getRightBeforeStartOfState(current, now));
        }
        ScheduledState lastState = states.get(states.size() - 1);
        lastState.setEnd(getRightBeforeStartOfStateNextDay(states.get(0), now));
    }

    private void sortByTimeAscending(List<ScheduledState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(scheduledState -> scheduledState.getStart(now)));
    }

    private ZonedDateTime getRightBeforeStartOfState(ScheduledState state, ZonedDateTime dateTime) {
        return ZonedDateTime.of(LocalDateTime.of(dateTime.toLocalDate(), state.getStart(dateTime).minusSeconds(1)), dateTime.getZone());
    }

    private ZonedDateTime getRightBeforeStartOfStateNextDay(ScheduledState state, ZonedDateTime now) {
        return getRightBeforeStartOfState(state, now.plusDays(1));
    }

    private void scheduleStates(ZonedDateTime now, List<ScheduledState> states) {
        sortByLastFirst(states, now);
        if (allInTheFuture(states, now)) {
            scheduleTemporaryCopyOfLastImmediately(states, now);
        }
        for (int i = 0; i < states.size(); i++) {
            ScheduledState state = states.get(i);
            schedule(state, now);
            if (state.isInThePast(now) && hasMorePastStates(states, i)) {
                addRemainingStatesTheNextDay(getRemaining(states, i), now);
                break;
            }
        }
    }

    private void sortByLastFirst(List<ScheduledState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(scheduledState -> scheduledState.getStart(now), Comparator.reverseOrder()));
    }

    private boolean allInTheFuture(List<ScheduledState> states, ZonedDateTime now) {
        return !states.get(states.size() - 1).isInThePast(now);
    }

    private void scheduleTemporaryCopyOfLastImmediately(List<ScheduledState> states, ZonedDateTime now) {
        ZonedDateTime newEnd = getRightBeforeStartOfState(states.get(states.size() - 1), now);
        ScheduledState temporaryCopy = ScheduledState.createTemporaryCopy(states.get(0), now, newEnd);
        schedule(temporaryCopy, 0);
    }

    private void schedule(ScheduledState state, ZonedDateTime now) {
        state.updateLastStart(now);
        schedule(state, state.getDelayInSeconds(now) * 1000L);
    }

    private void schedule(ScheduledState state, long delayInMs) {
        if (state.isNullState()) return;
        if (delayInMs == 0 || delayInMs > Math.max(5000, confirmDelayInSeconds * 1000)) {
            LOG.debug("Schedule {} in {}", state, Duration.ofMillis(delayInMs));
        }
        stateScheduler.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                LOG.debug("{} already ended", state);
                scheduleNextDay(state);
                return;
            }
            boolean success = hueApi.putState(state.getUpdateId(), state.getBrightness(), state.getCt(), state.getX(), state.getY(),
                    state.getHue(), state.getSat(), state.getOn(), state.getTransitionTime(currentTime.get()), state.isGroupState());
            LightState lightState = null;
            if (success) {
                lightState = hueApi.getLightState(state.getStatusId());
            }
            if (success && state.isOff() && lightState.isUnreachableOrOff()) {
                LOG.debug("{} turned off or already off", state);
                scheduleNextDay(state);
                return;
            }
            if (!success || lightState.isUnreachableOrOff()) {
                LOG.trace("'{}' not reachable or off, try again", state.getName());
                state.resetConfirmations();
                schedule(state, retryDelay.get());
                return;
            }
            if (!state.isFullyConfirmed()) {
                state.addConfirmation();
                if (state.getConfirmCounter() == 1) {
                    LOG.debug("Set {}", state);
                } else if (state.getConfirmCounter() % 5 == 0) {
                    LOG.debug("Confirmed {} ({})", state, state.getConfirmDebugString());
                }
                schedule(state, confirmDelayInSeconds * 1000L);
            } else {
                LOG.debug("{} fully confirmed", state);
                scheduleNextDay(state);
            }
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS));
    }

    private boolean hasMorePastStates(List<ScheduledState> states, int i) {
        return states.size() > i + 1;
    }

    private List<ScheduledState> getRemaining(List<ScheduledState> states, int i) {
        return states.subList(i + 1, states.size());
    }

    private void addRemainingStatesTheNextDay(List<ScheduledState> remainingStates, ZonedDateTime now) {
        remainingStates.forEach(state -> scheduleNextDay(state, now));
    }

    private void scheduleNextDay(ScheduledState state) {
        scheduleNextDay(state, currentTime.get());
    }

    private void scheduleNextDay(ScheduledState state, ZonedDateTime now) {
        if (state.isTemporary()) return;
        state.resetConfirmations();
        recalculateEnd(state, now);
        schedule(state, state.secondsUntilNextDayFromStart(now) * 1000L);
        state.updateLastStart(now);
    }

    private void recalculateEnd(ScheduledState state, ZonedDateTime now) {
        List<ScheduledState> states = new ArrayList<>(getLightStatesForId(state));
        sortByTimeAscending(states, now);
        int index = states.indexOf(state);
        if (index + 1 < states.size()) {
            ScheduledState next = states.get(index + 1);
            state.setEnd(getRightBeforeStartOfStateNextDay(next, state.getEnd()));
        } else {
            state.setEnd(getRightBeforeStartOfStateNextDay(states.get(0), state.getEnd()));
        }
    }

    private List<ScheduledState> getLightStatesForId(ScheduledState state) {
        return lightStates.get(getLightId(state));
    }

    private int getLightId(ScheduledState state) {
        if (state.isGroupState()) {
            return getGroupId(state.getUpdateId());
        } else {
            return state.getUpdateId();
        }
    }

    private void scheduleSunDataInfoLog() {
        logSunDataInfo();
        ZonedDateTime now = currentTime.get();
        ZonedDateTime midnight = ZonedDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT, now.getZone());
        long delay = Duration.between(now, midnight).toMinutes();
        stateScheduler.scheduleAtFixedRate(this::logSunDataInfo, delay + 1, 60 * 24, TimeUnit.MINUTES);
    }

    private void logSunDataInfo() {
        LOG.info("Current sun times:\n{}", startTimeProvider.toDebugString(currentTime.get()));
    }
}
