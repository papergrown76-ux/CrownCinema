package de.crown.cinema;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Single background importer; failures leave the active catalog and selection intact. */
final class VideoManager implements AutoCloseable {
    private final Path directory;
    private final VideoLibrary library;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Consumer<Runnable> whenIdle;
    private final Consumer<VideoCatalog> installed;
    private volatile VideoCatalog state;
    private volatile boolean closed;

    VideoManager(Path directory, Consumer<Runnable> whenIdle, Consumer<VideoCatalog> installed) throws IOException {
        this.directory = directory; this.library = new VideoLibrary(directory); this.whenIdle = whenIdle; this.installed = installed;
        Properties settings = library.settings();
        var assets = new HashMap<String, VideoLibrary.Prepared>(); assets.put("builtin", library.prepare("builtin", ignored -> { }));
        String selected = settings.getProperty("file", "auto");
        if (selected.equals("auto")) { var files = library.list(); selected = files.size() == 1 ? files.getFirst() : "builtin"; }
        Map<String, String> servers = new HashMap<>();
        for (String key : settings.stringPropertyNames()) if (key.startsWith("server.")) servers.put(key.substring(7).toLowerCase(Locale.ROOT), settings.getProperty(key));
        Set<String> names = new TreeSet<>(servers.values()); names.add(selected);
        for (String key : settings.stringPropertyNames()) if (key.startsWith("import.")) names.add(settings.getProperty(key));
        for (String name : names) {
            try { assets.put(name, library.prepare(name, ignored -> { })); }
            catch (IOException | IllegalArgumentException e) { System.getLogger(VideoManager.class.getName()).log(System.Logger.Level.WARNING, "Video unavailable at startup: " + name + ": " + e.getMessage()); }
        }
        if (!assets.containsKey(selected)) selected = "builtin";
        servers.values().removeIf(name -> !assets.containsKey(name));
        state = VideoCatalog.build(directory, assets, selected, servers);
    }
    VideoCatalog snapshot() { return state; }
    List<String> list() throws IOException { return library.list(); }
    boolean busy() { return busy.get(); }

    boolean update(String action, String name, String server, Consumer<String> progress) {
        if (closed || !busy.compareAndSet(false, true)) { progress.accept("Another video import is still running. Please wait."); return false; }
        progress.accept("Video update queued. Gameplay continues during import.");
        worker.execute(() -> {
            try {
                Properties settings = library.settings();
                var current = state;
                var assets = new HashMap<String, VideoLibrary.Prepared>(); current.clips().forEach((key, clip) -> assets.put(key, clip.prepared()));
                var servers = new HashMap<>(current.servers()); String selected = current.selected();
                if (action.equals("unload")) {
                    if (name.equals("builtin") || name.equals(selected) || servers.containsValue(name)) throw new IOException("Select a different default/server video before unloading this file.");
                    assets.remove(name);
                    settings.stringPropertyNames().stream().filter(k -> k.startsWith("import.") && settings.getProperty(k).equals(name)).toList().forEach(settings::remove);
                } else if (action.equals("reload")) {
                    for (String file : new TreeSet<>(assets.keySet())) assets.put(file, library.prepare(file, progress));
                } else if (action.equals("server") && name.equals("default")) {
                    servers.remove(server); settings.remove("server." + server);
                } else {
                    assets.put(name, library.prepare(name, progress));
                    settings.setProperty("import." + Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)), name);
                    if (action.equals("use")) selected = name;
                    if (action.equals("server")) { servers.put(server, name); settings.setProperty("server." + server, name); }
                }
                settings.setProperty("file", selected);
                progress.accept("Combining video resources. Active animations finish before the update is applied.");
                VideoCatalog prepared = VideoCatalog.build(directory, assets, selected, servers);
                whenIdle.accept(() -> {
                    try {
                        if (closed) return;
                        save(settings); installed.accept(prepared); state = prepared;
                        progress.accept("Video update complete and active. Default: " + prepared.defaultClip().name() + ". Players receive the updated pack when needed.");
                    } catch (Exception e) { progress.accept("Video update failed; the previous video remains active: " + e.getMessage()); }
                    finally { busy.set(false); }
                });
            } catch (Exception e) {
                busy.set(false); progress.accept("Video import failed; the previous video remains active: " + e.getMessage());
            }
        });
        return true;
    }

    private void save(Properties settings) throws IOException {
        Path temporary = Files.createTempFile(directory, "video-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { settings.store(writer, "Live Crown Cinema video settings"); }
            try { Files.move(temporary, directory.resolve("video.properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, directory.resolve("video.properties"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() { closed = true; worker.shutdownNow(); }
}
