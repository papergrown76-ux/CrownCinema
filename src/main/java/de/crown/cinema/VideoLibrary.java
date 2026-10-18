package de.crown.cinema;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Local video -> cached title-font pack. No shell, Python or Blender on the proxy. */
final class VideoLibrary {
    record Prepared(Sequence sequence, Path pack, String name) { }
    private static final Set<String> EXTENSIONS = Set.of("mp4", "webm", "mov", "mkv");
    private static final int FIRST = 0xe100, TRACKING = 0xe000, RESET = 0xe001;
    private static final long MAX_INPUT = 250L * 1024 * 1024, MAX_PACK = 90L * 1024 * 1024;
    private final Path directory, videos, settings;

    VideoLibrary(Path directory) throws IOException {
        this.directory = directory;
        videos = directory.resolve("videos"); settings = directory.resolve("video.properties");
        Files.createDirectories(videos);
        if (!Files.exists(settings)) Files.writeString(settings,
                "# auto: use a single video in videos/, otherwise the bundled demo.\n"
                + "# builtin: bundled demo; or select a filename with /crowncinema video use <file>.\n"
                + "file=auto\nffmpeg=auto\nfps=12\n", StandardCharsets.UTF_8);
        Path readme = videos.resolve("README.txt");
        if (!Files.exists(readme)) Files.writeString(readme,
                "Drop an MP4/WebM/MOV/MKV here. FFmpeg is included for Linux x86-64 and ARM64; no separate installation needed.\n"
                + "One file is selected automatically on startup. Multiple files: /crowncinema video list,\n"
                + "/crowncinema video use <filename> imports and switches live. /crowncinema video default restores the demo.\n"
                + "The first 12 seconds are imported at 960x540, 12 fps (configurable 1-20 fps).\n"
                + "Input limit: 250 MiB; generated resource pack limit: 90 MiB. Silent videos stay silent.\n"
                + "Keep pack.url empty and pack.http.enabled=true for automatic hosting.\n", StandardCharsets.UTF_8);
    }

    List<String> list() throws IOException {
        try (var paths = Files.list(videos)) {
            return paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).filter(VideoLibrary::supported).sorted().toList();
        }
    }

    private static boolean supported(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private Path input(String name) throws IOException {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)
                || !supported(name)) throw new IOException("Specify an MP4/WebM/MOV/MKV filename from videos/.");
        Path path = videos.resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_INPUT)
            throw new IOException("Video is missing, is a link or exceeds 250 MiB: " + name);
        return path.toAbsolutePath();
    }

    Properties settings() throws IOException {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(settings, StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }

    void select(String name) throws IOException {
        if (!name.equals("builtin")) input(name);
        Properties values = settings(); values.setProperty("file", name);
        Path temporary = Files.createTempFile(directory, "video-selection-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "Crown Cinema video selection"); }
            move(temporary, settings);
        } finally { Files.deleteIfExists(temporary); }
    }

    Prepared prepare() throws IOException {
        Properties values = settings();
        String selection = values.getProperty("file", "auto").trim();
        if (selection.equals("auto")) {
            var files = list();
            if (files.size() != 1) return new Prepared(Sequence.load(), null,
                    files.isEmpty() ? "Neutral demo" : "Neutral demo (multiple videos: /crowncinema video use <file>)");
            selection = files.getFirst();
        }
        if (selection.equals("builtin")) return new Prepared(Sequence.load(), null, "Neutral demo");
        return prepare(selection, ignored -> { });
    }

    Prepared prepare(String selection, java.util.function.Consumer<String> progress) throws IOException {
        if (selection.equals("builtin")) return new Prepared(Sequence.load(), null, "Neutral demo");
        Properties values = settings();
        Path source = input(selection);
        int fps;
        try { fps = Integer.parseInt(values.getProperty("fps", "12").trim()); }
        catch (NumberFormatException e) { throw new IOException("video.properties: fps must be between 1 and 20.", e); }
        if (fps < 1 || fps > 20) throw new IOException("video.properties: fps must be between 1 and 20.");
        String key = hash(source) + "-v1-" + fps;
        Path cache = directory.resolve("generated").resolve(key);
        Prepared cached = cached(cache, selection);
        if (cached != null) return cached;
        Files.createDirectories(cache.getParent());
        Path work = Files.createTempDirectory(cache.getParent(), "import-");
        try {
            String binary = BundledFfmpeg.resolve(directory, values.getProperty("ffmpeg", "auto").trim());
            progress.accept("Extracting video frames: " + selection);
            int maxFrames = 12 * fps;
            run(binary, work, "frames.log", List.of("-protocol_whitelist", "file,pipe", "-i", source.toString(),
                    "-map", "0:v:0", "-an", "-sn", "-dn", "-t", "12", "-vf",
                    "fps=" + fps + ",scale=960:540:force_original_aspect_ratio=decrease,pad=960:540:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1",
                    "-frames:v", Integer.toString(maxFrames), work.resolve("frame_%04d.png").toString()), false);
            int frames = 0;
            while (frames < maxFrames && Files.exists(work.resolve(String.format(Locale.ROOT, "frame_%04d.png", frames + 1)))) frames++;
            Sequence sequence = new Sequence(frames, fps, FIRST, 4, 3, TRACKING, RESET);
            String duration = String.format(Locale.ROOT, "%.6f", frames / (double) fps);
            progress.accept("Converting audio: " + selection);
            boolean audio = run(binary, work, "audio.log", List.of("-protocol_whitelist", "file,pipe", "-i", source.toString(),
                    "-map", "0:a:0", "-vn", "-sn", "-dn", "-t", duration, "-af", "apad", "-ar", "48000", "-ac", "2",
                    "-c:a", "libvorbis", "-q:a", "5", work.resolve("video.ogg").toString()), true);
            if (!audio) {
                String log = Files.readString(work.resolve("audio.log"));
                if (!log.contains("matches no streams")) throw new IOException("FFmpeg audio import failed: " + tail(log));
                run(binary, work, "silence.log", List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", duration,
                        "-c:a", "libvorbis", work.resolve("video.ogg").toString()), false);
            }
            progress.accept("Building video assets: " + selection);
            buildPack(work, frames);
            Properties metadata = new Properties();
            metadata.setProperty("frames", Integer.toString(frames)); metadata.setProperty("fps", Integer.toString(fps));
            metadata.setProperty("first-glyph", Integer.toString(FIRST)); metadata.setProperty("columns", "4"); metadata.setProperty("rows", "3");
            metadata.setProperty("tracking-glyph", Integer.toString(TRACKING)); metadata.setProperty("row-reset-glyph", Integer.toString(RESET));
            metadata.setProperty("pack-sha256", hash(work.resolve("pack.zip")));
            try (var out = Files.newOutputStream(work.resolve("sequence.properties"))) { metadata.store(out, "Generated from local video"); }
            Files.createDirectories(cache);
            // Publish metadata last; an interrupted conversion cannot become a valid cache hit.
            move(work.resolve("pack.zip"), cache.resolve("pack.zip"));
            move(work.resolve("sequence.properties"), cache.resolve("sequence.properties"));
            return new Prepared(sequence, cache.resolve("pack.zip"), selection);
        } finally { deleteTree(work); }
    }

    private Prepared cached(Path cache, String name) {
        try {
            Properties p = new Properties();
            try (var in = Files.newInputStream(cache.resolve("sequence.properties"))) { p.load(in); }
            if (!hash(cache.resolve("pack.zip")).equals(p.getProperty("pack-sha256"))) return null;
            try (var in = Files.newInputStream(cache.resolve("sequence.properties"))) {
                return new Prepared(Sequence.load(in), cache.resolve("pack.zip"), name + " (cached)");
            }
        } catch (IOException | IllegalArgumentException e) { return null; }
    }

    private static boolean run(String binary, Path work, String logName, List<String> args, boolean allowFailure) throws IOException {
        var command = new ArrayList<>(List.of(binary, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-threads", "2", "-filter_threads", "2"));
        command.addAll(args);
        Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(work.resolve(logName).toFile()).start(); }
        catch (IOException e) { throw new IOException("Could not start FFmpeg. Check execution permissions (noexec container mounts) or set an external executable path in video.properties.", e); }
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("FFmpeg import timed out after 120 seconds."); }
            if (process.exitValue() == 0) return true;
            if (allowFailure) return false;
            throw new IOException("FFmpeg import failed: " + tail(Files.readString(work.resolve(logName))));
        } catch (InterruptedException e) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("Video import interrupted", e);
        }
    }

    private static String tail(String log) { return log.substring(Math.max(0, log.length() - 1200)); }
    private static String hash(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }

    private static void buildPack(Path work, int frames) throws IOException {
        Path output = work.resolve("pack.zip");
        var providers = new ArrayList<String>();
        providers.add("{\"type\":\"space\",\"advances\":{\"\\ue000\":-1,\"\\ue001\":-80}}");
        try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            add(zip, "pack.mcmeta", ("{\"pack\":{\"pack_format\":22,\"supported_formats\":{\"min_inclusive\":22,\"max_inclusive\":97},"
                    + "\"min_format\":22,\"max_format\":[97,1],\"description\":\"Crown Cinema | Custom video\"}}").getBytes(StandardCharsets.UTF_8));
            for (int start = 0; start < frames; start += 24) {
                var images = new ArrayList<BufferedImage>();
                for (int f = start; f < Math.min(frames, start + 24); f++) {
                    BufferedImage image = ImageIO.read(work.resolve(String.format(Locale.ROOT, "frame_%04d.png", f + 1)).toFile());
                    if (image == null || image.getWidth() != 960 || image.getHeight() != 540) throw new IOException("Invalid FFmpeg frame");
                    images.add(image);
                }
                for (int row = 0; row < 3; row++) {
                    BufferedImage atlas = new BufferedImage(1920, 2880, BufferedImage.TYPE_INT_ARGB);
                    var graphics = atlas.createGraphics();
                    var chars = new StringBuilder("[");
                    for (int y = 0; y < 12; y++) {
                        if (y > 0) chars.append(','); chars.append('"');
                        for (int x = 0; x < 8; x++) {
                            int cell = y * 8 + x, frame = cell / 4, column = cell % 4;
                            if (start + frame >= frames) { chars.append("\\u0000"); continue; }
                            graphics.drawImage(images.get(frame), x * 240, y * 240, x * 240 + 240, y * 240 + 180,
                                    column * 240, row * 180, column * 240 + 240, row * 180 + 180, null);
                            chars.append(String.format(Locale.ROOT, "\\u%04x", FIRST + (start + frame) * 12 + row * 4 + column));
                        }
                        chars.append('"');
                    }
                    chars.append(']'); graphics.dispose();
                    String texture = "font/video_" + start / 24 + "_row_" + row + ".png";
                    var bytes = new ByteArrayOutputStream(); ImageIO.write(atlas, "PNG", bytes);
                    add(zip, "assets/crowncinema/textures/" + texture, bytes.toByteArray());
                    providers.add("{\"type\":\"bitmap\",\"file\":\"crowncinema:" + texture + "\",\"height\":20,\"ascent\":"
                            + (18 - row * 15) + ",\"chars\":" + chars + "}");
                }
            }
            add(zip, "assets/crowncinema/font/movie.json", ("{\"providers\":[" + String.join(",", providers) + "]}").getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/crowncinema/sounds.json", "{\"video\":{\"sounds\":[{\"name\":\"crowncinema:video\",\"stream\":false}]}}".getBytes(StandardCharsets.UTF_8));
            add(zip, "assets/crowncinema/sounds/video.ogg", Files.readAllBytes(work.resolve("video.ogg")));
        }
        if (Files.size(output) > MAX_PACK) throw new IOException("Video pack exceeds 90 MiB. Use a shorter video or lower fps.");
    }
    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
}
