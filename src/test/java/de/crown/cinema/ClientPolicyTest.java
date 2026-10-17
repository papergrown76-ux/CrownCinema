package de.crown.cinema;

import com.velocitypowered.api.network.ProtocolVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientPolicyTest {
    private final ClientPolicy policy = new ClientPolicy(ProtocolVersion.MINIMUM_VERSION, ProtocolVersion.MAXIMUM_VERSION);
    @Test void oldClientsAreOnlyAskedToUpgrade() {
        for (var version : new ProtocolVersion[]{ProtocolVersion.MINECRAFT_1_20, ProtocolVersion.MINECRAFT_1_20_2}) {
            assertTrue(policy.needsUpgrade(version));
            assertFalse(policy.supports(version));
        }
    }
    @Test void supportedRangeIncludesBothBoundaries() {
        assertTrue(policy.supports(ProtocolVersion.MINECRAFT_1_20_3));
        assertTrue(policy.supports(ProtocolVersion.MINECRAFT_26_3));
        assertFalse(policy.supports(ProtocolVersion.UNKNOWN));
    }
    @Test void limboConfigurationLimitsSupportedClients() {
        var limited = new ClientPolicy(ProtocolVersion.MINECRAFT_1_20_3, ProtocolVersion.MINECRAFT_1_21);
        assertTrue(limited.supports(ProtocolVersion.MINECRAFT_1_21));
        assertFalse(limited.supports(ProtocolVersion.MINECRAFT_26_3));
    }
}
