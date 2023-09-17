package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.time.StartTimeProvider;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiFunction;

final class ScheduledState {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String MULTI_COLOR_LOOP = "multi_colorloop";
    private static final String COLOR_LOOP = "colorloop";
    public static final int MAX_HUE_VALUE = 65535;
    public static final int MIDDLE_HUE_VALUE = 32768;
    /**
     * Max transition time as a multiple of 100ms. Value =  1h40min
     */
    public static final int MAX_TRANSITION_TIME = 60000;
    public static final int MAX_TRANSITION_TIME_MS = MAX_TRANSITION_TIME * 100;

    private final String name;
    @Getter
    private final int updateId;
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
    @Getter
    @Setter
    private ZonedDateTime end;
    @Getter
    private ZonedDateTime lastStart;
    @Getter
    private ZonedDateTime lastDefinedStart;
    @Getter
    @Setter
    private boolean retryAfterPowerOnState;
    @Getter
    private ZonedDateTime lastSeen;
    private ScheduledState originalState;
    private PutCall lastPutCall;
    @Setter
    private BiFunction<ScheduledState, ZonedDateTime, ScheduledStateSnapshot> previousStateLookup;

    @Builder
    public ScheduledState(String name, int updateId, String startString, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, String effect, Boolean on, String transitionTimeBeforeString, Integer definedTransitionTime,
                          Set<DayOfWeek> daysOfWeek, StartTimeProvider startTimeProvider, LightCapabilities capabilities,
                          int minTrBeforeGapInMinutes, Boolean force, Boolean interpolate, boolean groupState, boolean temporary) {
        this.name = name;
        this.updateId = updateId;
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
        this.on = on;
        this.definedTransitionTime = assertValidTransitionTime(definedTransitionTime);
        this.transitionTimeBeforeString = transitionTimeBeforeString;
        this.startTimeProvider = startTimeProvider;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.force = force;
        this.temporary = temporary;
        originalState = this;
        retryAfterPowerOnState = false;
        previousStateLookup = (state, dateTime) -> null;
        assertColorCapabilities();
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state) {
        return createTemporaryCopy(state, state.getEnd());
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state, ZonedDateTime end) {
        return createTemporaryCopy(state, state.startString, end);
    }

    private static ScheduledState createTemporaryCopy(ScheduledState state, String start, ZonedDateTime end) {
        ScheduledState copy = new ScheduledState(state.name, state.updateId, start,
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.effect, state.on,
                state.transitionTimeBeforeString, state.definedTransitionTime, state.daysOfWeek, state.startTimeProvider,
                state.capabilities, state.minTrBeforeGapInMinutes, state.force, state.interpolate, state.groupState, true);
        copy.end = end;
        copy.lastStart = state.lastStart;
        copy.lastDefinedStart = state.lastDefinedStart;
        copy.lastSeen = state.lastSeen;
        copy.originalState = state.originalState;
        return copy;
    }

    private String assertValidEffectValue(String effect) {
        if (effect != null && !"none".equals(effect) && !COLOR_LOOP.equals(effect) && !MULTI_COLOR_LOOP.equals(effect)) {
            throw new InvalidPropertyValue("Unsupported value for effect property: '" + effect + "'");
        }
        if (!isGroupState() && MULTI_COLOR_LOOP.equals(effect)) {
            throw new InvalidPropertyValue("Multi color loop is only supported for groups.");
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
        return x != null || y != null || hue != null || sat != null || effect != null;
    }

    /**
     * Returns the milliseconds until the state should be scheduled based on the previously calculated start, i.e.,
     * the lastStart property.
     *
     * @param now the current time
     * @return the delay in ms until this state should be scheduled, not negative.
     * Returning 0 if it should be directly scheduled.
     */
    public long getDelayUntilStart(ZonedDateTime now) {
        return getDelayUntilStart(now, lastStart);
    }

    private long getDelayUntilStart(ZonedDateTime now, ZonedDateTime start) {
        Duration between = Duration.between(now, start);
        if (between.isNegative()) {
            return 0;
        } else {
            return between.toMillis();
        }
    }

    public ZonedDateTime getStart(ZonedDateTime dateTime) {
        ZonedDateTime definedStart = getDefinedStart(dateTime);
        if (hasTransitionBefore()) {
            int transitionTimeBefore = getTransitionTimeBefore(dateTime, definedStart);
            return definedStart.minus(transitionTimeBefore, ChronoUnit.MILLIS);
        }
        return definedStart;
    }

    public boolean hasTransitionBefore() {
        return (transitionTimeBeforeString != null || interpolate == Boolean.TRUE);
    }

    private int getTransitionTimeBefore(ZonedDateTime dateTime, ZonedDateTime definedStart) {
        ScheduledStateSnapshot previousState = previousStateLookup.apply(this, dateTime);
        if (previousState == null || previousState.isNullState() || hasNoOverlappingProperties(previousState)) {
            return 0;
        }
        if (transitionTimeBeforeString != null) {
            return parseTransitionTimeBefore(dateTime);
        } else {
            return getTimeUntilPreviousState(previousState.getDefinedStart(), definedStart);
        }
    }

    private boolean hasNoOverlappingProperties(ScheduledStateSnapshot previousState) {
        PutCall previousPutCall = previousState.getScheduledState().getPutCall(null);
        return StateInterpolator.hasNoOverlappingProperties(previousPutCall, getPutCall(null));
    }

    private int getTimeUntilPreviousState(ZonedDateTime previousStateDefinedStart, ZonedDateTime definedStart) {
        return (int) Duration.between(previousStateDefinedStart, definedStart).toMillis();
    }

    private int parseTransitionTimeBefore(ZonedDateTime dateTime) {
        try {
            return InputConfigurationParser.parseTransitionTime("tr-before", transitionTimeBeforeString) * 100;
        } catch (Exception e) {
            return parseDateTimeBasedTransitionTime(dateTime);
        }
    }

    private int parseDateTimeBasedTransitionTime(ZonedDateTime dateTime) {
        ZonedDateTime transitionTimeBeforeStart = startTimeProvider.getStart(transitionTimeBeforeString, dateTime);
        ZonedDateTime definedStart = getDefinedStart(dateTime);
        Duration duration = Duration.between(transitionTimeBeforeStart, definedStart);
        if (duration.isNegative()) {
            return 0;
        }
        return (int) duration.toMillis();
    }

    public ScheduledStateSnapshot getSnapshot(ZonedDateTime dateTime) {
        ZonedDateTime definedStart = getDefinedStart(dateTime);
        return new ScheduledStateSnapshot(this, definedStart);
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
        return startTimeProvider.getStart(startString, dateTime);
    }

    /**
     * Returns the next defined start after the last one, starting from the given dateTime.
     * We loop over getDefinedStart with increased days until we find the first start that is after the last one.
     */
    public ZonedDateTime getNextDefinedStart(ZonedDateTime dateTime) {
        ZonedDateTime last = lastDefinedStart;
        ZonedDateTime next = getDefinedStart(dateTime);
        while (next.isBefore(last) || next.equals(last)) {
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

    public String getIdV1() {
        if (groupState) {
            return "/groups/" + updateId;
        }
        return "/lights/" + updateId;
    }

    private String getEffect() {
        if (isMultiColorLoop()) {
            return COLOR_LOOP;
        }
        return effect;
    }

    public boolean isMultiColorLoop() {
        return MULTI_COLOR_LOOP.equals(effect);
    }

    private Integer getTransitionTime(ZonedDateTime now) {
        int adjustedTrBefore = getAdjustedTransitionTimeBefore(now);
        if (adjustedTrBefore == 0) {
            return definedTransitionTime;
        }
        return adjustedTrBefore;
    }

    private int getAdjustedTransitionTimeBefore(ZonedDateTime now) {
        if (!hasTransitionBefore()) {
            return 0;
        }
        Duration between = Duration.between(now, lastDefinedStart);
        if (between.isZero() || between.isNegative()) return 0;
        return (int) between.toMillis() / 100;
    }

    public PutCall getInterpolatedPutCall(ScheduledState previousState, ZonedDateTime dateTime,
                                          boolean keepPreviousPropertiesForNullTargets) {
        return new StateInterpolator(this, previousState, dateTime, keepPreviousPropertiesForNullTargets)
                .getInterpolatedPutCall();
    }

    public boolean isSplitState() {
        return Duration.between(lastStart, lastDefinedStart).compareTo(Duration.ofMillis(MAX_TRANSITION_TIME_MS)) > 0;
    }

    public boolean isInsideSplitCallWindow(ZonedDateTime now) {
        return getNextTransitionTimeSplitStart(now).isBefore(lastDefinedStart);
    }

    public ZonedDateTime calculateNextPowerOnEnd(ZonedDateTime now) {
        if (isInsideSplitCallWindow(now)) {
            return getNextTransitionTimeSplitStart(now).minusSeconds(1);
        } else {
            return getEnd();
        }
    }

    public PutCall getNextInterpolatedSplitPutCall(ZonedDateTime now, ScheduledState previousState) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now).minusMinutes(getRequiredGap()); // add buffer
        PutCall interpolatedSplitPutCall = getInterpolatedPutCall(previousState, nextSplitStart, false);
        if (interpolatedSplitPutCall == null) {
            return null; // no interpolation possible
        }
        Duration between = Duration.between(now, nextSplitStart);
        if (between.isZero() || between.isNegative()) {
            return null; // we are inside the required gap, skip split call
        }
        interpolatedSplitPutCall.setTransitionTime((int) between.toMillis() / 100);
        return interpolatedSplitPutCall;
    }

    public int getRequiredGap() {
        return minTrBeforeGapInMinutes;
    }

    public long getNextInterpolationSplitDelayInMs(ZonedDateTime now) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now);
        return Duration.between(now, nextSplitStart).toMillis();
    }

    private ZonedDateTime getNextTransitionTimeSplitStart(ZonedDateTime now) {
        ZonedDateTime splitStart = lastStart.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS);
        while (splitStart.isBefore(now) || splitStart.isEqual(now)) {
            splitStart = splitStart.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS);
        }
        return splitStart;
    }

    public boolean endsBefore(ZonedDateTime now) {
        return now.isAfter(end);
    }

    public boolean isNullState() {
        return brightness == null && ct == null && on == null && x == null && y == null && hue == null && sat == null && effect == null;
    }

    public boolean isOff() {
        return on == Boolean.FALSE;
    }

    public boolean isOn() {
        return on == Boolean.TRUE;
    }

    public void updateLastStart(ZonedDateTime dateTime) {
        lastStart = getStart(dateTime);
        lastDefinedStart = getDefinedStart(dateTime);
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

    public boolean isNotSameState(ScheduledState state) {
        return !isSameState(state);
    }

    public boolean isSameState(ScheduledState state) {
        return this == state || originalState == state;
    }

    public boolean isForced() {
        return force == Boolean.TRUE;
    }

    public PutCall getPutCall(ZonedDateTime now) {
        return PutCall.builder().id(updateId)
                      .bri(brightness)
                      .ct(ct)
                      .x(x)
                      .y(y)
                      .hue(hue)
                      .sat(sat)
                      .on(on)
                      .effect(getEffect())
                      .transitionTime(now != null ? getTransitionTime(now) : null)
                      .groupState(groupState)
                      .build();
    }

    @Override
    public String toString() {
        return getFormattedName() + " {" +
                "id=" + updateId +
                (temporary && !retryAfterPowerOnState ? ", temporary" : "") +
                (retryAfterPowerOnState ? ", power-on-event" : "") +
                ", start=" + getFormattedStart() +
                ", end=" + getFormattedEnd() +
                getFormattedPropertyIfSet("on", on) +
                getFormattedPropertyIfSet("bri", brightness) +
                getFormattedPropertyIfSet("ct", ct) +
                getFormattedPropertyIfSet("x", x) +
                getFormattedPropertyIfSet("y", y) +
                getFormattedPropertyIfSet("hue", hue) +
                getFormattedPropertyIfSet("sat", sat) +
                getFormattedPropertyIfSet("effect", effect) +
                getFormattedDaysOfWeek() +
                getFormattedTransitionTimeBefore() +
                getFormattedTransitionTimeIfSet("tr", definedTransitionTime) +
                getFormattedPropertyIfSet("lastSeen", getFormattedTime(lastSeen)) +
                getFormattedPropertyIfSet("force", force) +
                getFormattedPropertyIfSet("interpolate", interpolate) +
                '}';
    }

    public String getFormattedName() {
        if (groupState) {
            return "Group '" + name + "'";
        }
        return "Light '" + name + "'";
    }

    public String getContextName() {
        String context = name;
        if (isRetryAfterPowerOnState()) {
            context += " (power-on)";
        } else if (temporary) {
            context += " (temporary)";
        }
        return context;
    }

    private String getFormattedStart() {
        if (lastStart != null) {
            return startString + " (" + getFormattedTime(lastStart) + ")";
        }
        return startString;
    }

    private String getFormattedTime(ZonedDateTime zonedDateTime) {
        if (zonedDateTime == null) {
            return null;
        }
        return TIME_FORMATTER.format(zonedDateTime);
    }

    private String getFormattedEnd() {
        if (end == null) return "<ERROR: not set>";
        return end.toLocalDateTime().toString();
    }

    private String getFormattedTransitionTimeBefore() {
        if (transitionTimeBeforeString == null) {
            return "";
        }
        if (lastStart != null) {
            return formatPropertyName("tr-before") + transitionTimeBeforeString +
                    " (" + formatTransitionTimeBefore(parseTransitionTimeBefore(lastStart)) + ")";
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

    private static String formatTransitionTimeBefore(Integer transitionTime) {
        return Duration.ofMillis(transitionTime).toString();
    }
}
