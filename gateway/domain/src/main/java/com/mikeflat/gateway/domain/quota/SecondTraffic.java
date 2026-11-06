package com.mikeflat.gateway.domain.quota;

public record SecondTraffic(long second, long allowed, long rejected) {
}
