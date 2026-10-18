# Ready-to-install downloads

- [Velocity plugin 0.7.0](crown-cinema-0.7.0.jar)
- [Optional Paper/Spigot backend plugin 0.6.1](crown-cinema-backend-0.6.1.jar)
- [Installation ZIP with both JARs, English instructions and licenses](crown-cinema-installation-0.7.0.zip)
- [Matching FFmpeg/library sources and build instructions](crown-cinema-ffmpeg-sources-9.0.zip)

Click a file, then **Download raw file**, or use the compiled assets on the latest release. GitHub also provides source archives. Private repositories require a signed-in account.

The plugin includes only a neutral, silent demo. Add your own MP4/WebM/MOV/MKV under `plugins/crowncinema/videos/`, select it live with `/crowncinema video use <filename>`. Use `/crowncinema video server <target> <filename>` for a server-specific video. `/crowncinema menu` opens native vanilla settings on Java 1.21.6+, with chat fallback for older clients. FFmpeg is included for Linux x86-64 and ARM64; no separate installation is needed. The plugin converts and hosts the generated resource pack automatically. See the [README](../README.md) for setup, ports, limits, commands, debug mode, permissions and combat behavior.

From pre-0.6 versions, replace both JARs and restart the affected servers. Preserve configuration, pack preferences and backend `recovery.yml`. Commands now start with `/crowncinema`; permission names are unchanged. Old personal-video download archives are intentionally removed from this directory.
