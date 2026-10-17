package de.crown.cinema;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BundledFfmpegTest {
    @TempDir Path directory;

    @Test void extractsRunsAndRepairsWithoutAnySystemFfmpeg() throws Exception {
        Path binary = Path.of(BundledFfmpeg.resolve(directory, "auto"));
        assertTrue(binary.startsWith(directory)); assertTrue(Files.isExecutable(binary));
        var process = new ProcessBuilder(binary.toString(), "-version").redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        assertTrue(new String(process.getInputStream().readAllBytes()).contains("ffmpeg version 9.0"));
        byte[] original = Files.readAllBytes(binary);
        assertEquals(binary.toString(), BundledFfmpeg.resolve(directory, "ffmpeg"));
        Files.writeString(binary, "broken extraction");
        assertEquals(binary.toString(), BundledFfmpeg.resolve(directory, "auto"));
        assertArrayEquals(original, Files.readAllBytes(binary));
        Files.setPosixFilePermissions(binary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        BundledFfmpeg.resolve(directory, "auto"); assertTrue(Files.isExecutable(binary));
    }

    @Test void neverOverwritesAnExecutableLink() throws Exception {
        Path binary = Path.of(BundledFfmpeg.resolve(directory, "auto"));
        Path target = directory.resolve("other"); Files.writeString(target, "keep me");
        Files.delete(binary); Files.createSymbolicLink(binary, target);
        assertThrows(java.io.IOException.class, () -> BundledFfmpeg.resolve(directory, "auto"));
        assertEquals("keep me", Files.readString(target));
    }

    @Test void selectsSupportedArchitecturesAndRetainsExplicitOverride() throws Exception {
        assertEquals("linux-x86_64", BundledFfmpeg.platform("Linux", "amd64"));
        assertEquals("linux-aarch64", BundledFfmpeg.platform("Linux", "arm64"));
        assertThrows(java.io.IOException.class, () -> BundledFfmpeg.extract(directory, "Windows", "amd64"));
        assertEquals("/custom/ffmpeg", BundledFfmpeg.resolve(directory, "/custom/ffmpeg"));
        Files.createSymbolicLink(directory.resolve("tools"), directory.resolve("missing"));
        assertThrows(java.io.IOException.class, () -> BundledFfmpeg.resolve(directory, "auto"));
    }
}
