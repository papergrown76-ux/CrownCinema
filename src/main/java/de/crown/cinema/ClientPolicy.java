package de.crown.cinema;

import com.velocitypowered.api.network.ProtocolVersion;

final class ClientPolicy {
    static final int MINIMUM = 765; // 1.20.3 / 1.20.4
    static final int MAXIMUM = 777; // 26.3; future protocols require explicit validation
    private final int min;
    private final int max;

    ClientPolicy(ProtocolVersion limboMin, ProtocolVersion limboMax) {
        min = Math.max(MINIMUM, limboMin.getProtocol());
        max = Math.min(MAXIMUM, limboMax.getProtocol());
    }

    boolean supports(ProtocolVersion version) {
        return version.isSupported() && version.getProtocol() >= min && version.getProtocol() <= max;
    }

    boolean needsUpgrade(ProtocolVersion version) {
        return version.isSupported() && version.getProtocol() < MINIMUM;
    }

    int maximum() { return max; }
}
