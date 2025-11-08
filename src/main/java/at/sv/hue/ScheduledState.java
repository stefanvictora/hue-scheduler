package at.sv.hue;

import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.time.StartTimeProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class ScheduledState { // todo: a better name would be StateDefinition
    /**
     * Max transition time as a multiple of 100ms. Value =  1h40min
     */
    public static final int MAX_TRANSITION_TIME = 60000;
    public static final int MAX_TRANSITION_TIME_MS = MAX_TRANSITION_TIME * 100;

    private final Identifier identifier;
    private final List<ScheduledLightState> lightStates;
    @Getter
    private final String startString;
    @Getter
    private final Integer definedTransitionTime;
    private final String transitionTimeBeforeString;
    private final StartTimeProvider startTimeProvider;
    private final EnumSet<DayOfWeek> daysOfWeek;
    @Getter
    private final boolean groupState;
    private final Boolean force;
    private final Boolean interpolate;
    @Getter
    private final boolean temporary;
    private final int minTrBeforeGapInMinutes;
    private final Cache<ZonedDateTime, ScheduledStateSnapshot> snapshotCache;
    private final int brightnessOverrideThreshold;
    private final int colorTemperatureOverrideThresholdKelvin;
    private final double colorOverrideThreshold;
    @Getter
    private boolean triggeredByPowerTransition;
    @Getter
    private ZonedDateTime lastSeen;
    private ScheduledState originalState;
    @Getter
    private PutCalls lastPutCalls;
    @Setter
    private Function<ScheduledStateSnapshot, ScheduledStateSnapshot> previousStateLookup;
    @Setter
    private BiFunction<ScheduledStateSnapshot, ZonedDateTime, ScheduledStateSnapshot> nextStateLookup;

    @Builder
    public ScheduledState(Identifier identifier, String startString, List<ScheduledLightState> lightStates,
                          String transitionTimeBeforeString, Integer definedTransitionTime, Set<DayOfWeek> daysOfWeek,
                          StartTimeProvider startTimeProvider,
                          int minTrBeforeGapInMinutes, int brightnessOverrideThreshold, int colorTemperatureOverrideThresholdKelvin,
                          double colorOverrideThreshold, Boolean force, Boolean interpolate, boolean groupState, boolean temporary) {
        this.identifier = identifier;
        this.startString = startString;
        this.lightStates = lightStates;
        this.interpolate = interpolate;
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            this.daysOfWeek = EnumSet.allOf(DayOfWeek.class);
        } else {
            this.daysOfWeek = EnumSet.copyOf(daysOfWeek);
        }
        this.groupState = groupState;
        this.definedTransitionTime = assertValidTransitionTime(definedTransitionTime);
        this.transitionTimeBeforeString = transitionTimeBeforeString;
        this.startTimeProvider = startTimeProvider;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.brightnessOverrideThreshold = brightnessOverrideThreshold;
        this.colorTemperatureOverrideThresholdKelvin = colorTemperatureOverrideThresholdKelvin;
        this.colorOverrideThreshold = colorOverrideThreshold;
        this.force = force;
        this.temporary = temporary;
        originalState = this;
        triggeredByPowerTransition = false;
        previousStateLookup = (state) -> null;
        nextStateLookup = (state, dateTime) -> null;
        snapshotCache = Caffeine.newBuilder()
                                .expireAfterWrite(Duration.ofDays(3))
                                .build();
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state) {
        return createTemporaryCopy(state, state.startString);
    }

    private static ScheduledState createTemporaryCopy(ScheduledState state, String start) {
        ScheduledState copy = new ScheduledState(state.identifier, start,
                copyLightStates(state.lightStates), state.transitionTimeBeforeString, state.definedTransitionTime, state.daysOfWeek, state.startTimeProvider,
                state.minTrBeforeGapInMinutes, state.brightnessOverrideThreshold, state.colorTemperatureOverrideThresholdKelvin,
                state.colorOverrideThreshold, state.force, state.interpolate, state.groupState, true);
        copy.lastSeen = state.lastSeen;
        copy.originalState = state.originalState;
        copy.previousStateLookup = state.previousStateLookup;
        copy.nextStateLookup = state.nextStateLookup;
        return copy;
    }

    private static List<ScheduledLightState> copyLightStates(List<ScheduledLightState> lightStates) {
        return lightStates.stream()
                          .map(lightState -> lightState.toBuilder().build())
                          .toList();
    }

    private Integer assertValidTransitionTime(Integer transitionTime) {
        if (transitionTime != null && (transitionTime > MAX_TRANSITION_TIME || transitionTime < 0)) {
            throw new InvalidTransitionTime("Invalid transition time '" + transitionTime + "'. Allowed integer range: 0-" + MAX_TRANSITION_TIME);
        }
        return transitionTime;
    }

    public boolean hasTransitionBefore() {
        return (hasTransitionTimeBeforeString() || interpolate == Boolean.TRUE);
    }

    public boolean hasTransitionTimeBeforeString() {
        return transitionTimeBeforeString != null;
    }

    public int parseTransitionTimeBeforeString(ZonedDateTime definedStart) {
        try {
            return InputConfigurationParser.parseTransitionTime("tr-before", transitionTimeBeforeString) * 100;
        } catch (Exception e) {
            return parseDateTimeBasedTransitionTime(definedStart);
        }
    }

    private int parseDateTimeBasedTransitionTime(ZonedDateTime definedStart) {
        ZonedDateTime transitionTimeBeforeStart = parseStartTime(transitionTimeBeforeString, definedStart);
        Duration duration = Duration.between(transitionTimeBeforeStart, definedStart);
        if (duration.isNegative()) {
            return 0;
        }
        return (int) duration.toMillis();
    }

    /**
     * Returns the snapshot for the given dateTime. The snapshot is cached for 3 days.
     */
    public ScheduledStateSnapshot getSnapshot(ZonedDateTime dateTime) {
        return snapshotCache.get(getDefinedStart(dateTime),
                definedStart -> new ScheduledStateSnapshot(this, definedStart, previousStateLookup, nextStateLookup));
    }

    /**
     * The start without any transition time before adjustments, i.e., the start as defined via the start time
     * expression used in the configuration file.
     */
    public ZonedDateTime getDefinedStart(ZonedDateTime dateTime) {
        DayOfWeek day;
        DayOfWeek today = DayOfWeek.from(dateTime);
        if (daysOfWeek.contains(today)) {
            day = today;
        } else {
            day = getNextDayAfterTodayOrFirstNextWeek(today);
        }
        dateTime = dateTime.with(TemporalAdjusters.nextOrSame(day));
        return parseStartTime(startString, dateTime);
    }

    private ZonedDateTime parseStartTime(String input, ZonedDateTime dateTime) {
        return startTimeProvider.getStart(input, dateTime);
    }

    private DayOfWeek getNextDayAfterTodayOrFirstNextWeek(DayOfWeek today) {
        return daysOfWeek.stream()
                         .filter(day -> afterToday(day, today))
                         .findFirst()
                         .orElse(getFirstDay());
    }

    private boolean afterToday(DayOfWeek day, DayOfWeek today) {
        return day.compareTo(today) > 0;
    }

    private DayOfWeek getFirstDay() {
        assert !daysOfWeek.isEmpty();
        return daysOfWeek.stream().findFirst().get();
    }

    public boolean isScheduledOn(DayOfWeek... day) {
        return daysOfWeek.containsAll(Arrays.asList(day));
    }

    private Integer getTransitionTime(ZonedDateTime now, ZonedDateTime definedStart) {
        int adjustedTrBefore = getAdjustedTransitionTimeBefore(now, definedStart);
        if (adjustedTrBefore == 0) {
            return definedTransitionTime;
        }
        return adjustedTrBefore;
    }

    private int getAdjustedTransitionTimeBefore(ZonedDateTime now, ZonedDateTime definedStart) {
        if (!hasTransitionBefore()) {
            return 0;
        }
        Duration between = Duration.between(now, definedStart);
        if (between.isZero() || between.isNegative()) return 0;
        return (int) between.toMillis() / 100;
    }

    public int getRequiredGap() {
        return minTrBeforeGapInMinutes;
    }

    public boolean isNullState() {
        return lightStates.stream().allMatch(ScheduledLightState::isNullState);
    }

    public boolean hasOtherPropertiesThanOn() {
        return lightStates.stream().anyMatch(ScheduledLightState::hasOtherPropertiesThanOn);
    }

    public boolean isOff() {
        return lightStates.stream().allMatch(ScheduledLightState::isOff);
    }

    public boolean isOn() {
        return lightStates.stream().anyMatch(ScheduledLightState::isOn);
    }

    public boolean lightStateDiffers(LightState currentState) {
        PutCall putCall;
        if (lastPutCalls.isGeneralGroup() || !lastPutCalls.isGroupUpdate()) {
            putCall = lastPutCalls.getFirst();
        } else {
            putCall = lastPutCalls.get(currentState.getId());
        }
        return new LightStateComparator(putCall, currentState, brightnessOverrideThreshold,
                colorTemperatureOverrideThresholdKelvin, colorOverrideThreshold).lightStateDiffers();
    }

    public void setLastSeen(ZonedDateTime lastSeen) {
        this.lastSeen = lastSeen;
        if (originalState != this) {
            originalState.setLastSeen(lastSeen);
        }
    }

    public void setLastPutCalls(PutCalls lastPutCalls) {
        this.lastPutCalls = lastPutCalls;
        if (originalState != this) {
            originalState.setLastPutCalls(lastPutCalls);
        }
    }

    public boolean isSameState(ScheduledState state) {
        return this == state || originalState == state;
    }

    public boolean isForced() {
        return force == Boolean.TRUE;
    }

    public PutCalls getPutCalls(ZonedDateTime now, ZonedDateTime definedStart) {
        Integer transitionTime = now != null && definedStart != null ? getTransitionTime(now, definedStart) : null; // todo: mutation coverage
        List<PutCall> putCallList = lightStates.stream()
                                               .map(this::getPutCall)
                                               .toList();
        return new PutCalls(identifier.id(), putCallList, transitionTime, groupState);
    }

    private PutCall getPutCall(ScheduledLightState lightState) {
        return PutCall.builder()
                      .id(lightState.getId())
                      .bri(lightState.getBri())
                      .ct(lightState.getCt())
                      .x(lightState.getX())
                      .y(lightState.getY())
                      .on(lightState.getOn())
                      .effect(lightState.getEffect())
                      .gradient(lightState.getGradient())
                      .gamut(lightState.getGamut())
                      .build();
    }

    public String getId() {
        return identifier.id();
    }

    public void setTriggeredByPowerTransition() {
        this.triggeredByPowerTransition = true;
    }

    @Override
    public String toString() {
        return getFormattedName() + " {" + "start=" + startString + ", " + getFormattedProperties() + '}';
    }

    public String getFormattedProperties() {
        return "id=" + identifier.id() +
               (temporary && !triggeredByPowerTransition ? ", temporary" : "") +
               (triggeredByPowerTransition ? ", power-transition-state" : "") +
               formatPropertyName("states") + lightStates.toString() +
               getFormattedDaysOfWeek() +
               getFormattedPropertyIfSet("tr-before", transitionTimeBeforeString) +
               getFormattedTransitionTimeIfSet("tr", definedTransitionTime) +
               getFormattedPropertyIfSet("force", force) +
               getFormattedPropertyIfSet("interpolate", interpolate);
    }

    public String getFormattedName() {
        if (groupState) {
            return "Group '" + identifier.name() + "'";
        }
        return "Light '" + identifier.name() + "'";
    }

    public String getContextName() {
        String context = identifier.name();
        if (isTriggeredByPowerTransition()) {
            context += " (power-transition)";
        } else if (temporary) {
            context += " (temporary)";
        }
        return context;
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }

    private String getFormattedDaysOfWeek() {
        if (daysOfWeek.size() == 7) return "";
        return formatPropertyName("days") + Arrays.toString(daysOfWeek.toArray());
    }

    private String getFormattedTransitionTimeIfSet(String name, Integer transitionTime) {
        if (transitionTime == null) return "";
        return formatPropertyName(name) + formatTransitionTime(transitionTime);
    }

    private static String formatTransitionTime(Integer transitionTime) {
        return Duration.ofMillis(transitionTime * 100L).toString();
    }
}
