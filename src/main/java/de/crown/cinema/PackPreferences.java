package de.crown.cinema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Proxy-wide player choices, retained across reconnects and restarts. */
final class PackPreferences {
    enum Choice { ON, OFF, ASK }

    private final Path file;
    private final Map<UUID, Choice> choices = new HashMap<>();

    PackPreferences(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("pack-preferences.properties");
        if (Files.exists(file)) {
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            for (String key : properties.stringPropertyNames()) {
                choices.put(UUID.fromString(key), Choice.valueOf(properties.getProperty(key)));
            }
        }
    }

    synchronized Choice get(UUID player) { return choices.getOrDefault(player, Choice.ASK); }

    synchronized void set(UUID player, Choice choice) throws IOException {
        if (get(player) == choice) return;
        var properties = new Properties();
        choices.forEach((id, value) -> properties.setProperty(id.toString(), value.name()));
        if (choice == Choice.ASK) properties.remove(player.toString());
        else properties.setProperty(player.toString(), choice.name());
        Path temporary = Files.createTempFile(file.getParent(), "pack-preferences-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Crown Cinema player pack choices: ON/OFF; missing = ASK");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            if (choice == Choice.ASK) choices.remove(player);
            else choices.put(player, choice);
        } finally { Files.deleteIfExists(temporary); }
    }
}
