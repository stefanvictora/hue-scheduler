package at.sv.hue;

import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
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
import java.util.function.Function;

final class ScheduledState { // todo: a better name would be StateDefinition
    public static final int MAX_HUE_VALUE = 65535;
    public static final int MIDDLE_HUE_VALUE = 32768;
    /**
     * Max transition time as a multiple of 100ms. Value =  1h40min
     */
    public static final int MAX_TRANSITION_TIME = 60000;
    public static final int MAX_TRANSITION_TIME_MS = MAX_TRANSITION_TIME * 100;

    private final Identifier identifier;
    @Getter
    private final String startString;
    private final Integer brightness;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Integer hue;
    private final Integer sat;
    private final Boolean on;
    @Getter
    private final Integer definedTransitionTime;
    private final String transitionTimeBeforeString;
    private final StartTimeProvider startTimeProvider;
    private final String effect;
    private final EnumSet<DayOfWeek> daysOfWeek;
    @Getter
    private final boolean groupState;
    private final Boolean force;
    private final Boolean interpolate;
    @Getter
    private final boolean temporary;
    @Getter
    private final LightCapabilities capabilities;
    private final int minTrBeforeGapInMinutes;
    private final Cache<ZonedDateTime, ScheduledStateSnapshot> snapshotCache;
    @Getter
    @Setter
    private boolean retryAfterPowerOnState;
    @Getter
    private ZonedDateTime lastSeen;
    private ScheduledState originalState;
    private PutCall lastPutCall;
    @Setter
    private Function<ScheduledStateSnapshot, ScheduledStateSnapshot> previousStateLookup;

    @Builder
    public ScheduledState(Identifier identifier, String startString, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, String effect, Boolean on, String transitionTimeBeforeString, Integer definedTransitionTime,
                          Set<DayOfWeek> daysOfWeek, StartTimeProvider startTimeProvider, LightCapabilities capabilities,
                          int minTrBeforeGapInMinutes, Boolean force, Boolean interpolate, boolean groupState, boolean temporary) {
        this.identifier = identifier;
        this.startString = startString;
        this.interpolate = interpolate;
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            this.daysOfWeek = EnumSet.allOf(DayOfWeek.class);
        } else {
            this.daysOfWeek = EnumSet.copyOf(daysOfWeek);
        }
        this.groupState = groupState;
        this.capabilities = capabilities;
        this.effect = assertValidEffectValue(effect);
        this.brightness = assertValidBrightnessValue(brightness);
        this.ct = assertCtSupportAndValue(ct);
        this.x = assertValidXAndY(x);
        this.y = assertValidXAndY(y);
        this.hue = assertValidHueValue(hue);
        this.sat = assertValidSaturationValue(sat);
        assertValidHueAndSat();
        this.on = on;
        this.definedTransitionTime = assertValidTransitionTime(definedTransitionTime);
        this.transitionTimeBeforeString = transitionTimeBeforeString;
        this.startTimeProvider = startTimeProvider;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.force = force;
        this.temporary = temporary;
        originalState = this;
        retryAfterPowerOnState = false;
        previousStateLookup = (state) -> null;
        assertColorCapabilities();
        snapshotCache = Caffeine.newBuilder()
                                .expireAfterWrite(Duration.ofDays(3))
                                .build();
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state) {
        return createTemporaryCopy(state, state.startString);
    }

    private static ScheduledState createTemporaryCopy(ScheduledState state, String start) {
        ScheduledState copy = new ScheduledState(state.identifier, start,
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.effect, state.on,
                state.transitionTimeBeforeString, state.definedTransitionTime, state.daysOfWeek, state.startTimeProvider,
                state.capabilities, state.minTrBeforeGapInMinutes, state.force, state.interpolate, state.groupState, true);
        copy.lastSeen = state.lastSeen;
        copy.originalState = state.originalState;
        copy.previousStateLookup = state.previousStateLookup;
        return copy;
    }

    private String assertValidEffectValue(String effect) {
        if (effect == null) {
            return null;
        }
        if (groupState) {
            throw new InvalidPropertyValue("Effects are not supported by groups.");
        }
        List<String> supportedEffects = capabilities.getEffects();
        if (supportedEffects == null) {
            throw new InvalidPropertyValue("Light does not support any effects.");
        }
        if (effect.equals("none")) {
            return effect;
        }
        if (!supportedEffects.contains(effect)) {
            throw new InvalidPropertyValue("Unsupported value for effect property: '" + effect + "'." +
                                           " Supported effects: " + supportedEffects);
        }
        return effect;
    }

    private Integer assertValidBrightnessValue(Integer brightness) {
        if (brightness == null) {
            return null;
        }
        assertBrightnessSupported();
        if (brightness > 254 || brightness < 1) {
            throw new InvalidBrightnessValue("Invalid brightness value '" + brightness + "'. Allowed integer range: 1-254");
        }
        return brightness;
    }

    private void assertBrightnessSupported() {
        if (!capabilities.isBrightnessSupported()) {
            throw new BrightnessNotSupported(getFormattedName() + "' does not support setting brightness! "
                                             + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private Integer assertCtSupportAndValue(Integer ct) {
        if (ct == null) {
            return null;
        }
        assertCtSupported();
        if (capabilities.getCtMax() == null || capabilities.getCtMin() == null) {
            return ct;
        }
        if (ct > capabilities.getCtMax() || ct < capabilities.getCtMin()) {
            throw new InvalidColorTemperatureValue("Invalid ct value '" + ct + "'. Support integer range for " + getFormattedName() + ": "
                                                   + capabilities.getCtMin() + "-" + capabilities.getCtMax());
        }
        return ct;
    }

    private void assertCtSupported() {
        if (!capabilities.isCtSupported()) {
            throw new ColorTemperatureNotSupported(getFormattedName() + "' does not support setting color temperature! "
                                                   + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private Double assertValidXAndY(Double xOrY) {
        if (xOrY != null && (xOrY > 1 || xOrY < 0)) {
            throw new InvalidXAndYValue("Invalid x or y value '" + xOrY + "'. Allowed double range: 0-1");
        }
        return xOrY;
    }

    private Integer assertValidHueValue(Integer hue) {
        if (hue != null && (hue > MAX_HUE_VALUE || hue < 0)) {
            throw new InvalidHueValue("Invalid hue value '" + hue + "'. Allowed integer range: 0-65535");
        }
        return hue;
    }

    private Integer assertValidSaturationValue(Integer sat) {
        if (sat != null && (sat > 254 || sat < 0)) {
            throw new InvalidSaturationValue("Invalid saturation value '" + sat + "'. Allowed integer range: 0-254");
        }
        return sat;
    }

    private void assertValidHueAndSat() {
        if (hue != null && sat == null || hue == null && sat != null) {
            throw new InvalidPropertyValue("Hue and sat can only occur at the same time.");
        }
    }

    private Integer assertValidTransitionTime(Integer transitionTime) {
        if (transitionTime != null && (transitionTime > MAX_TRANSITION_TIME || transitionTime < 0)) {
            throw new InvalidTransitionTime("Invalid transition time '" + transitionTime + ". Allowed integer range: 0-" + MAX_TRANSITION_TIME);
        }
        return transitionTime;
    }

    private void assertColorCapabilities() {
        if (isColorState() && !capabilities.isColorSupported()) {
            throw new ColorNotSupported(getFormattedName() + "' does not support setting color! "
                                        + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private boolean isColorState() {
        return x != null || y != null || hue != null || sat != null;
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
                definedStart -> new ScheduledStateSnapshot(this, definedStart, previousStateLookup));
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

    /**
     * Returns the next defined start after the last one, starting from the given dateTime.
     * We loop over getDefinedStart with increased days until we find the first start that is after the last one.
     */
    public ZonedDateTime getNextDefinedStart(ZonedDateTime dateTime, ZonedDateTime currentDefinedStart) {
        ZonedDateTime next = getDefinedStart(dateTime);
        while (next.isBefore(currentDefinedStart) || next.equals(currentDefinedStart)) {
            dateTime = dateTime.plusDays(1);
            next = getDefinedStart(dateTime);
        }
        return next;
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

    public boolean isScheduledOn(ZonedDateTime day) {
        return isScheduledOn(DayOfWeek.from(day));
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
        return on == null && hasNoOtherPropertiesThanOn();
    }

    private boolean hasNoOtherPropertiesThanOn() {
        return brightness == null && ct == null && x == null && y == null && hue == null && sat == null && effect == null;
    }

    public boolean hasOtherPropertiesThanOn() {
        return !hasNoOtherPropertiesThanOn();
    }

    public boolean isOff() {
        return on == Boolean.FALSE;
    }

    public boolean isOn() {
        return on == Boolean.TRUE;
    }

    public boolean lightStateDiffers(LightState currentState) {
        return new LightStateComparator(lastPutCall, currentState).lightStateDiffers();
    }

    public void setLastSeen(ZonedDateTime lastSeen) {
        this.lastSeen = lastSeen;
        if (originalState != this) {
            originalState.setLastSeen(lastSeen);
        }
    }

    public void setLastPutCall(PutCall lastPutCall) {
        this.lastPutCall = lastPutCall;
        if (originalState != this) {
            originalState.setLastPutCall(lastPutCall);
        }
    }

    public boolean isSameState(ScheduledState state) {
        return this == state || originalState == state;
    }

    public boolean isForced() {
        return force == Boolean.TRUE;
    }

    public PutCall getPutCall(ZonedDateTime now, ZonedDateTime definedStart) {
        return PutCall.builder().id(identifier.id())
                      .bri(brightness)
                      .ct(ct)
                      .x(x)
                      .y(y)
                      .hue(hue)
                      .sat(sat)
                      .on(on)
                      .effect(effect)
                      .transitionTime(now != null && definedStart != null ? getTransitionTime(now, definedStart) : null)
                      .groupState(groupState)
                      .gamut(capabilities != null ? capabilities.getColorGamut() : null)
                      .build();
    }

    public String getId() {
        return identifier.id();
    }

    @Override
    public String toString() {
        return getFormattedName() + " {" + "start=" + startString + ", " + getFormattedProperties() + '}';
    }

    public String getFormattedProperties() {
        return "id=" + identifier.id() +
               (temporary && !retryAfterPowerOnState ? ", temporary" : "") +
               (retryAfterPowerOnState ? ", power-on-event" : "") +
               getFormattedPropertyIfSet("on", on) +
               getFormattedPropertyIfSet("bri", brightness) +
               getFormattedPropertyIfSet("ct", ct) +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("hue", hue) +
               getFormattedPropertyIfSet("sat", sat) +
               getFormattedPropertyIfSet("effect", effect) +
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
        if (isRetryAfterPowerOnState()) {
            context += " (power-on)";
        } else if (temporary) {
            context += " (temporary)";
        }
        return context;
    }

    private String getFormattedTransitionTimeBefore() {
        if (transitionTimeBeforeString == null) {
            return "";
        }
        return formatPropertyName("tr-before") + transitionTimeBeforeString;
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
