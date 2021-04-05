package at.sv.hue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

final class ScheduledState {
    static final int CONFIRM_AMOUNT = 30;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String name;
    private final int updateId;
    private final int statusId;
    private final String start;
    private final Integer brightness;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Integer hue;
    private final Integer sat;
    private final Boolean on;
    private final Integer transitionTime;
    private final StartTimeProvider startTimeProvider;
    private final boolean groupState;
    private int confirmCounter;
    private ZonedDateTime end;
    private ZonedDateTime lastStart;
    private boolean temporary;

    public ScheduledState(String name, int id, String start, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, Boolean on, Integer transitionTime, StartTimeProvider startTimeProvider) {
        this(name, id, id, start, brightness, ct, x, y, hue, sat, on, transitionTime, startTimeProvider, false);
    }

    public ScheduledState(String name, int updateId, int statusId, String start, Integer brightness, Integer ct, Double x, Double y,
                          Integer hue, Integer sat, Boolean on, Integer transitionTime, StartTimeProvider startTimeProvider, boolean groupState) {
        this.name = name;
        this.updateId = updateId;
        this.statusId = statusId;
        this.start = start;
        this.brightness = assertValidBrightnessValue(brightness);
        this.ct = assertValidCtValue(ct);
        this.x = assertValidXAndY(x);
        this.y = assertValidXAndY(y);
        this.hue = assertValidHueValue(hue);
        this.sat = assertValidSaturationValue(sat);
        this.on = on;
        this.transitionTime = transitionTime;
        this.startTimeProvider = startTimeProvider;
        this.groupState = groupState;
        confirmCounter = 0;
        temporary = false;
    }

    public static ScheduledState createTemporaryCopy(ScheduledState state, ZonedDateTime start, ZonedDateTime end) {
        ScheduledState copy = new ScheduledState(state.name, state.updateId, state.statusId, start.toLocalTime().toString(),
                state.brightness, state.ct, state.x, state.y, state.hue, state.sat, state.on, state.transitionTime, state.startTimeProvider, state.groupState);
        copy.end = end;
        copy.temporary = true;
        copy.lastStart = start;
        return copy;
    }

    private Integer assertValidBrightnessValue(Integer brightness) {
        if (brightness != null && (brightness > 254 || brightness < 1)) {
            throw new InvalidBrightnessValue("Invalid brightness value '" + brightness + "'. Allowed integer range: 1-254");
        }
        return brightness;
    }

    private Integer assertValidCtValue(Integer ct) {
        if (ct != null && (ct > 500 || ct < 153)) {
            throw new InvalidColorTemperatureValue("Invalid ct value '" + ct + "'. Allowed integer range: 153-500");
        }
        return ct;
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
        return startTimeProvider.getStart(start, dateTime);
    }

    public String getName() {
        return name;
    }

    public int getUpdateId() {
        return updateId;
    }

    public int getStatusId() {
        return statusId;
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

    public Boolean getOn() {
        return on;
    }

    public Integer getTransitionTime() {
        return transitionTime;
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

    public boolean isNullState() {
        return brightness == null && ct == null && on == null && x == null && y == null && hue == null && sat == null;
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
                ", transitionTime=" + getFormattedTransitionTime() +
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
        return ", " + name + "=" + property;
    }

    private Duration getFormattedTransitionTime() {
        return Duration.ofMillis(Optional.ofNullable(transitionTime).orElse(4) * 100);
    }
}
