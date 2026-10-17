package de.crown.cinema;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackPreferencesTest {
    @TempDir Path directory;

    @Test void retainsIndependentChoicesAfterRestartAndCanResetOne() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var preferences = new PackPreferences(directory);
        assertEquals(PackPreferences.Choice.ASK, preferences.get(first));
        preferences.set(first, PackPreferences.Choice.ON);
        preferences.set(second, PackPreferences.Choice.OFF);
        preferences = new PackPreferences(directory);
        assertEquals(PackPreferences.Choice.ON, preferences.get(first));
        assertEquals(PackPreferences.Choice.OFF, preferences.get(second));
        preferences.set(first, PackPreferences.Choice.ASK);
        var restarted = new PackPreferences(directory);
        assertEquals(PackPreferences.Choice.ASK, restarted.get(first));
        assertEquals(PackPreferences.Choice.OFF, restarted.get(second));
    }
}
