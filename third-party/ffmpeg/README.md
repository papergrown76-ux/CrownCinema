# Bundled FFmpeg

The proxy JAR includes separate, fully static **FFmpeg 9.0** executables for Linux x86-64 and ARM64. They are executed as separate processes and are licensed under **LGPL-2.1-or-later**, not this project's MIT license. This build disables GPL and nonfree components. libogg 1.3.6 and libvorbis 1.3.7 use BSD-style licenses; zlib 1.3.2 uses the zlib license. The static musl C runtime and Zig compiler runtime retain their included MIT/BSD notices. Full license notices are in `licenses/` and in the proxy JAR under `META-INF/ffmpeg/`.

The exact corresponding sources, library sources, build script, checksum lock and licenses are provided as `crown-cinema-ffmpeg-sources-9.0.zip` alongside every compiled release. They are also reproducible from this repository:

```sh
python3 tools/build-bundled-ffmpeg.py
```

Build host: Linux x86-64, Python 3.12+, GNU make, pkg-config and a C build toolchain. The script downloads the checksum-pinned Zig 0.15.2 compiler and unchanged upstream source archives, then cross-compiles both musl targets. No system FFmpeg is used. Every downloaded input is SHA-256 checked. The FFmpeg source release signature and the signing key are preserved here; fingerprint: `FCF986EA15E6E293A5644F10B4322F04D67658D8`.

The manifest records executable hashes and lengths. The plugin verifies the unpacked executable before using it and does not download native programs at runtime. No root access is needed. Users may modify/rebuild FFmpeg and select their own executable with `ffmpeg=/absolute/path` in `video.properties`; do not supply shell arguments. The LGPL permits reverse engineering for debugging modifications to that component.

Enabled features cover local H.264/HEVC/VP8/VP9/MPEG-4 decoding, PNG output, scaling/frame-rate conversion, AAC and common audio decoding, and libvorbis Ogg encoding. AV1, networking, GPL codecs and external FFmpeg libraries are disabled. See the build script for the complete configuration. The bundled executable is a subset intended for this importer, not a replacement for a full multimedia installation.
