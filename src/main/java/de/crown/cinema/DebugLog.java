package de.crown.cinema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;
import org.slf4j.Logger;

/** Opt-in diagnostic events. Never records pack URLs, IPs, video bytes or chat. */
final class DebugLog {
    private final Path settings, file;
    private final Logger logger;
    private volatile boolean enabled;
    DebugLog(Path directory, Logger logger) throws IOException {
        this.logger = logger; settings = directory.resolve("debug.properties"); file = directory.resolve("debug.log");
        var p = new Properties();
        if (Files.exists(settings)) try (var in = Files.newInputStream(settings)) { p.load(in); }
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
    }
    boolean enabled() { return enabled; }
    synchronized void set(boolean value) throws IOException {
        Path temp = Files.createTempFile(settings.getParent(), "debug-", ".tmp");
        try {
            Files.writeString(temp, "# Persistent Crown Cinema debug mode\nenabled=" + value + "\n", StandardCharsets.UTF_8);
            Files.move(temp, settings, StandardCopyOption.REPLACE_EXISTING);
            enabled = value; record("debug.enabled", "enabled=" + value);
        } finally { Files.deleteIfExists(temp); }
    }
    synchronized void record(String event, String detail) {
        if (!enabled) return;
        String text = detail.replace('\n', ' ').replace('\r', ' ');
        if (text.length() > 1600) text = text.substring(0,1600);
        String line = Instant.now() + " " + event + " " + text;
        logger.info("[debug] {}", line);
        try {
            if (Files.exists(file) && Files.size(file) >= 2 * 1024 * 1024) Files.move(file, file.resolveSibling("debug.log.1"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { logger.warn("Could not write Crown Cinema debug log: {}", e.getMessage()); }
    }
}
