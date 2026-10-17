# Ready-to-install downloads

- [Velocity plugin 0.6.0](crown-cinema-0.6.0.jar)
- [Optional Paper/Spigot backend plugin 0.6.0](crown-cinema-backend-0.6.0.jar)
- [Installation ZIP with both JARs, English instructions and license](crown-cinema-installation-0.6.0.zip)

Click a file, then **Download raw file**, or use the compiled assets on the latest release. GitHub also provides source archives. Private repositories require a signed-in account.

The plugin includes only a neutral, silent demo. Add your own MP4/WebM/MOV/MKV under `plugins/crowncinema/videos/`, select it with `/crowncinema video use <filename>` if needed, and restart Velocity. FFmpeg must be installed inside the Velocity environment. The plugin converts and hosts the generated resource pack automatically. See the [README](../README.md) for setup, ports, limits, commands, debug mode, permissions and combat behavior.

From pre-0.6 versions, replace both JARs and restart the affected servers. Preserve configuration, pack preferences and backend `recovery.yml`. Commands now start with `/crowncinema`; permission names are unchanged. Old personal-video download archives are intentionally removed from this directory.
