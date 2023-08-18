package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.State;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Getter
@Setter
final class ScheduledState {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String MULTI_COLOR_LOOP = "multi_colorloop";
    private static final String COLOR_LOOP = "colorloop";
    public static final int MAX_HUE_VALUE = 65535;
    public static final int MIDDLE_HUE_VALUE = 32768;

    private final String name;
    private final int updateId;
    private final String start;
    private final Integer brightness;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Integer hue;
    private final Integer sat;
    private final Boolean on;
    private final Integer transitionTime;
    private final Integer transitionTimeBefore;
    private final StartTimeProvider startTimeProvider;
    private final String effect;
    private final EnumSet<DayOfWeek> daysOfWeek;
    private final boolean groupState;
    private final Boolean force;
    private final List<Integer> groupLights;
    private final LightCapabilities capabilities;
    private ZonedDateTime end;
    private ZonedDateTime lastStart;
    private boolean temporary;
    private ZonedDateTime lastSeen;
    private Consumer<ZonedDateTime> lastSeenSync;
    private ScheduledState originalState;

    @Builder
    public ScheduledState(String name, int updateId, String start, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, String effect, Boolean on, Integer transitionTimeBefore, Integer transitionTime,
                          Set<DayOfWeek> daysOfWeek, StartTimeProvider startTimeProvider, boolean groupState,
                          List<Integer> groupLights, LightCapabilities capabilities, Boolean force) {
        this.name = name;
        this.updateId = updateId;
        this.start = start;
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
        this.transitionTime = assertValidTransitionTime(transitionTime);
        this.transitionTimeBefore = assertValidTransitionTime(transitionTimeBefore);
        this.startTimeProvider = startTimeProvider;
        this.force = force;
        temporary = false;
        originalState = this;
        assertColorCapabilities();
    }
    
    public static ScheduledState createTemporaryCopyNow(ScheduledState state, ZonedDateTime now, ZonedDateTime end) {
        return createTemporaryCopy(state, now.toLocalTime().toString(), now, end, null);
    }
    
    public static ScheduledState createTemporaryCopy(ScheduledState state, ZonedDateTime end) {
        return createTemporaryCopy(state, state.start, state.lastStart, end, state.transitionTimeBefore);
    }
    
    private static ScheduledState createTemporaryCopy(ScheduledState state, String start, ZonedDateTime lastStart, ZonedDateTime end,
            Integer transitionTimeBefore) {
        ScheduledState copy = new ScheduledState(state.name, state.updateId, start,
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.effect, state.on, transitionTimeBefore,
                state.transitionTime, state.daysOfWeek, state.startTimeProvider, state.groupState, state.groupLights,
                state.capabilities, state.force);
        copy.end = end;
        copy.temporary = true;
        copy.lastStart = lastStart;
        copy.lastSeen = state.lastSeen;
        copy.lastSeenSync = state::setLastSeen;
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
        if (transitionTime != null && (transitionTime > 65535 || transitionTime < 0)) {
            throw new InvalidTransitionTime("Invalid transition time '" + transitionTime + ". Allowed integer range: 0-65535");
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

    public long getDelayInSeconds(ZonedDateTime now) {
        Duration between = Duration.between(now, getStart(now));
        if (between.isNegative()) {
            return 0;
        } else {
            return between.getSeconds();
        }
    }

    public boolean isInThePastOrNow(ZonedDateTime now) {
        return getDelayInSeconds(now) == 0;
    }

    public ZonedDateTime getStart(ZonedDateTime dateTime) {
        ZonedDateTime definedStart = getStartWithoutTransitionTimeBefore(dateTime);
        if (transitionTimeBefore != null) {
            return definedStart.with(definedStart.toLocalTime().minus(transitionTimeBefore * 100L, ChronoUnit.MILLIS));
        }
        return definedStart;
    }

    private ZonedDateTime getStartWithoutTransitionTimeBefore(ZonedDateTime now) {
        DayOfWeek day;
        DayOfWeek today = DayOfWeek.from(now);
        if (daysOfWeek.contains(today)) {
            day = today;
        } else {
            day = getNextDayAfterTodayOrFirstNextWeek(today);
        }
        now = now.with(TemporalAdjusters.nextOrSame(day));
        return startTimeProvider.getStart(start, now);
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
            return transitionTime;
        }
        return adjustedTrBefore;
    }

    public int getAdjustedTransitionTimeBefore(ZonedDateTime now) {
        if (transitionTimeBefore == null) return 0;
        ZonedDateTime definedStart = getDefinedStartInTheFuture();
        Duration between = Duration.between(now, definedStart);
        if (between.isZero() || between.isNegative()) return 0;
        return (int) between.toMillis() / 100;
    }
    
    private ZonedDateTime getDefinedStartInTheFuture() { // todo: write more tests to verify this behavior
        ZonedDateTime definedStart = getStartWithoutTransitionTimeBefore(lastStart); // .with(lastStart.toLocalDate())
        if (definedStart.isBefore(lastStart)) {
            return getStartWithoutTransitionTimeBefore(lastStart.plusDays(1)); // with(lastStart.plusDays(1).toLocalDate())
        }
        return definedStart;
    }
    
    /**
     * t = (current_time - start_time) / (end_time - start_time)
     */
    public BigDecimal getInterpolatedTime(ZonedDateTime now) {
        ZonedDateTime startTime = getStart(now).with(now.toLocalDate());
        Duration durationAfterStart = Duration.between(startTime, now);
        ZonedDateTime endTime = getDefinedStartInTheFuture();
        Duration totalDuration = Duration.between(startTime, endTime);
        return BigDecimal.valueOf(durationAfterStart.toMillis())
                .divide(BigDecimal.valueOf(totalDuration.toMillis()), 7, RoundingMode.HALF_UP);
    }
    
    public boolean endsAfter(ZonedDateTime now) {
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

    public void updateLastStart(ZonedDateTime now) {
        this.lastStart = getStart(now);
    }
    
    public boolean lightStateDiffers(State currentState) {
        return brightnessDiffers(currentState) ||
                colorModeOrValuesDiffer(currentState) ||
                effectDiffers(currentState);
    }
    
    private boolean brightnessDiffers(State currentState) {
        return brightness != null && !brightness.equals(currentState.getBrightness());
    }
    
    private boolean colorModeOrValuesDiffer(State currentState) {
        ColorMode colorMode = getColorMode();
        if (colorMode == ColorMode.NONE) {
            return false; // if no color mode scheduled, always treat as equal
        }
        if (!Objects.equals(colorMode, currentState.getColormode())) {
            return true;
        }
        switch (colorMode) {
            case CT:
                return ct != null && !ct.equals(currentState.getColorTemperature());
            case HS:
                return hue != null && !hue.equals(currentState.getHue()) ||
                        sat != null && !sat.equals(currentState.getSat());
            case XY:
                return doubleValueDiffers(x, currentState.getX()) ||
                        doubleValueDiffers(y, currentState.getY());
            default:
                return false; // should not happen, but as a fallback we just ignore unknown color modes
        }
    }

    public ColorMode getColorMode() {
        if (ct != null) {
            return ColorMode.CT;
        } else if (x != null) {
            return ColorMode.XY;
        } else if (hue != null || sat != null) {
            return ColorMode.HS;
        }
        return ColorMode.NONE;
    }
    
    private boolean effectDiffers(State currentState) {
        if (getEffect() == null) {
            return false; // if no effect scheduled, always treat as equal
        } else if (currentState.getEffect() == null) {
            return !"none".equals(getEffect()); // if effect scheduled, but none set, only consider "none" to be equal
        } else {
            return !getEffect().equals(currentState.getEffect()); // otherwise, effects have to be exactly the same
        }
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
        if (lastSeenSync != null) {
            lastSeenSync.accept(lastSeen);
        }
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
    
    public PutCall getInterpolatedPutCall(ZonedDateTime now, ScheduledState previousState) {
        return new StateInterpolator(this, previousState, now).getInterpolatedPutCall();
    }

    @Override
    public String toString() {
        return getFormattedName() + " {" +
                "id=" + getUpdateId() +
                (temporary ? ", temporary" : "") +
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
                getFormattedTransitionTimeIfSet("transitionTimeBefore", transitionTimeBefore) +
                getFormattedTransitionTimeIfSet("transitionTime", transitionTime) +
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
            return start + " (" + getFormattedTime(lastStart) + ")";
        }
        return start;
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
        return formatPropertyName(name) + Duration.ofMillis(transitionTime * 100L).toString();
    }
}
