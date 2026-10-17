package de.crown.cinema;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerSettingsTest {
    @TempDir Path directory;

    @Test void modeSurvivesRestartWithoutChangingDownloadSettingsOrCustomProperties() throws Exception {
        var file=directory.resolve("config.properties");
        Files.writeString(file,"pack.http.port=9124\npack.http.bind=0.0.0.0\npack.url=https://cdn.example.org/crown.zip\ncustom.setting=Grüße\ncinema.server.LOBBY=proxy\n");
        var initial=CinemaConfig.load(directory);
        var settings=new ServerSettings(directory,initial.serverModes());
        assertFalse(settings.configured("survival"));
        assertEquals(CinemaConfig.ServerMode.OFF,settings.mode("survival"));
        settings.set("SURVIVAL",CinemaConfig.ServerMode.BRIDGE);
        settings.set("lobby",CinemaConfig.ServerMode.OFF);
        var loaded=CinemaConfig.load(directory);
        assertEquals(9124,loaded.httpPort());
        assertEquals("https://cdn.example.org/crown.zip",loaded.packUrl().toString());
        assertEquals(CinemaConfig.ServerMode.BRIDGE,loaded.serverMode("survival"));
        assertEquals(CinemaConfig.ServerMode.OFF,loaded.serverMode("lobby"));
        assertTrue(Files.readString(file).contains("Grüße"));
        var restarted=new ServerSettings(directory,loaded.serverModes());
        assertTrue(restarted.configured("LOBBY"));
        assertEquals(CinemaConfig.ServerMode.BRIDGE,restarted.mode("Survival"));
    }
    @Test void failedSaveDoesNotChangeTheLiveMode() {
        var settings=new ServerSettings(directory,Map.of("lobby",CinemaConfig.ServerMode.PROXY));
        assertThrows(IOException.class,()->settings.set("lobby",CinemaConfig.ServerMode.OFF));
        assertEquals(CinemaConfig.ServerMode.PROXY,settings.mode("lobby"));
    }
}
