package at.sv.hue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

final class EnforcedState {
    static final int CONFIRM_AMOUNT = 30;

    private final String name;
    private final int updateId;
    private final int statusId;
    private final String start;
    private final Integer brightness;
    private final Integer ct;
    private final Boolean on;
    private final StartTimeProvider startTimeProvider;
    private final boolean groupState;
    private int confirmCounter;
    private ZonedDateTime end;
    private ZonedDateTime lastStart;

    public EnforcedState(String name, int id, String start, Integer brightness, Integer ct, Boolean on, StartTimeProvider startTimeProvider) {
        this(name, id, id, start, brightness, ct, on, startTimeProvider, false);
    }

    public EnforcedState(String name, int updateId, int statusId, String start, Integer brightness, Integer ct, Boolean on,
                         StartTimeProvider startTimeProvider, boolean groupState) {
        this.name = name;
        this.updateId = updateId;
        this.statusId = statusId;
        this.start = start;
        this.brightness = assertValidBrightnessValue(brightness);
        this.ct = assertValidCtValue(ct);
        this.on = on;
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

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "EnforcedState{" +
                "name=" + name +
                ", updateId=" + updateId +
                ", statusId=" + statusId +
                ", start=" + start + " (" + getStartIfAvailable() + ")" +
                ", brightness=" + brightness +
                ", on=" + on +
                ", ct=" + ct +
                ", confirmCounter=" + confirmCounter +
                ", end=" + end.toLocalDateTime() +
                '}';
    }

    private LocalTime getStartIfAvailable() {
        if (lastStart != null) {
            return getStart(lastStart);
        } else {
            return null;
        }
    }
}
