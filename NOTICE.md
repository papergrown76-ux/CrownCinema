# Licensing and third-party components

The original Crown Cinema source code and the neutral bundled demo are licensed under the MIT License in `LICENSE`.

Velocity (GPL-3.0), its public API (MIT), LimboAPI (GPL-3.0) and the Java dependencies retain their own licenses. Their binaries are not bundled in the plugin. The plugin uses a pinned Velocity internal sound encoder; redistributing a combined work requires considering the dependencies' license terms as well as this project's MIT license.

User-provided videos, sounds, skins and Minecraft assets are not licensed by this project. Only import content you have permission to distribute. The current bundled demo is independently drawn and silent; it contains no player skins, Minecraft textures or the previous personal forge video. Old Git history is separate from the cleaned current source tree.

The proxy JAR separately bundles static FFmpeg 9.0 executables under LGPL-2.1-or-later, with libogg/libvorbis (BSD-style) and zlib (zlib license). These executables retain their original licenses. See `third-party/ffmpeg/README.md`, the included license texts and the matching complete FFmpeg source ZIP provided with compiled releases. The optional backend JAR does not contain FFmpeg.
