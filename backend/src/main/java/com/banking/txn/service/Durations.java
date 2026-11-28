package com.banking.txn.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Durations {

    private Durations() {
    }

    /** Milliseconds elapsed since startNanos (from System.nanoTime()), with 3 decimals. */
    static BigDecimal millisSince(long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        // nanos with scale 6 = milliseconds (1 ms = 1,000,000 ns)
        return BigDecimal.valueOf(elapsedNanos, 6).setScale(3, RoundingMode.HALF_UP);
    }
}
