package de.crown.cinema;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import net.kyori.adventure.key.Key;

/** Immutable playback snapshot. Separate fonts/sounds allow concurrent different videos. */
record VideoCatalog(Map<String, Clip> clips, String selected, Map<String, String> servers, PackService.Payload pack) {
    record Clip(VideoLibrary.Prepared prepared, Key font, Key sound) {
        Sequence sequence() { return prepared.sequence(); }
        String name() { return prepared.name(); }
    }
    Clip forServer(String server) { return clips.get(servers.getOrDefault(server.toLowerCase(Locale.ROOT), selected)); }
    Clip defaultClip() { return clips.get(selected); }

    static VideoCatalog build(Path directory, Map<String, VideoLibrary.Prepared> prepared, String selected,
                              Map<String, String> servers) throws IOException {
        if (prepared.size() > 16) throw new IOException("At most 15 custom videos can be loaded at once.");
        long total = 0;
        Set<Path> counted = new HashSet<>();
        for (var asset : prepared.values()) if (asset.pack() != null && counted.add(asset.pack())) {
            total += Files.size(asset.pack());
            if (total > 89L * 1024 * 1024) throw new IOException("Loaded video resources exceed the combined 90 MiB limit. Unload unused videos or lower fps.");
        }
        var clips = new LinkedHashMap<String, Clip>();
        var entries = new TreeMap<String, byte[]>();
        try (var input = VideoCatalog.class.getResourceAsStream("/cinema/crown-cinema.zip")) { read(input, entries); }
        StringBuilder sounds = new StringBuilder(new String(entries.get("assets/crowncinema/sounds.json"), StandardCharsets.UTF_8));
        sounds.setLength(sounds.lastIndexOf("}"));
        Set<String> merged = new HashSet<>();
        for (var entry : new TreeMap<>(prepared).entrySet()) {
            var asset = entry.getValue();
            if (asset.pack() == null) {
                clips.put(entry.getKey(), new Clip(asset, Key.key("crowncinema:movie"), CinemaAudio.SOUND)); continue;
            }
            String id = "clip_" + sha256(asset.pack()).substring(0, 24);
            clips.put(entry.getKey(), new Clip(asset, Key.key("crowncinema:" + id), Key.key("crowncinema:video_" + id)));
            if (!merged.add(id)) continue;
            Map<String, byte[]> source = new TreeMap<>();
            try (var input = Files.newInputStream(asset.pack())) { read(input, source); }
            for (var file : source.entrySet()) {
                String path = file.getKey(); byte[] data = file.getValue();
                if (path.equals("assets/crowncinema/font/movie.json")) {
                    path = "assets/crowncinema/font/" + id + ".json";
                    data = new String(data, StandardCharsets.UTF_8).replace("crowncinema:font/", "crowncinema:" + id + "/font/").getBytes(StandardCharsets.UTF_8);
                } else if (path.startsWith("assets/crowncinema/textures/")) {
                    path = "assets/crowncinema/textures/" + id + "/" + path.substring("assets/crowncinema/textures/".length());
                } else if (path.equals("assets/crowncinema/sounds/video.ogg")) {
                    path = "assets/crowncinema/sounds/" + id + "/video.ogg";
                } else continue;
                entries.put(path, data);
            }
            sounds.append(",\"video_").append(id).append("\":{\"sounds\":[{\"name\":\"crowncinema:").append(id).append("/video\",\"stream\":false}]}");
        }
        sounds.append('}');
        entries.put("assets/crowncinema/sounds.json", sounds.toString().getBytes(StandardCharsets.UTF_8));
        Path generated = directory.resolve("generated"); Files.createDirectories(generated);
        Path temporary = Files.createTempFile(generated, "catalog-", ".zip");
        try {
            // Preserve the exact bundled demo when no custom resources are needed.
            if (merged.isEmpty()) {
                try (var in = VideoCatalog.class.getResourceAsStream("/cinema/crown-cinema.zip")) { Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING); }
            } else try (var zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (var entry : entries.entrySet()) {
                    var item = new ZipEntry(entry.getKey()); item.setTime(0); zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
                }
            }
            if (Files.size(temporary) > 90L * 1024 * 1024) throw new IOException("Combined video pack exceeds 90 MiB. Use fewer/shorter videos or lower fps.");
            var payload = PackService.Payload.load(temporary);
            Path published = generated.resolve("catalog-" + payload.hash() + ".zip");
            if (!Files.exists(published)) Files.move(temporary, published);
            else if (!PackService.Payload.load(published).hash().equals(payload.hash())) Files.move(temporary, published, StandardCopyOption.REPLACE_EXISTING);
            return new VideoCatalog(Map.copyOf(clips), selected, Map.copyOf(servers), new PackService.Payload(published, payload.hash()));
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void read(InputStream input, Map<String, byte[]> target) throws IOException {
        try (var zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) if (!entry.isDirectory()) target.put(entry.getName(), zip.readAllBytes());
        }
    }
    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
