package at.sv.hue.api;

import java.util.concurrent.TimeUnit;

public interface RateLimiter {
    void acquire(int permits);

    static RateLimiter create(double permitsPerSecond) {
        return new RateLimiterImpl(permitsPerSecond, 1, System::nanoTime, RateLimiter::sleep);
    }

    static void sleep(Long time) {
        try {
            TimeUnit.NANOSECONDS.sleep(time);
        } catch (InterruptedException ignore) {
        }
    }
}
