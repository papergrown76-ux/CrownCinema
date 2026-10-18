# Crown Cinema

Video cinematics and server announcements for Minecraft Java networks running Velocity. Use **`/crowncinema`** to announce a server, preview a video, manage server modes and inspect resource packs. The old `/crown` command is no longer registered.

[Download the compiled plugin](downloads/crown-cinema-0.7.0.jar) · [Installation ZIP](downloads/crown-cinema-installation-0.7.0.zip) · [Optional Paper plugin](downloads/crown-cinema-backend-0.6.1.jar) · [MIT license](LICENSE)

You do not need to compile the source. Click a download above, then **Download raw file**, or use the assets on the [latest release](https://github.com/papergrown76-ux/CrownCinema/releases/latest). Source archives are provided by GitHub alongside releases.

The bundled six-second demo is independently drawn and silent. It contains no player skins, Minecraft textures or the previous personal forge video. Add your own video to play its images and audio.

## Requirements

- Velocity with **Java 25**. The tested build is Velocity **4.2.1-SNAPSHOT build 41**.
- [LimboAPI 1.1.27-SNAPSHOT, build bfef579](https://modrinth.com/plugin/limboapi/version/zOTyppCs) on Velocity, even when using backend bridge mode.
- **FFmpeg is included** for Linux x86-64 and ARM64, including Pelican containers. Other platforms need an external FFmpeg executable. No Python or Blender is needed on your server.
- Minecraft Java **1.20.3 or newer**, within the prepared protocol range supported by the installed LimboAPI. This build prepares protocols **765–777**. Older clients get an upgrade message and can continue playing.
- An inbound **TCP port**, default **8124**, reachable by players for the resource-pack download. On Pelican, add the allocation and publish the port in addition to opening the firewall.

## Install

1. Put `crown-cinema.jar` and the matching LimboAPI JAR in **Velocity's** `plugins/` directory.
2. Start Velocity once. Configuration is created under `plugins/crowncinema/`.
3. Keep `pack.url=` empty and `pack.http.enabled=true` for automatic hosting. The plugin serves the generated pack itself; no MP4 or ZIP upload to another website is required.
4. Open inbound TCP 8124, or change `pack.http.port`. If your Minecraft hostname uses SRV records, a TCP proxy or different HTTP routing, set `pack.http.public-host` to this proxy's directly reachable hostname/IP, without a scheme or port.
5. Give administrators `crowncinema.admin` and players `crowncinema.pack` in the **Velocity permission system**. For LuckPerms: `lpv group admin permission set crowncinema.admin true` and `lpv group default permission set crowncinema.pack true`.
6. Run `/crowncinema menu` (native dialogs on Java 1.21.6+) or `/crowncinema servers` to configure which registered Velocity servers may show animations.

On the first installation, the example configuration enables `lobby=proxy`. Unlisted servers are OFF. Replace the example with your actual safe lobby name.

## Import and switch videos without restarting

1. Upload an **MP4, WebM, MOV or MKV** to `plugins/crowncinema/videos/`.
2. Run `/crowncinema video use intro.mp4` to import it in the background and select it as the default. No Velocity restart is needed. Names containing spaces are supported.
3. Watch the progress messages: frame extraction, audio conversion, resource generation and publication. Normal gameplay continues during conversion.
4. Active animations finish before the new catalog is activated. Players receive an updated Crown pack when needed; playback requires the new pack hash to be applied. Failed imports keep the previous catalog and settings.

`/crowncinema video import intro.mp4` loads a video without changing the default. Selecting an already imported, unchanged video reuses the same combined pack and does not reload it. `/crowncinema video default` switches to the neutral demo immediately. `/crowncinema video reload` rechecks loaded input files and reimports changed content or fps settings. `/crowncinema video unload unused.mp4` removes unused resources; first remove any default/server assignments for that file. Input files are never deleted by these commands.

### Different videos for target servers

```text
/crowncinema video server survival survival-intro.mp4
/crowncinema video server skyblock skyblock-intro.mp4
/crowncinema announce survival Survival
/crowncinema announce skyblock Skyblock
```

Assignments choose the video for the **advertised target server**, regardless of which eligible backend the viewer is currently on. An unassigned target uses the global default. `/crowncinema video server survival default` removes the override; `builtin` explicitly selects the neutral demo. The chosen video still ends with the target's display name and JOIN NOW.

Loaded videos have separate fonts and sound events in one combined resource pack, so different targets can play concurrently. Up to **15 custom videos plus the demo**, with at most **90 MiB of combined pack resources**, can be loaded. Reduce fps, shorten videos or unload unused files when reaching the limit. Download URLs include their content hash; an old in-flight URL continues serving its original bytes during an update. The plugin only replaces its own pack.

Selections, imported files and target assignments are persisted in `plugins/crowncinema/video.properties`. A single video is automatically selected on first startup with `file=auto`; multiple files use the neutral demo until selected.

```properties
file=auto
# Bundled FFmpeg; alternatively an external executable path without arguments
ffmpeg=auto
# Integer from 1 to 20. Apply changes with /crowncinema video reload.
fps=12
# Server assignments are written automatically by commands, for example:
# server.survival=survival-intro.mp4
```

FFmpeg is extracted and SHA-256 verified under `plugins/crowncinema/tools/`. Legacy `ffmpeg=ffmpeg` settings also use the bundled binary. The server user needs permission to execute files there; a `noexec` mount needs an external executable path. The bundled build supports H.264, HEVC, VP8, VP9 and MPEG-4; AV1 is not included.

Per-video limits remain **the first 12 seconds**, **960×540**, letterboxed to 16:9, default **12 fps**, input up to **250 MiB**. Audio is converted to Ogg; silent inputs stay silent. Playback uses Minecraft's **Master** volume. Import errors appear in progress messages and the debug log. Live updates require built-in hosting (`pack.url` empty, HTTP enabled).

Minecraft renders converted bitmap-font frames rather than MP4 directly. Visual coverage depends on GUI scale and pack compatibility. This is a short cinematic system. No player skins or personal videos are included.

## Native vanilla settings GUI

Run **`/crowncinema menu`**, `/crowncinema gui` or `/crowncinema` with `crowncinema.admin`.

- Java **1.21.6+**: real vanilla dialog window with default/import video selection, registered server settings, mode buttons, target video assignment and pagination. No client mod or backend GUI plugin is needed.
- Older supported clients and the console: clickable chat server overview. All video commands remain available. `/crowncinema servers` always opens that chat overview.
- GUI actions run the same permission-checked commands. Escape/Close dismisses the window; it does not freeze gameplay. Bridge playback still requires the optional backend companion.

These are Minecraft's inline dialogs, documented in the [official 1.21.6 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6). Headless tests verify delivery, navigation and vanilla codec acceptance; they do not render the window in a Minecraft client.

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/crowncinema servers [page]` | `crowncinema.admin` | Clickable chat menu listing registered Velocity servers and configured modes |
| `/crowncinema server <name> off\|proxy\|bridge` | `crowncinema.admin` | Save and apply a server mode immediately |
| `/crowncinema announce <target> [display name]` | `crowncinema.admin` | Request playback for eligible players; JOIN NOW points to the target |
| `/crowncinema preview <target> [display name]` | `crowncinema.admin` | Preview for yourself in game |
| `/crowncinema stop <player\|all>` | `crowncinema.admin` | Stop playback and restore viewers |
| `/crowncinema status [player]` | `crowncinema.admin` | Active video, debug state, pack hash, download address and backend pack status; supports the proxy console |
| `/crowncinema video list` | `crowncinema.admin` | List local input files and active video |
| `/crowncinema menu` or `gui` | `crowncinema.admin` | Native vanilla settings dialog; chat fallback for older clients |
| `/crowncinema video import <filename>` | `crowncinema.admin` | Import resources in the background without changing the default |
| `/crowncinema video use <filename>` | `crowncinema.admin` | Import and switch the default live |
| `/crowncinema video server <target> <filename\|default\|builtin>` | `crowncinema.admin` | Assign a target video or remove its override |
| `/crowncinema video reload` | `crowncinema.admin` | Recheck imported files and fps settings |
| `/crowncinema video unload <filename>` | `crowncinema.admin` | Unload unused resources without deleting the input |
| `/crowncinema video default` | `crowncinema.admin` | Switch to the neutral demo live |
| `/crowncinema debug on\|off` | `crowncinema.admin` | Persistently enable/disable diagnostics |
| `/crowncinema pack on\|off\|ask` | `crowncinema.pack` | Change your saved pack preference |
| `/crowncinema join` | None | Join the server from the current announcement |

Use commands without `/` in the Velocity console. Ordinary players cannot import videos, announce, inspect other players or enable debugging.

## Server modes and combat

The mode applies to the **viewer’s current backend**, not the advertised target. Settings are persisted immediately in `config.properties`.

- **OFF:** No automatic pack offer and no cinematic on that backend. This is the default for unlisted servers.
- **PROXY:** Temporary Limbo cinema. The player leaves and rejoins the backend. Use this only on safe lobbies; combat-logout plugins see a real disconnect/reconnect.
- **BRIDGE:** Install the optional [backend plugin](backend-bridge/README.md) on that Paper/Spigot server. The connection stays open. The addon uses normal Bukkit APIs to move the viewer into a black cinema room, enable protection and restore position/game mode/flight/speeds afterward. A missing or rejecting bridge skips the cinematic without switching to Limbo.

The addon skips players who dealt or received PvP damage within the configured cooldown (default 15 seconds). This does not replace a PvP plugin's combat state. Server operators can integrate its cancellable `CinemaPrepareEvent`. Anticheat is not bypassed; test the actual PvP/anticheat combination before enabling bridge mode on a gameplay server.

## Resource packs and Nexo-style pack managers

Backend pack requests and their status responses are left to Velocity. Crown waits for outstanding backend requests before its own offer. A backend **clear all packs** request reaches the client unchanged; Crown then offers only its own pack again on enabled servers after the backend transaction finishes. Other proxy pack plugins must handle their own restoration.

Acceptance/decline is saved by player UUID. To avoid Minecraft repeatedly prompting, players must also set **Server Resource Packs: Enabled** for this entry in their own server list. A server plugin cannot change that client setting. `/crowncinema pack off` removes only the Crown pack and suppresses further Crown offers.

All Crown resources use the `crowncinema` namespace. Actual Nexo packs, custom shaders and texture rendering still require a test with the server's real pack; a successful protocol test does not prove that textures render correctly.

## Debugging

1. Enable `/crowncinema debug on` **before** reconnecting or reproducing a problem.
2. Reproduce it, then run `/crowncinema status` or `crowncinema status <player>` in the proxy console.
3. Inspect Velocity's console and `plugins/crowncinema/debug.log`.
4. Disable `/crowncinema debug off` when finished.

Debug mode survives restarts. The file rotates at approximately **2 MiB**, retaining one previous file as `debug.log.1`. Events include video readiness/import failures, backend pack offers/removals, pack UUID/status, playback requests and bridge actions/tokens. Pack URLs, IP addresses, raw videos and chat contents are not recorded by the debug event logger. Logs include player names, file names and session IDs; review logs before sharing. FFmpeg/startup error logs may contain local paths.

Backend diagnostics are separate: set `debug: true` in `plugins/CrownCinemaBackend/config.yml` and restart that backend. Its console then includes incoming bridge actions and recovery state. Keep `recovery.yml` while any return is pending.

Common checks:

- **FAILED_DOWNLOAD:** Open the displayed download URL on a player's PC; it must download a ZIP. Check Pelican allocation, inbound TCP, bind address and DNS.
- **FAILED_RELOAD / missing Nexo textures:** Keep the pack-status trace and the Minecraft client `latest.log` lines mentioning resource reloads/shaders. This cannot be diagnosed from a successful HTTP download alone.
- **No video after upload:** Run `/crowncinema video list` and check import progress/errors. Use `/crowncinema video use <file>` or `reload`; a restart is not required.
- **Black cinema room:** `/crownbackend leave`; backend admins can run `crownbackend status <player>` and `crownbackend recover <player>`. A blocked return retains protection/recovery and retries every second. Already deleted historical recovery data cannot be reconstructed.

## Build and validation

The neutral demo is included in source resources, so a standard build needs no personal assets, Blender or rendering step. The wrapper bootstraps checksum-verified Java 25/Maven tools on Linux x86_64. The first root build compiles the embedded FFmpeg binaries using checksum-pinned sources and Zig; it needs Python 3.12+, GNU make, pkg-config and a C build toolchain, but no installed FFmpeg. See [the native build and license notes](third-party/ffmpeg/README.md).

```sh
./mvnw -B -ntp verify
./mvnw -f backend-bridge/pom.xml -B -ntp verify
npm --prefix tools/smoke ci --cache build/npm-cache --no-audit --no-fund
NODE_PATH="$PWD/tools/smoke/node_modules" node tools/protocol-smoke.cjs
NODE_PATH="$PWD/tools/smoke/node_modules" node tools/real-bridge-smoke.cjs
python3 tools/validate-dialog.py
python3 tools/package-release.py
```

Tests start loopback-only offline development servers. Never copy their configuration to production. Protocol tests need free ports 25565, 25566, 25567, 25569 and 28124. The real Paper test also needs 25576. Run the two integration tests sequentially. The Paper test downloads pinned, checksum-verified Paper 26.2 build 134 and ViaVersion/ViaBackwards 5.12.1. It accepts the EULA for that isolated development server.

Java tests exercise actual FFmpeg import with/without audio, cache validation, selection guards and debug rotation, plus command/pack/session/backend behavior. Integration tests run real Velocity/LimboAPI and a real Paper backend with protocol clients. **Pack application is simulated**; these tests do not render a game UI or perform a native 26.2/26.3 client login. To redraw the neutral demo, install FFmpeg and Pillow and run `python3 tools/generate-demo.py`; this is optional and not needed for normal builds. The official client-codec checker is optional: `python3 tools/validate-pack.py [pack.zip]`.

GitHub Actions builds/tests source changes and publishes compiled JARs, the installation ZIP and matching FFmpeg source ZIP alongside a published release. Checksums are verified before uploading and kept in the repository. The repository's `downloads/` directory also provides ready-made files when Actions is unavailable.

## Upgrading and licensing

For 0.7.0, replace the proxy JAR once and restart Velocity; subsequent video updates need no restart. Backend 0.6.0/0.6.1 remains compatible. From versions before 0.6.0, replace **both** proxy and backend JARs, restart them, and use `/crowncinema` everywhere. Permissions remain `crowncinema.*`. Existing pack-download configuration, pack preferences and backend recovery data are retained. Remove duplicate old JARs from plugin directories. Customized `message.upgrade` text is retained; set it to English yourself if an old configuration contains German text.

Original source code and the neutral demo use the [MIT License](LICENSE). Dependencies and user videos retain their own licenses; see [NOTICE.md](NOTICE.md). Bundled FFmpeg has its own LGPL license; the full corresponding source archive and license notices accompany the compiled downloads.
