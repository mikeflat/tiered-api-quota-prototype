package com.mikeflat.gateway.bootstrap;

import java.time.Duration;

final class Eventually {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    @FunctionalInterface
    interface Check {
        void verify() throws Exception;
    }

    private Eventually() {
    }

    static void within(Duration timeout, Check check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                check.verify();
                return;
            } catch (AssertionError notYet) {
                if (System.nanoTime() >= deadline) {
                    throw notYet;
                }
                Thread.sleep(POLL_INTERVAL);
            }
        }
    }
}
