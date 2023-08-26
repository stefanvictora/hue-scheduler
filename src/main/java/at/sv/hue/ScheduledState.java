package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.time.StartTimeProvider;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

final class ScheduledState {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String MULTI_COLOR_LOOP = "multi_colorloop";
    private static final String COLOR_LOOP = "colorloop";
    public static final int MAX_HUE_VALUE = 65535;
    public static final int MIDDLE_HUE_VALUE = 32768;
    /**
     * Max transition time as a multiple of 100ms
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
    @Getter
    private final String transitionTimeBeforeString;
    private final StartTimeProvider startTimeProvider;
    private final String effect;
    private final EnumSet<DayOfWeek> daysOfWeek;
    @Getter
    private final boolean groupState;
    private final Boolean force;
    @Getter
    private final boolean temporary;
    private final List<Integer> groupLights;
    @Getter
    private final LightCapabilities capabilities;
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

    @Builder
    public ScheduledState(String name, int updateId, String startString, Integer brightness, Integer ct, Double x, Double y,
            Integer hue, Integer sat, String effect, Boolean on, String transitionTimeBeforeString, Integer definedTransitionTime,
                          Set<DayOfWeek> daysOfWeek, StartTimeProvider startTimeProvider, boolean groupState,
                          List<Integer> groupLights, LightCapabilities capabilities, Boolean force, boolean temporary) {
        this.name = name;
        this.updateId = updateId;
        this.startString = startString;
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            this.daysOfWeek = EnumSet.allOf(DayOfWeek.class);
        } else {
            this.daysOfWeek = EnumSet.copyOf(daysOfWeek);
        }
        this.groupState = groupState;
        this.groupLights = groupLights;
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
        this.force = force;
        this.temporary = temporary;
        originalState = this;
        retryAfterPowerOnState = false;
        assertColorCapabilities();
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state) {
        return createTemporaryCopy(state, state.getEnd());
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state, ZonedDateTime end) {
        return createTemporaryCopy(state, state.startString, end, state.transitionTimeBeforeString);
    }

    private static ScheduledState createTemporaryCopy(ScheduledState state, String start, ZonedDateTime end,
                                                      String transitionTimeBefore) {
        ScheduledState copy = new ScheduledState(state.name, state.updateId, start,
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.effect, state.on, transitionTimeBefore,
                state.definedTransitionTime, state.daysOfWeek, state.startTimeProvider, state.groupState, state.groupLights,
                state.capabilities, state.force, true);
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
     * Returns if the state should have already started at the start calculated for the given day.
     *
     * @param now the current day used for comparison
     * @param day the date used for calculating the next start for comparison, in most cases the current or next day
     * @return true if the state should have already started at given day, false otherwise
     */
    public boolean stateAlreadyStarted(ZonedDateTime now, ZonedDateTime day) {
        return getDelayUntilStart(now, getStart(day)) == 0;
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
        if (transitionTimeBeforeString != null) {
            return definedStart.minus(getTransitionTimeBefore(dateTime) * 100L, ChronoUnit.MILLIS);
        }
        return definedStart;
    }
    
    /**
     * Returns the calculated transition time before as multiple of 100ms, to be directly used by the Hue API.
     * To get the actual ms, multiply the returned value by 100.
     *
     * @param dateTime the date time used as input for calculating sun-based transition before times.
     * @return the calculated transition time before as multiple of 100ms
     */
    public Integer getTransitionTimeBefore(ZonedDateTime dateTime) {
        try {
            return InputConfigurationParser.parseTransitionTime("tr-before", transitionTimeBeforeString);
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
        return (int) (duration.toMillis() / 100L);
    }

    /**
     * The start without any transition time before adjustments, i.e., the start as defined via the start time
     * expression used in the configuration file.
     *
     * @param dateTime the date the defined start for this state should be calculated for
     * @return the defined start for the given date
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
     *
     * @param dateTime the start date the next defined start should be calculated for
     * @return the next defined start
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

    public Set<DayOfWeek> getDaysOfWeek() {
        return EnumSet.copyOf(daysOfWeek);
    }

    public boolean isScheduledOn(ZonedDateTime day) {
        return isScheduledOn(DayOfWeek.from(day));
    }

    public boolean isScheduledOn(DayOfWeek... day) {
        return getDaysOfWeek().containsAll(Arrays.asList(day));
    }
    
    public String getIdV1() {
        if (groupState) {
            return "/groups/" + updateId;
        }
        return "/lights/" + updateId;
    }

    public String getEffect() {
        if (isMultiColorLoop()) {
            return COLOR_LOOP;
        }
        return effect;
    }

    public boolean isMultiColorLoop() {
        return MULTI_COLOR_LOOP.equals(effect);
    }

    public Integer getTransitionTime(ZonedDateTime now) {
        int adjustedTrBefore = getAdjustedTransitionTimeBefore(now);
        if (adjustedTrBefore == 0) {
            return definedTransitionTime;
        }
        return adjustedTrBefore;
    }

    public int getAdjustedTransitionTimeBefore(ZonedDateTime now) {
        if (transitionTimeBeforeString == null) {
            return 0;
        }
        Duration between = Duration.between(now, lastDefinedStart);
        if (between.isZero() || between.isNegative()) return 0;
        return (int) between.toMillis() / 100;
    }

    public PutCall getInterpolatedPutCall(ZonedDateTime dateTime, ScheduledState previousState) {
        return new StateInterpolator(this, previousState, dateTime).getInterpolatedPutCall();
    }
    
    public boolean shouldSplitLongBeforeTransition(ZonedDateTime now) {
        return getAdjustedTransitionTimeBefore(now) > ScheduledState.MAX_TRANSITION_TIME;
    }
    
    public ZonedDateTime calculateNextPowerOnEnd(ZonedDateTime now) {
        if (shouldSplitLongBeforeTransition(now)) {
            return getNextTransitionTimeSplitStart(now).minusSeconds(1);
        } else {
            return getEnd();
        }
    }
    
    public PutCall getNextInterpolatedSplitPutCall(ZonedDateTime now, ScheduledState previousState) {
        ZonedDateTime nextSplitStart = getNextTransitionTimeSplitStart(now);
        PutCall interpolatedSplitPutCall = getInterpolatedPutCall(nextSplitStart, previousState);
        // todo: interpolated call can be null?
        long splitTransitionTime = Duration.between(now, nextSplitStart).toMillis();
        interpolatedSplitPutCall.setTransitionTime((int) splitTransitionTime / 100);
        return interpolatedSplitPutCall;
    }
    
    private ZonedDateTime getNextTransitionTimeSplitStart(ZonedDateTime now) {
        ZonedDateTime splitStart = lastStart.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS);
        while (splitStart.isBefore(now) || splitStart.isEqual(now)) {
            splitStart = splitStart.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS);
        }
        return splitStart;
    }

    /**
     * t = (current_time - start_time) / (end_time - start_time)
     */
    public BigDecimal getInterpolatedTime(ZonedDateTime now) {
        Duration durationAfterStart = Duration.between(lastStart, now);
        ZonedDateTime endTime = lastDefinedStart;
        Duration totalDuration = Duration.between(lastStart, endTime);
        return BigDecimal.valueOf(durationAfterStart.toMillis())
                .divide(BigDecimal.valueOf(totalDuration.toMillis()), 7, RoundingMode.HALF_UP);
    }

    public boolean endsBefore(ZonedDateTime now) {
        return now.isAfter(end);
    }

    public List<Integer> getGroupLights() {
        return new ArrayList<>(groupLights); // todo: maybe fetch this dynamically
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

    public void updateLastStart(ZonedDateTime now) {
        this.lastStart = getStart(now);
        this.lastDefinedStart = getDefinedStart(now);
    }
    
    public boolean lightStateDiffers(LightState currentState) {
        return brightnessDiffers(currentState) ||
                colorModeOrValuesDiffer(currentState) ||
                effectDiffers(currentState) ||
                onStateDiffers(currentState);
    }

    private boolean brightnessDiffers(LightState currentState) {
        return lastPutCall.getBri() != null && currentState.isBrightnessSupported() && !lastPutCall.getBri().equals(currentState.getBrightness());
    }

    private boolean colorModeOrValuesDiffer(LightState currentState) {
        ColorMode colorMode = lastPutCall.getColorMode();
        if (colorMode == ColorMode.NONE) {
            return false; // if no color mode scheduled, always treat as equal
        }
        if (colorModeNotSupportedByState(colorMode, currentState)) {
			return false;
		}
        if (colorModeDiffers(colorMode, currentState)) {
            return true;
        }
        switch (colorMode) {
            case CT:
                return lastPutCall.getCt() != null && !lastPutCall.getCt().equals(currentState.getColorTemperature());
            case HS:
                return lastPutCall.getHue() != null && !lastPutCall.getHue().equals(currentState.getHue()) ||
                        lastPutCall.getSat() != null && !lastPutCall.getSat().equals(currentState.getSat());
            case XY:
                return doubleValueDiffers(lastPutCall.getX(), currentState.getX()) ||
                        doubleValueDiffers(lastPutCall.getY(), currentState.getY());
            default:
                return false; // should not happen, but as a fallback we just ignore unknown color modes
        }
    }

    private static boolean colorModeNotSupportedByState(ColorMode colorMode, LightState currentState) {
        return colorMode == ColorMode.CT && !currentState.isCtSupported()
                || colorMode == ColorMode.HS && !currentState.isColorSupported()
                || colorMode == ColorMode.XY && !currentState.isColorSupported();
    }

    private static boolean colorModeDiffers(ColorMode colorMode, LightState currentState) {
        return !Objects.equals(colorMode, currentState.getColormode());
    }
    
    private boolean effectDiffers(LightState currentState) {
        String lastEffect = lastPutCall.getEffect();
        if (lastEffect == null) {
            return false; // if no effect scheduled, always treat as equal
        } else if (currentState.getEffect() == null) {
            return !"none".equals(lastEffect); // if effect scheduled, but none set, only consider "none" to be equal
        } else {
            return !lastEffect.equals(currentState.getEffect()); // otherwise, effects have to be exactly the same
        }
    }

    /**
     * We only detect any changes if the state itself has "on:true". This is specifically meant for detecting turning
     * off lights inside a group, when the "on:true" state is enforced via the state.
     */
    private boolean onStateDiffers(LightState currentState) {
        return lastPutCall.getOn() != null && currentState.isOnOffSupported() && lastPutCall.getOn() &&
                !lastPutCall.getOn().equals(currentState.isOn());
    }

    private boolean doubleValueDiffers(Double scheduled, Double current) {
        if (scheduled == null && current == null) {
            return false;
        }
        if (scheduled == null || current == null) {
            return true;
        }
        return getBigDecimal(scheduled).compareTo(getBigDecimal(current)) != 0;
    }

    private static BigDecimal getBigDecimal(Double value) {
        return new BigDecimal(value.toString()).setScale(3, RoundingMode.HALF_UP);
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
        return this != state && (originalState == null || originalState != state);
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
                      .transitionTime(getTransitionTime(now))
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
                getFormattedPropertyIfSet("brightness", brightness) +
                getFormattedPropertyIfSet("ct", ct) +
                getFormattedPropertyIfSet("x", x) +
                getFormattedPropertyIfSet("y", y) +
                getFormattedPropertyIfSet("hue", hue) +
                getFormattedPropertyIfSet("sat", sat) +
                getFormattedPropertyIfSet("effect", effect) +
                getFormattedDaysOfWeek() +
                getFormattedTransitionTimeBefore() +
                getFormattedTransitionTimeIfSet("transitionTime", definedTransitionTime) +
                getFormattedPropertyIfSet("lastSeen", getFormattedTime(lastSeen)) +
                getFormattedPropertyIfSet("force", force) +
                '}';
    }

    public String getFormattedName() {
        if (groupState) {
            return "Group '" + name + "'";
        }
        return "Light '" + name + "'";
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
            return formatPropertyName("transitionTimeBefore") + transitionTimeBeforeString +
                    " (" + formatTransitionTime(getTransitionTimeBefore(lastStart)) + ")";
        }
        return formatPropertyName("transitionTimeBefore") + transitionTimeBeforeString;
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
