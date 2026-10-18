package de.crown.cinema;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VideoManagerTest {
    @TempDir Path directory;
    @Test void importsOffThreadDefersPublicationAndPersistsTargetServerSelection() throws Exception {
        var commits = new LinkedBlockingQueue<Runnable>();
        try (var manager = new VideoManager(directory, commits::add, ignored -> { })) {
            Path video = directory.resolve("videos/survival intro.mp4");
            try (var in = getClass().getResourceAsStream("/h264-aac.mp4")) { Files.copy(in, video); }
            List<String> progress = new CopyOnWriteArrayList<>(); String oldHash = manager.snapshot().pack().hash();
            assertTrue(manager.update("server", "survival intro.mp4", "survival", progress::add));
            Runnable commit = commits.poll(30, TimeUnit.SECONDS); assertNotNull(commit);
            assertTrue(manager.busy()); assertEquals(oldHash, manager.snapshot().pack().hash());
            assertFalse(manager.update("use", "builtin", "", progress::add));
            commit.run(); assertFalse(manager.busy());
            var state = manager.snapshot(); assertNotEquals(oldHash, state.pack().hash());
            assertEquals("survival intro.mp4", state.forServer("SURVIVAL").name());
            assertEquals("Neutral demo", state.forServer("other").name());
            assertNotEquals(state.defaultClip().font(), state.forServer("survival").font());
            try (var zip = new ZipFile(state.pack().file().toFile())) {
                String font = "assets/crowncinema/font/" + state.forServer("survival").font().value() + ".json";
                assertNotNull(zip.getEntry(font));
                String sounds = new String(zip.getInputStream(zip.getEntry("assets/crowncinema/sounds.json")).readAllBytes());
                assertTrue(sounds.contains(state.forServer("survival").sound().value()));
            }
            String catalogHash = state.pack().hash();
            manager.update("use", "survival intro.mp4", "", progress::add);
            commit = commits.poll(30, TimeUnit.SECONDS); assertNotNull(commit); commit.run();
            assertEquals(catalogHash, manager.snapshot().pack().hash(), "Changing selection must reuse the combined pack");
        }
        try (var restarted = new VideoManager(directory, Runnable::run, ignored -> { })) {
            assertEquals("survival intro.mp4", restarted.snapshot().defaultClip().prepared().name().replace(" (cached)", ""));
            assertEquals("survival intro.mp4", restarted.snapshot().servers().get("survival"));
        }
    }

    @Test void failedImportDoesNotChangePackOrPersistBrokenSelection() throws Exception {
        try (var manager = new VideoManager(directory, Runnable::run, ignored -> { })) {
            var original = manager.snapshot(); var done = new CountDownLatch(1);
            Files.writeString(directory.resolve("videos/broken.mp4"), "not a video");
            manager.update("use", "broken.mp4", "", text -> { if (text.startsWith("Video import failed")) done.countDown(); });
            assertTrue(done.await(30, TimeUnit.SECONDS)); assertSame(original, manager.snapshot()); assertFalse(manager.busy());
            assertFalse(Files.readString(directory.resolve("video.properties")).contains("file=broken"));
        }
    }
}
