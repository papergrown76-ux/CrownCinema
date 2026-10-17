package de.crown.cinema;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** A separately licensed executable, extracted locally; never downloads native code. */
final class BundledFfmpeg {
    private BundledFfmpeg() { }

    static String resolve(Path directory, String configured) throws IOException {
        if (!Set.of("", "auto", "bundled", "ffmpeg").contains(configured)) return configured;
        return extract(directory, System.getProperty("os.name"), System.getProperty("os.arch")).toString();
    }

    static Path extract(Path directory, String os, String arch) throws IOException {
        String platform = platform(os, arch);
        Properties manifest = new Properties();
        try (var in = resource("manifest.properties")) { manifest.load(in); }
        String hash = manifest.getProperty(platform + ".sha256");
        long size = Long.parseLong(manifest.getProperty(platform + ".size"));
        if (hash == null || !hash.matches("[0-9a-f]{64}") || size < 1 || size > 100_000_000)
            throw new IOException("Invalid bundled FFmpeg manifest");
        Path tools = directory.toAbsolutePath().resolve("tools");
        Path parent = tools.resolve("ffmpeg-" + manifest.getProperty("version") + "-" + hash.substring(0, 16)).resolve(platform);
        // Refuse link traversal when publishing a native executable.
        for (Path path = parent; path.startsWith(tools); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("FFmpeg tools directory must not contain symbolic links: " + path);
        }
        Files.createDirectories(parent);
        Path binary = parent.resolve("ffmpeg");
        if (Files.isSymbolicLink(binary)) throw new IOException("Bundled FFmpeg executable must not be a symbolic link");
        if (!valid(binary, size, hash)) {
            Path temporary = Files.createTempFile(parent, "ffmpeg-", ".tmp");
            try {
                try (var in = new GZIPInputStream(resource(platform + ".gz")); var out = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; long total = 0; int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > size) throw new IOException("Bundled FFmpeg exceeds declared size");
                        out.write(buffer, 0, count);
                    }
                }
                if (!valid(temporary, size, hash)) throw new IOException("Bundled FFmpeg checksum mismatch");
                executable(temporary);
                try { Files.move(temporary, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, binary, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
        executable(binary);
        return binary;
    }

    static String platform(String os, String arch) throws IOException {
        if (os.equalsIgnoreCase("Linux")) {
            if (Set.of("amd64", "x86_64").contains(arch.toLowerCase(Locale.ROOT))) return "linux-x86_64";
            if (Set.of("aarch64", "arm64").contains(arch.toLowerCase(Locale.ROOT))) return "linux-aarch64";
        }
        throw new IOException("Bundled FFmpeg supports Linux x86-64 and ARM64. Set ffmpeg to an external executable path in video.properties on " + os + "/" + arch + ".");
    }

    private static InputStream resource(String name) throws IOException {
        var stream = BundledFfmpeg.class.getResourceAsStream("/native/ffmpeg/" + name);
        if (stream == null) throw new IOException("Bundled FFmpeg resource missing: " + name + ". Use the complete official plugin JAR.");
        return stream;
    }

    private static boolean valid(Path file, long size, String expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != size) return false;
        try (var in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            return expected.equals(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void executable(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException e) {
            if (!file.toFile().setExecutable(true, true)) throw new IOException("Could not make bundled FFmpeg executable: " + file, e);
        }
    }
}
