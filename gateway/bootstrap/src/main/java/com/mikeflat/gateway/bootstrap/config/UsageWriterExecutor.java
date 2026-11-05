package com.mikeflat.gateway.bootstrap.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class UsageWriterExecutor {

    public static final String THREAD_NAME = "usage-writer";

    private UsageWriterExecutor() {
    }

    public static ThreadPoolExecutor create(int queueCapacity) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
