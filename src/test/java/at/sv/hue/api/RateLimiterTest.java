package at.sv.hue.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class RateLimiterTest {
    private AtomicLong sleepTime;
    private long time;
    private RateLimiter limiter;

    private void create(int permitsPerSecond) {
        limiter = new RateLimiterImpl(permitsPerSecond, () -> time, sleepTime::set);
    }

    private void acquireAndAssertSleepTime(int permits) {
        acquireAndAssertSleepTime(permits, 0, TimeUnit.SECONDS);
    }

    private void acquireAndAssertSleepTime(int permits, long time, TimeUnit unit) {
        limiter.acquire(permits);
        assertSleepTime(time, unit);
    }

    private void assertSleepTime(long time, TimeUnit unit) {
        assertThat("Sleep time differs", this.sleepTime.get(), is(unit.toNanos(time)));
    }

    private void advanceTime(int time, TimeUnit unit) {
        this.time += unit.toNanos(time);
    }

    @BeforeEach
    void setUp() {
        sleepTime = new AtomicLong();
        time = 0;
        limiter = null;
    }

    @Test
    void acquire_onePermitPerSecond_oneAcquire_noSleep() {
        create(1);

        acquireAndAssertSleepTime(1);
    }

    @Test
    void acquire_onePermitPerSecond_twoAcquires_secondSleepsOneSecond() {
        create(1);

        acquireAndAssertSleepTime(1);

        acquireAndAssertSleepTime(1, 1, TimeUnit.SECONDS);
    }

    @Test
    void acquire_onePermitPerSecond_secondAcquireHalfASecondLater_sleepsForHalfASecond() {
        create(1);

        acquireAndAssertSleepTime(1);

        advanceTime(500, TimeUnit.MILLISECONDS);

        acquireAndAssertSleepTime(1, 500, TimeUnit.MILLISECONDS);
    }

    @Test
    void acquire_onePermitPerSecond_acquireTenPermits_secondAcquireWaitsTenSeconds() {
        create(1);

        acquireAndAssertSleepTime(10);

        acquireAndAssertSleepTime(1, 10, TimeUnit.SECONDS);
    }

    @Test
    void acquire_tenPermitsPerSecond_multipleAcquires_spacedOutEvenly() {
        create(10);

        acquireAndAssertSleepTime(5);
        acquireAndAssertSleepTime(5, 500, TimeUnit.MILLISECONDS);
        acquireAndAssertSleepTime(1, 1, TimeUnit.SECONDS);

        advanceTime(1, TimeUnit.SECONDS);

        acquireAndAssertSleepTime(10, 100, TimeUnit.MILLISECONDS);
    }
}
