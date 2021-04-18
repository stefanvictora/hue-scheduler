package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.time.StartTimeProvider;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

final class ScheduledState {
    static final int CONFIRM_AMOUNT = 30;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String MULTI_COLOR_LOOP = "multi_colorloop";
    private static final String COLOR_LOOP = "colorloop";

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
    private final boolean groupState;
    private final List<Integer> groupLights;
    private final LightCapabilities capabilities;
    private int confirmCounter;
    private ZonedDateTime end;
    private ZonedDateTime lastStart;
    private boolean temporary;

    public ScheduledState(String name, int id, String start, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, String effect, Boolean on, Integer transitionTimeBefore, Integer transitionTime,
                          StartTimeProvider startTimeProvider, LightCapabilities capabilities) {
        this(name, id, start, brightness, ct, x, y, hue, sat, effect, on, transitionTimeBefore, transitionTime, startTimeProvider,
                false, null, capabilities);
    }

    public ScheduledState(String name, int updateId, String start, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, String effect, Boolean on, Integer transitionTimeBefore, Integer transitionTime,
                          StartTimeProvider startTimeProvider, boolean groupState, List<Integer> groupLights, LightCapabilities capabilities) {
        this.name = name;
        this.updateId = updateId;
        this.start = start;
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
        confirmCounter = 0;
        temporary = false;
        assertColorCapabilities();
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state, ZonedDateTime start, ZonedDateTime end) {
        ScheduledState copy = new ScheduledState(state.name, state.updateId, start.toLocalTime().toString(),
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.effect, state.on, state.transitionTimeBefore,
                state.transitionTime, state.startTimeProvider, state.groupState, state.groupLights, state.capabilities);
        copy.end = end;
        copy.temporary = true;
        copy.lastStart = start;
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
        if (brightness != null && (brightness > 254 || brightness < 1)) {
            throw new InvalidBrightnessValue("Invalid brightness value '" + brightness + "'. Allowed integer range: 1-254");
        }
        return brightness;
    }

    private Integer assertCtSupportAndValue(Integer ct) {
        if (ct == null || isGroupState()) return ct;
        assertCtSupported();
        if (ct > capabilities.getCtMax() || ct < capabilities.getCtMin()) {
            throw new InvalidColorTemperatureValue("Invalid ct value '" + ct + "'. Support integer range for light model: "
                    + capabilities.getCtMin() + "-" + capabilities.getCtMax());
        }
        return ct;
    }

    private void assertCtSupported() {
        if (!capabilities.isCtSupported()) {
            throw new ColorTemperatureNotSupported("Light '" + getName() + "' does not support setting color temperature!");
        }
    }

    private Double assertValidXAndY(Double xOrY) {
        if (xOrY != null && (xOrY > 1 || xOrY < 0)) {
            throw new InvalidXAndYValue("Invalid x or y value '" + xOrY + "'. Allowed double range: 0-1");
        }
        return xOrY;
    }

    private Integer assertValidHueValue(Integer hue) {
        if (hue != null && (hue > 65535 || hue < 0)) {
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
        if (isGroupState()) return;
        if (isColorState() && !capabilities.isColorSupported()) {
            throw new ColorNotSupported("Light '" + getName() + "' does not support setting color!");
        }
    }

    private boolean isColorState() {
        return x != null || y != null || hue != null || sat != null || effect != null;
    }

    public long getDelayInSeconds(ZonedDateTime now) {
        Duration between = Duration.between(now, getZonedStart(now));
        if (between.isNegative()) {
            return 0;
        } else {
            return between.getSeconds();
        }
    }

    private ZonedDateTime getZonedStart(ZonedDateTime now) {
        return getZonedStart(now, getStart(now));
    }

    private ZonedDateTime getZonedStart(ZonedDateTime now, LocalTime start) {
        return ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), start), now.getZone());
    }

    public long secondsUntilNextDayFromStart(ZonedDateTime now) {
        ZonedDateTime zonedStart = getZonedStart(now, getStart(now.plusDays(1)));
        Duration between = Duration.between(now, zonedStart);
        if (now.isAfter(zonedStart) || now.isEqual(zonedStart) || startHasAdvancedSinceLastTime(zonedStart)) {
            return between.plusDays(1).getSeconds();
        }
        return between.getSeconds();
    }

    private boolean startHasAdvancedSinceLastTime(ZonedDateTime zonedStart) {
        return lastStart != null && zonedStart.toLocalTime().isAfter(lastStart.toLocalTime());
    }

    public boolean isInThePast(ZonedDateTime now) {
        return getDelayInSeconds(now) == 0;
    }

    public LocalTime getStart(ZonedDateTime dateTime) {
        LocalTime start = getStartTime(dateTime);
        if (transitionTimeBefore != null) {
            start = start.minus(transitionTimeBefore * 100, ChronoUnit.MILLIS);
        }
        return start;
    }

    private LocalTime getStartTime(ZonedDateTime now) {
        return startTimeProvider.getStart(this.start, now);
    }

    public String getName() {
        return name;
    }

    public int getUpdateId() {
        return updateId;
    }

    public int getStatusId() {
        if (groupState) {
            return groupLights.get(0);
        }
        return updateId;
    }

    public Integer getBrightness() {
        return brightness;
    }

    public Integer getCt() {
        return ct;
    }

    public Double getX() {
        return x;
    }

    public Double getY() {
        return y;
    }

    public Integer getHue() {
        return hue;
    }

    public Integer getSat() {
        return sat;
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

    public Boolean getOn() {
        return on;
    }

    public Integer getTransitionTime(ZonedDateTime now) {
        if (transitionTimeBefore == null) return transitionTime;
        ZonedDateTime definedStart = getDefinedStart(lastStart);
        Duration between = Duration.between(now, definedStart);
        if (between.isZero() || between.isNegative()) return transitionTime;
        return (int) between.toMillis() / 100;
    }

    private ZonedDateTime getDefinedStart(ZonedDateTime now) {
        return getZonedStart(now, getStartTime(now));
    }

    public boolean isFullyConfirmed() {
        return confirmCounter >= CONFIRM_AMOUNT;
    }

    public void addConfirmation() {
        confirmCounter++;
    }

    public int getConfirmCounter() {
        return confirmCounter;
    }

    public void resetConfirmations() {
        confirmCounter = 0;
    }

    public boolean endsAfter(ZonedDateTime now) {
        return now.isAfter(end);
    }

    public ZonedDateTime getEnd() {
        return end;
    }

    public void setEnd(ZonedDateTime end) {
        this.end = end;
    }

    public boolean isGroupState() {
        return groupState;
    }

    public List<Integer> getGroupLights() {
        return new ArrayList<>(groupLights);
    }

    public boolean isNullState() {
        return brightness == null && ct == null && on == null && x == null && y == null && hue == null && sat == null && effect == null;
    }

    public boolean isOff() {
        return on == Boolean.FALSE;
    }

    public String getConfirmDebugString() {
        return confirmCounter + "/" + CONFIRM_AMOUNT;
    }

    public void updateLastStart(ZonedDateTime now) {
        this.lastStart = getZonedStart(now);
    }

    public boolean isTemporary() {
        return temporary;
    }

    @Override
    public String toString() {
        return "State{" +
                getFormattedName() +
                ", id=" + getUpdateId() +
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
                getFormattedTransitionTimeIfSet("transitionTimeBefore", transitionTimeBefore) +
                getFormattedTransitionTimeIfSet("transitionTime", transitionTime) +
                '}';
    }

    private String getFormattedName() {
        if (groupState) {
            return "group=" + name;
        }
        return "light=" + name;
    }

    private String getFormattedStart() {
        if (lastStart != null) {
            LocalTime parsedStart = getStart(lastStart);
            if (!parsedStart.equals(lastStart.toLocalTime())) {
                return start + " (" + getFormattedTime(parsedStart) + ")";
            }
        }
        return start;
    }

    private String getFormattedTime(LocalTime localTime) {
        return TIME_FORMATTER.format(localTime);
    }

    private String getFormattedEnd() {
        return end.toLocalDateTime().toString();
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }

    private String getFormattedTransitionTimeIfSet(String name, Integer transitionTime) {
        if (transitionTime == null) return "";
        return formatPropertyName(name) + Duration.ofMillis(transitionTime * 100).toString();
    }
}
