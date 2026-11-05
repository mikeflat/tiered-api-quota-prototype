package com.mikeflat.gateway.domain.quota;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class BlockedTenants {

    private static final long NO_PERIOD = -1;

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
        PeriodBlocks blocks = current.updateAndGet(
                held -> held.periodIndex() < periodIndex ? PeriodBlocks.emptyFor(periodIndex) : held);
        if (blocks.periodIndex() == periodIndex) {
            blocks.tenantIds().add(tenantId);
        }
    }
}
