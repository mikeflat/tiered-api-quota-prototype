package com.mikeflat.gateway.domain.tenant;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum Tier {
    BRONZE("Bronze"),
    SILVER("Silver"),
    GOLD("Gold");

    private final String displayName;

    Tier(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Tier parse(String value) {
        if (value != null) {
            for (Tier tier : values()) {
                if (tier.displayName.equalsIgnoreCase(value)) {
                    return tier;
                }
            }
        }
        String known = Arrays.stream(values()).map(Tier::displayName).collect(Collectors.joining(", "));
        String shown = value == null ? "null" : "'" + value + "'";
        throw new IllegalArgumentException("unknown tier " + shown + " (expected one of " + known + ")");
    }

    @Override
    public String toString() {
        return displayName;
    }
}
