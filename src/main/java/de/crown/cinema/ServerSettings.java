package de.crown.cinema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Live server modes; publish a change only after it has been saved successfully. */
final class ServerSettings {
    private final Path file;
    private volatile Map<String, CinemaConfig.ServerMode> modes;

    ServerSettings(Path directory, Map<String, CinemaConfig.ServerMode> initial) {
        file = directory.resolve("config.properties");
        modes = Map.copyOf(initial);
    }

    CinemaConfig.ServerMode mode(String name) { return modes.getOrDefault(key(name), CinemaConfig.ServerMode.OFF); }
    boolean configured(String name) { return modes.containsKey(key(name)); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    synchronized void set(String name, CinemaConfig.ServerMode mode) throws IOException {
        Objects.requireNonNull(mode);
        String nameKey = key(name);
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        // Preserve every other setting, including externally edited download ports.
        properties.stringPropertyNames().stream()
                .filter(k -> k.startsWith("cinema.server.") && key(k.substring("cinema.server.".length()).trim()).equals(nameKey))
                .toList().forEach(properties::remove);
        properties.setProperty("cinema.server." + nameKey, mode.name().toLowerCase(Locale.ROOT));
        Path temporary = Files.createTempFile(file.getParent(), "cinema-config-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Crown Cinema configuration; server modes can be changed with /crowncinema servers");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            var updated = new HashMap<>(modes);
            updated.put(nameKey, mode);
            modes = Map.copyOf(updated);
        } finally { Files.deleteIfExists(temporary); }
    }
}
