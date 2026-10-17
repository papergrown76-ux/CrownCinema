package de.crown.cinema;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

public record CinemaConfig(URI packUrl, boolean required, boolean httpEnabled, String httpBind,
                           int httpPort, String httpPublicHost, int endcardSeconds, int ticketSeconds, int maxViewers,
                           String upgradeMessage, Map<String, ServerMode> serverModes) {
    enum ServerMode { OFF, PROXY, BRIDGE }
    ServerMode serverMode(String name) { return serverModes.getOrDefault(name.toLowerCase(Locale.ROOT), ServerMode.OFF); }
    public static CinemaConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("config.properties");
        if (!Files.exists(path)) {
            try (InputStream in = CinemaConfig.class.getResourceAsStream("/config.properties")) {
                if (in == null) throw new IOException("Missing default configuration");
                Files.copy(in, path);
            }
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
        return from(p);
    }

    static CinemaConfig from(Properties p) {
        String url = p.getProperty("pack.url", "").trim();
        URI uri = url.isEmpty() ? null : URI.create(url);
        if (uri != null && ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null)) {
            throw new IllegalArgumentException("pack.url must be an HTTP(S) URL without credentials or fragment");
        }
        String publicHost = p.getProperty("pack.http.public-host", "").trim();
        int port = number(p, "pack.http.port", 8124, 1, 65535);
        if (!publicHost.isEmpty()) httpUrl(publicHost, port);
        Map<String, ServerMode> modes = new HashMap<>();
        for (String key : p.stringPropertyNames()) if (key.startsWith("cinema.server.")) {
            String name = key.substring("cinema.server.".length()).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) throw new IllegalArgumentException("Missing cinema server name");
            modes.put(name, ServerMode.valueOf(p.getProperty(key).trim().toUpperCase(Locale.ROOT)));
        }
        return new CinemaConfig(uri, bool(p, "pack.required"), bool(p, "pack.http.enabled", true),
                p.getProperty("pack.http.bind", "0.0.0.0"), port, publicHost,
                number(p, "cinema.endcard-seconds", 3, 1, 10), number(p, "cinema.join-ticket-seconds", 300, 10, 3600),
                number(p, "cinema.max-viewers", 200, 1, 2000), p.getProperty("message.upgrade",
                "Please update Minecraft to 1.20.3 or newer. You can continue playing normally."), Map.copyOf(modes));
    }

    URI packUrlFor(String virtualHost) {
        if (packUrl != null) return packUrl;
        if (!httpEnabled) return null;
        String host = httpPublicHost.isEmpty() ? virtualHost : httpPublicHost;
        return host == null || host.isBlank() ? null : httpUrl(host, httpPort);
    }

    private static URI httpUrl(String host, int port) {
        try {
            URI uri = new URI("http", null, host, port, "/crown-cinema.zip", null, null);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != port
                    || !"/crown-cinema.zip".equals(uri.getRawPath())) {
                throw new IllegalArgumentException("Pack download host must contain only a hostname or IP address");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("pack.http.public-host must be only a hostname or IP address, without a URL or port", e);
        }
    }

    private static int number(Properties p, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    private static boolean bool(Properties p, String key) {
        return bool(p, key, false);
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
}
