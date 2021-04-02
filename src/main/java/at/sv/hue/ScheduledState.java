package at.sv.hue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

final class ScheduledState {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    static final int CONFIRM_AMOUNT = 30;

    private final String name;
    private final int updateId;
    private final int statusId;
    private final String start;
    private final Integer brightness;
    private final Integer ct;
    private final Boolean on;
    private final Integer transitionTime;
    private final StartTimeProvider startTimeProvider;
    private final boolean groupState;
    private int confirmCounter;
    private ZonedDateTime end;
    private ZonedDateTime lastStart;

    public ScheduledState(String name, int id, String start, Integer brightness, Integer ct, Boolean on, Integer transitionTime,
                          StartTimeProvider startTimeProvider) {
        this(name, id, id, start, brightness, ct, on, transitionTime, startTimeProvider, false);
    }

    public ScheduledState(String name, int updateId, int statusId, String start, Integer brightness, Integer ct, Boolean on,
                          Integer transitionTime, StartTimeProvider startTimeProvider, boolean groupState) {
        this.name = name;
        this.updateId = updateId;
        this.statusId = statusId;
        this.start = start;
        this.brightness = assertValidBrightnessValue(brightness);
        this.ct = assertValidCtValue(ct);
        this.on = on;
        this.transitionTime = transitionTime;
        this.startTimeProvider = startTimeProvider;
        this.groupState = groupState;
        confirmCounter = 0;
    }

    private Integer assertValidBrightnessValue(Integer brightness) {
        if (brightness != null && (brightness > 254 || brightness < 1)) {
            throw new InvalidBrightnessValue("Invalid brightness value '" + brightness + "'. Allowed range: 1-254");
        }
        return brightness;
    }

    private Integer assertValidCtValue(Integer ct) {
        if (ct != null && (ct > 500 || ct < 153)) {
            throw new InvalidColorTemperatureValue("Invalid ct value '" + ct + "'. Allowed range: 153-500");
        }
        return ct;
    }

    public long getDelay(ZonedDateTime now) {
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
        return getDelay(now) == 0;
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
        return brightness == null && ct == null && on == null;
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

    @Override
    public String toString() {
        return "State{" +
                getFormattedName() +
                ", start=" + getFormattedStart() +
                ", end=" + getFormattedEnd() +
                getFormattedPropertyIfSet("on", on) +
                getFormattedPropertyIfSet("brightness", brightness) +
                getFormattedPropertyIfSet("ct", ct) +
                ", transitionTime=" + getFormattedTransitionTime() +
                '}';
    }

    private String getFormattedName() {
        if (groupState) {
            return "group=" +  name;
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
        return getFormattedTime(end.toLocalDateTime().toLocalTime());
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return ", " + name + "=" + property;
    }

    private Duration getFormattedTransitionTime() {
        return Duration.ofMillis(Optional.ofNullable(transitionTime).orElse(4) * 100);
    }
}
