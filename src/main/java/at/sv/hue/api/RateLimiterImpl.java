/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package at.sv.hue.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Adapted from <a href="https://github.com/google/guava">com.google.common.util.concurrent.RateLimiter</a>
 * License: Apache-2.0 License
 */
final class RateLimiterImpl implements RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimiterImpl.class);
    private static final long ONE_SECOND_IN_NANOS = Duration.ofSeconds(1).toNanos();

    private final double permitsPerSecond;
    private final Supplier<Long> nanoTime;
    private final Consumer<Long> sleep;
    private final double stableIntervalNanos;
    private final Object mutex = new Object();

    private long nextFreeTicketNanos = 0L;
    private double storedPermits;

    RateLimiterImpl(double permitsPerSecond, Supplier<Long> nanoTime, Consumer<Long> sleep) {
        this.permitsPerSecond = permitsPerSecond;
        this.nanoTime = nanoTime;
        this.sleep = sleep;
        storedPermits = 0.0;
        stableIntervalNanos = SECONDS.toNanos(1L) / permitsPerSecond;
        reSync();
    }

    private void reSync() {
        long nowNanos = nanoTime.get();
        if (nowNanos > nextFreeTicketNanos) {
            double newPermits = (nowNanos - nextFreeTicketNanos) / stableIntervalNanos;
            storedPermits = min(permitsPerSecond, storedPermits + newPermits);
            nextFreeTicketNanos = nowNanos;
        }
    }

    @Override
    public void acquire(int permits) {
        long pointInTime;
        synchronized (mutex) {
            pointInTime = reserveEarliestAvailable(permits);
        }
        long waitLength = Math.max(pointInTime - nanoTime.get(), 0);
        if (waitLength > ONE_SECOND_IN_NANOS) {
            LOG.trace("Waiting for {}", Duration.ofNanos(waitLength));
        }
        sleep.accept(waitLength);
    }

    private long reserveEarliestAvailable(int requiredPermits) {
        reSync();
        long returnValue = nextFreeTicketNanos;
        double storedPermitsToSpend = min(requiredPermits, storedPermits);
        double freshPermits = requiredPermits - storedPermitsToSpend;
        long waitNanos = (long) (freshPermits * stableIntervalNanos);
        this.nextFreeTicketNanos = saturatedAdd(nextFreeTicketNanos, waitNanos);
        this.storedPermits -= storedPermitsToSpend;
        return returnValue;
    }

    private long saturatedAdd(long a, long b) {
        long naiveSum = a + b;
        if ((a ^ b) < 0 | (a ^ naiveSum) >= 0) {
            return naiveSum;
        }
        return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
    }
}
