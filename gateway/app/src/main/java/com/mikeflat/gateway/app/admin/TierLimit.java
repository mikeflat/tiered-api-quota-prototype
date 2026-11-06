package com.mikeflat.gateway.app.admin;

import com.mikeflat.gateway.domain.tenant.Tier;

public record TierLimit(Tier tier, long limit) {
}
