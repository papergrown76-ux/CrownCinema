package de.crown.cinema;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinemaConfigTest {
    @Test void unlistedServersAreDisabledAndModesAreExplicit() {
        Properties p = new Properties(); p.setProperty("cinema.server.Lobby", "proxy"); p.setProperty("cinema.server.survival", "bridge");
        var config = CinemaConfig.from(p);
        assertEquals(CinemaConfig.ServerMode.OFF,config.serverMode("pvp"));
        assertEquals(CinemaConfig.ServerMode.PROXY,config.serverMode("lobby"));
        assertEquals(CinemaConfig.ServerMode.BRIDGE,config.serverMode("SURVIVAL"));
        p.setProperty("cinema.server.pvp", "automatic");
        assertThrows(IllegalArgumentException.class,() -> CinemaConfig.from(p));
    }
    @Test void defaultsToSelfHostingWithoutForcingThePack() {
        var config = CinemaConfig.from(new Properties());
        assertNull(config.packUrl());
        assertFalse(config.required());
        assertTrue(config.httpEnabled());
        assertEquals("0.0.0.0", config.httpBind());
        assertEquals("http://play.example.org:8124/crown-cinema.zip", config.packUrlFor("play.example.org").toString());
        assertNull(config.packUrlFor(null));
    }
    @Test void rejectsCredentialsFragmentsAndNonHttpUrls() {
        for (String value : new String[]{"https://user:secret@example.com/pack.zip", "file:///etc/passwd", "https://example.com/pack.zip#fragment"}) {
            Properties p = new Properties(); p.setProperty("pack.url", value);
            assertThrows(IllegalArgumentException.class, () -> CinemaConfig.from(p));
        }
    }
    @Test void explicitDownloadUrlOverridesAutomaticHosting() {
        Properties p = new Properties(); p.setProperty("pack.url", "https://cdn.example.org/pack.zip");
        assertEquals("https://cdn.example.org/pack.zip", CinemaConfig.from(p).packUrlFor("play.example.org").toString());
        p.setProperty("pack.url", "http://cdn.example.org/pack.zip");
        assertEquals("http://cdn.example.org/pack.zip", CinemaConfig.from(p).packUrlFor(null).toString());
    }
    @Test void supportsFixedPublicHostIpv6AndCustomPort() {
        Properties p = new Properties(); p.setProperty("pack.http.public-host", "downloads.example.org");
        p.setProperty("pack.http.port", "9001");
        assertEquals("http://downloads.example.org:9001/crown-cinema.zip", CinemaConfig.from(p).packUrlFor("play.example.org").toString());
        p.setProperty("pack.http.public-host", "2001:db8::1");
        assertEquals("http://[2001:db8::1]:9001/crown-cinema.zip", CinemaConfig.from(p).packUrlFor(null).toString());
        for (String host : new String[]{"https://example.org", "example.org/path", "user@example.org", "example.org:9001"}) {
            p.setProperty("pack.http.public-host", host);
            assertThrows(IllegalArgumentException.class, () -> CinemaConfig.from(p));
        }
    }
    @Test void existingExplicitDisabledSettingRemainsDisabled() {
        Properties p = new Properties(); p.setProperty("pack.http.enabled", "false");
        assertNull(CinemaConfig.from(p).packUrlFor("play.example.org"));
    }
    @Test void validatesDurationAndBooleanSettings() {
        Properties p = new Properties(); p.setProperty("cinema.endcard-seconds", "999999");
        assertThrows(IllegalArgumentException.class, () -> CinemaConfig.from(p));
        p.clear(); p.setProperty("pack.required", "tru");
        assertThrows(IllegalArgumentException.class, () -> CinemaConfig.from(p));
    }
}
