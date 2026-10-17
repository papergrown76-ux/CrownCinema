package de.crown.cinema;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VideoLibraryTest {
    @TempDir Path directory;
    private static void ffmpeg(String... args) throws Exception {
        var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "FFmpeg fixture generation timed out");
        assertEquals(0, process.exitValue(), "FFmpeg must be installed to test custom video imports");
    }
    @Test void emptyLibraryUsesOnlyTheNeutralBundledDemo() throws Exception {
        var library = new VideoLibrary(directory);
        assertNull(library.prepare().pack()); assertTrue(library.list().isEmpty());
        try (var in = getClass().getResourceAsStream("/cinema/crown-cinema.zip")) {
            assertNotNull(in); assertTrue(in.readAllBytes().length < 1024 * 1024, "The old personal video must not be bundled");
        }
    }
    @Test void importsRealMp4WithAudioAndReusesOnlyVerifiedCache() throws Exception {
        var library = new VideoLibrary(directory); Path source = directory.resolve("videos/my intro.mp4");
        ffmpeg("-f", "lavfi", "-i", "color=c=blue:s=320x180:r=4:d=1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", source.toString());
        var result = library.prepare();
        assertEquals(12, result.sequence().frames()); assertEquals(12, result.sequence().fps());
        try (var zip = new ZipFile(result.pack().toFile())) {
            assertTrue(new String(zip.getInputStream(zip.getEntry("assets/crowncinema/sounds.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).startsWith("{\"video\":"));
            var image = ImageIO.read(zip.getInputStream(zip.getEntry("assets/crowncinema/textures/font/video_0_row_0.png")));
            int rgb = image.getRGB(20,20); assertTrue((rgb & 255) > 200 && ((rgb >> 16) & 255) < 30);
            assertEquals(1920, image.getWidth()); assertEquals(2880, image.getHeight());
            Path audio = directory.resolve("imported.ogg"); Files.write(audio, zip.getInputStream(zip.getEntry("assets/crowncinema/sounds/video.ogg")).readAllBytes());
            var decode = new ProcessBuilder("ffmpeg", "-v", "error", "-i", audio.toString(), "-f", "s16le", "pipe:1").start();
            byte[] samples = decode.getInputStream().readAllBytes(); assertEquals(0, decode.waitFor());
            boolean audible = false;
            for (int i=0;i+1<samples.length;i+=2) if (Math.abs((short)((samples[i]&255)|(samples[i+1]<<8))) > 500) { audible=true;break; }
            assertTrue(audible, "The imported video audio must be retained");
        }
        Files.writeString(directory.resolve("video.properties"), "file=auto\nfps=12\nffmpeg=/missing-ffmpeg\n");
        assertTrue(library.prepare().name().contains("cached"));
        Files.writeString(result.pack(), "corrupt-cache");
        assertThrows(java.io.IOException.class, library::prepare, "A corrupt cache must not be served as valid video");
        library.select("builtin"); assertNull(library.prepare().pack());
    }
    @Test void silentVideoStaysSilentAndMultipleVideosRequireSelection() throws Exception {
        var library = new VideoLibrary(directory); Path source = directory.resolve("videos/silent.mp4");
        ffmpeg("-f", "lavfi", "-i", "color=c=red:s=320x180:r=4:d=1", "-an", "-c:v", "libx264", source.toString());
        Files.copy(source, directory.resolve("videos/other.mp4"));
        assertNull(library.prepare().pack());
        library.select("silent.mp4"); var result = library.prepare(); assertNotNull(result.pack());
        try (var zip = new ZipFile(result.pack().toFile())) {
            assertNotNull(zip.getEntry("assets/crowncinema/sounds/video.ogg"));
        }
        assertEquals(List.of("other.mp4", "silent.mp4"), library.list());
    }
    @Test void selectionCannotEscapeVideosOrFollowLinks() throws Exception {
        var library = new VideoLibrary(directory);
        assertThrows(java.io.IOException.class, () -> library.select("../private.mp4"));
        Path target = directory.resolve("private.mp4"); Files.writeString(target,"not a video");
        Files.createSymbolicLink(directory.resolve("videos/link.mp4"),target);
        assertThrows(java.io.IOException.class, () -> library.select("link.mp4")); assertTrue(library.list().isEmpty());
    }
}
