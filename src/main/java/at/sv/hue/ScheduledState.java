package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.time.StartTimeProvider;

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

final class ScheduledState {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String MULTI_COLOR_LOOP = "multi_colorloop";
    private static final String COLOR_LOOP = "colorloop";

    private final String name;
    private final int updateId;
    private final String start;
    private final Boolean confirm;
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
    private final List<Integer> groupLights;
    private final LightCapabilities capabilities;
    private int confirmCounter;
    private ZonedDateTime end;
    private ZonedDateTime lastStart;
    private boolean temporary;

    public ScheduledState(String name, int updateId, String start, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, String effect, Boolean on, Integer transitionTimeBefore, Integer transitionTime,
                          EnumSet<DayOfWeek> daysOfWeek, Boolean confirm, StartTimeProvider startTimeProvider, boolean groupState,
                          List<Integer> groupLights, LightCapabilities capabilities) {
        this.name = name;
        this.updateId = updateId;
        this.start = start;
        this.confirm = confirm;
        if (daysOfWeek.isEmpty()) {
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
        confirmCounter = 0;
        temporary = false;
        assertColorCapabilities();
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state, ZonedDateTime start, ZonedDateTime end) {
        ScheduledState copy = new ScheduledState(state.name, state.updateId, start.toLocalTime().toString(),
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.effect, state.on, state.transitionTimeBefore,
                state.transitionTime, state.daysOfWeek, state.confirm, state.startTimeProvider, state.groupState, state.groupLights, state.capabilities);
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
        Duration between = Duration.between(now, getStart(now));
        if (between.isNegative()) {
            return 0;
        } else {
            return between.getSeconds();
        }
    }

    public long secondsUntilNextDayFromStart(ZonedDateTime now) {
        return Duration.between(now, getNextStart(now)).getSeconds();
    }

    public ZonedDateTime getNextStart(ZonedDateTime now) {
        if (shouldConsiderNextDayForStart(now)) {
            return getStart(now.plusDays(1));
        } else {
            return getStart(now);
        }
    }

    private boolean shouldConsiderNextDayForStart(ZonedDateTime now) {
        ZonedDateTime start = getStart(now);
        return !now.toLocalTime().isBefore(start.toLocalTime()) || startTimeHasChangedSinceLastTime(start);
    }

    private boolean startTimeHasChangedSinceLastTime(ZonedDateTime zonedStart) {
        return lastStart != null && !zonedStart.toLocalTime().equals(lastStart.toLocalTime());
    }

    public boolean isInThePast(ZonedDateTime now) {
        return getDelayInSeconds(now) == 0;
    }

    public ZonedDateTime getStart(ZonedDateTime dateTime) {
        ZonedDateTime start = getStartWithoutTransitionTime(dateTime);
        if (transitionTimeBefore != null) {
            start = start.minus(transitionTimeBefore * 100, ChronoUnit.MILLIS);
        }
        return start;
    }

    private ZonedDateTime getStartWithoutTransitionTime(ZonedDateTime now) {
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

    public EnumSet<DayOfWeek> getDaysOfWeek() {
        return EnumSet.copyOf(daysOfWeek);
    }

    public boolean isScheduledOn(ZonedDateTime day) {
        return isScheduledOn(DayOfWeek.from(day));
    }

    public boolean isScheduledOn(DayOfWeek... day) {
        return getDaysOfWeek().containsAll(Arrays.asList(day));
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
        ZonedDateTime definedStart = getStartWithoutTransitionTime(lastStart).with(lastStart.toLocalDate());
        Duration between = Duration.between(now, definedStart);
        if (between.isZero() || between.isNegative()) return transitionTime;
        return (int) between.toMillis() / 100;
    }

    public boolean isFullyConfirmed(int confirmCount) {
        return confirmCounter >= confirmCount;
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

    public String getConfirmDebugString(int confirmCount) {
        return confirmCounter + "/" + confirmCount;
    }

    public void updateLastStart(ZonedDateTime now) {
        this.lastStart = getStart(now);
    }

    public boolean isTemporary() {
        return temporary;
    }

    public boolean shouldConfirm(boolean confirmAll) {
        if (confirm == null) return confirmAll;
        return confirm;
    }

    @Override
    public String toString() {
        return  getFormattedName() + " {" +
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
            return start + " (" + getFormattedTime(getStart(lastStart)) + ")";
        }
        return start;
    }

    private String getFormattedTime(ZonedDateTime zonedDateTime) {
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
        return formatPropertyName(name) + Duration.ofMillis(transitionTime * 100).toString();
    }
}
