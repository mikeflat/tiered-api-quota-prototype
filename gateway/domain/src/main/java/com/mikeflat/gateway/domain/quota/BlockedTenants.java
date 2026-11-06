package com.mikeflat.gateway.domain.quota;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class BlockedTenants {

    private static final long NO_PERIOD = -1;
    private static final BooleanSupplier ALWAYS = () -> true;

    private record PeriodBlocks(long periodIndex, Set<Long> tenantIds) {

        static PeriodBlocks emptyFor(long periodIndex) {
            return new PeriodBlocks(periodIndex, ConcurrentHashMap.newKeySet());
        }
    }

    private final AtomicReference<PeriodBlocks> current = new AtomicReference<>(PeriodBlocks.emptyFor(NO_PERIOD));

    public boolean isBlocked(long tenantId, long periodIndex) {
        PeriodBlocks blocks = current.get();
        return blocks.periodIndex() == periodIndex && blocks.tenantIds().contains(tenantId);
    }

    public void block(long tenantId, long periodIndex) {
        blockIf(tenantId, periodIndex, ALWAYS);
    }

    public synchronized void blockIf(long tenantId, long periodIndex, BooleanSupplier stillOverLimit) {
        PeriodBlocks blocks = current.updateAndGet(
                held -> held.periodIndex() < periodIndex ? PeriodBlocks.emptyFor(periodIndex) : held);
        if (blocks.periodIndex() == periodIndex && stillOverLimit.getAsBoolean()) {
            blocks.tenantIds().add(tenantId);
        }
    }

    public synchronized void unblock(long tenantId) {
        current.get().tenantIds().remove(tenantId);
    }
}
