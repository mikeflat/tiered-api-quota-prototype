package com.mikeflat.gateway.app.snapshot;

import java.util.List;

public sealed interface TenantRows {

    PageRequest page();

    record Available(List<TenantRow> rows, int total, PageRequest page) implements TenantRows {

        public Available {
            rows = List.copyOf(rows);
        }
    }

    record Unavailable(PageRequest page) implements TenantRows {
    }
}
