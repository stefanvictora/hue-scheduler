package at.sv.hue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

final class EnforcedState {
    private final int updateId;
    private final int statusId;
    private final LocalTime start;
    private final Integer brightness;
    private final Integer ct;
    private final boolean groupState;
    private int confirmCounter;
    private LocalDateTime end;

    public EnforcedState(int id, LocalTime start, Integer brightness, Integer ct) {
        this(id, id, start, brightness, ct, false);
    }

    public EnforcedState(int updateId, int statusId, LocalTime start, Integer brightness, Integer ct, boolean groupState) {
        this.updateId = updateId;
        this.statusId = statusId;
        this.start = start;
        this.brightness = brightness;
        this.ct = ct;
        this.groupState = groupState;
        confirmCounter = 0;
    }

    public long getDelay(LocalDateTime now) {
        LocalDateTime startDateTime = LocalDateTime.of(now.toLocalDate(), start);
        Duration between = Duration.between(now, startDateTime);
        if (between.isNegative()) {
            return 0;
        } else {
            return between.getSeconds();
        }
    }

    public boolean isInThePast(LocalDateTime now) {
        return getDelay(now) == 0;
    }

    public LocalTime getStart() {
        return start;
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

    public boolean isFullyConfirmed() {
        return confirmCounter >= 120;
    }

    public void addConfirmation() {
        confirmCounter++;
    }

    public void resetConfirmations() {
        confirmCounter = 0;
    }

    public void setEnd(LocalDateTime end) {
        this.end = end;
    }

    public boolean endsAfter(LocalDateTime now) {
        return now.isAfter(end);
    }

    public void shiftEndToNextDay() {
        end = end.plusDays(1);
    }

    public boolean isGroupState() {
        return groupState;
    }

    @Override
    public String toString() {
        return "EnforcedState{" +
                "updateId=" + updateId +
                ", statusId=" + statusId +
                ", start=" + start +
                ", brightness=" + brightness +
                ", ct=" + ct +
                ", groupState=" + groupState +
                ", confirmCounter=" + confirmCounter +
                ", end=" + end +
                '}';
    }
}
