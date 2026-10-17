# Crown Cinema

Video cinematics and server announcements for Minecraft Java networks running Velocity. Use **`/crowncinema`** to announce a server, preview a video, manage server modes and inspect resource packs. The old `/crown` command is no longer registered.

[Download the compiled plugin](downloads/crown-cinema-0.6.1.jar) · [Installation ZIP](downloads/crown-cinema-installation-0.6.1.zip) · [Optional Paper plugin](downloads/crown-cinema-backend-0.6.1.jar) · [MIT license](LICENSE)

You do not need to compile the source. Click a download above, then **Download raw file**, or use the assets on the [latest release](https://github.com/papergrown76-ux/CrownCinema/releases/latest). Source archives are provided by GitHub alongside releases. Private repositories require a signed-in account; this restriction disappears after the owner makes the repository public.

The bundled six-second demo is independently drawn and silent. It contains no player skins, Minecraft textures or the previous personal forge video. Add your own video to play its images and audio.

## Requirements

- Velocity with **Java 25**. The tested build is Velocity **4.2.1-SNAPSHOT build 41**.
- [LimboAPI 1.1.27-SNAPSHOT, build bfef579](https://modrinth.com/plugin/limboapi/version/zOTyppCs) on Velocity, even when using backend bridge mode.
- **FFmpeg inside the Velocity machine/container** for custom videos. A host installation is insufficient if the proxy runs in a separate Pelican/Docker container. No Python or Blender is needed on your server.
- Minecraft Java **1.20.3 or newer**, within the prepared protocol range supported by the installed LimboAPI. This build prepares protocols **765–777**. Older clients get an upgrade message and can continue playing.
- An inbound **TCP port**, default **8124**, reachable by players for the resource-pack download. On Pelican, add the allocation and publish the port in addition to opening the firewall.

## Install

1. Put `crown-cinema.jar` and the matching LimboAPI JAR in **Velocity's** `plugins/` directory.
2. Start Velocity once. Configuration is created under `plugins/crowncinema/`.
3. Keep `pack.url=` empty and `pack.http.enabled=true` for automatic hosting. The plugin serves the generated pack itself; no MP4 or ZIP upload to another website is required.
4. Open inbound TCP 8124, or change `pack.http.port`. If your Minecraft hostname uses SRV records, a TCP proxy or different HTTP routing, set `pack.http.public-host` to this proxy's directly reachable hostname/IP, without a scheme or port.
5. Give administrators `crowncinema.admin` and players `crowncinema.pack` in the **Velocity permission system**. For LuckPerms: `lpv group admin permission set crowncinema.admin true` and `lpv group default permission set crowncinema.pack true`.
6. Run `/crowncinema servers` to configure which registered Velocity servers may show animations.

On the first installation, the example configuration enables `lobby=proxy`. Unlisted servers are OFF. Replace the example with your actual safe lobby name.

## Import your own video

1. FFmpeg is **included in the proxy JAR for Linux x86-64 and ARM64**, including Pelican containers. No separate installation or startup download is needed.
2. Place an **MP4, WebM, MOV or MKV** in `plugins/crowncinema/videos/`.
3. With exactly one video and the default `file=auto`, restart Velocity and it is selected automatically.
4. With multiple videos, use `/crowncinema video list`, then `/crowncinema video use intro.mp4`, and **restart Velocity**.
5. The plugin converts the video, caches the generated pack and hosts it on the existing download port. Players receive the changed pack hash and can reuse unchanged cached content.

Use `/crowncinema video default` followed by a restart to return to the neutral demo. Replacing the selected input file also takes effect after a restart. Selection is global for the proxy; this version plays one selected video, followed by the requested server name and a clickable **JOIN NOW** chat entry.

`plugins/crowncinema/video.properties`:

```properties
# auto, builtin, or a filename from videos/
file=auto
# auto: bundled executable; alternatively an external executable path (no arguments)
ffmpeg=auto
# Integer from 1 to 20
fps=12
```

The first custom-video import extracts a checksum-verified static executable into `plugins/crowncinema/tools/`. Existing `ffmpeg=ffmpeg` settings also select the bundled version automatically. The server user must be allowed to execute files there; a `noexec` mount needs an executable external path configured instead. Other operating systems need an external FFmpeg with PNG and libvorbis support. The bundled build supports H.264, HEVC, VP8, VP9 and MPEG-4 video; AV1 is not included.

Limits: **first 12 seconds**, **960×540**, letterboxed to 16:9, default **12 fps**, input up to **250 MiB**, generated pack up to **90 MiB**. Use shorter clips or lower fps for large/high-detail videos. Original video audio is converted to Ogg; videos without audio stay silent. The sound uses Minecraft's **Master** volume slider. Import errors are logged and fall back to the neutral demo, so normal gameplay remains available. Check the active video with `/crowncinema status` after every import.

Minecraft does not play MP4 files directly. The plugin converts frames into a private bitmap font and plays the audio through the resource pack. Quality and screen coverage depend on the client's GUI scale and resource-pack compatibility. This is a short cinematic system, not a general movie player.

Converted data is cached under `plugins/crowncinema/generated/`, verified by content hashes and reused on restart. The selected input, its frame rate or an invalid cache triggers conversion again. Custom video imports require built-in hosting (`pack.url` empty, HTTP enabled).

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/crowncinema servers [page]` or `menu` | `crowncinema.admin` | Clickable chat menu listing registered Velocity servers and configured modes |
| `/crowncinema server <name> off\|proxy\|bridge` | `crowncinema.admin` | Save and apply a server mode immediately |
| `/crowncinema announce <target> [display name]` | `crowncinema.admin` | Request playback for eligible players; JOIN NOW points to the target |
| `/crowncinema preview <target> [display name]` | `crowncinema.admin` | Preview for yourself in game |
| `/crowncinema stop <player\|all>` | `crowncinema.admin` | Stop playback and restore viewers |
| `/crowncinema status [player]` | `crowncinema.admin` | Active video, debug state, pack hash, download address and backend pack status; supports the proxy console |
| `/crowncinema video list` | `crowncinema.admin` | List local input files and active video |
| `/crowncinema video use <filename>` | `crowncinema.admin` | Save a selection; restart to import and use it |
| `/crowncinema video default` | `crowncinema.admin` | Select the neutral demo; restart to apply |
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
- **No video after upload:** Check active selection, FFmpeg availability inside the container, input limits and import errors; restart after selecting a file.
- **Black cinema room:** `/crownbackend leave`; backend admins can run `crownbackend status <player>` and `crownbackend recover <player>`. A blocked return retains protection/recovery and retries every second. Already deleted historical recovery data cannot be reconstructed.

## Build and validation

The neutral demo is included in source resources, so a standard build needs no personal assets, Blender or rendering step. The wrapper bootstraps checksum-verified Java 25/Maven tools on Linux x86_64. The first root build compiles the embedded FFmpeg binaries using checksum-pinned sources and Zig; it needs Python 3.12+, GNU make, pkg-config and a C build toolchain, but no installed FFmpeg. See [the native build and license notes](third-party/ffmpeg/README.md).

```sh
./mvnw -B -ntp verify
./mvnw -f backend-bridge/pom.xml -B -ntp verify
npm --prefix tools/smoke ci --cache build/npm-cache --no-audit --no-fund
NODE_PATH="$PWD/tools/smoke/node_modules" node tools/protocol-smoke.cjs
NODE_PATH="$PWD/tools/smoke/node_modules" node tools/real-bridge-smoke.cjs
python3 tools/package-release.py
```

Tests start loopback-only offline development servers. Never copy their configuration to production. Protocol tests need free ports 25565, 25566, 25567, 25569 and 28124. The real Paper test also needs 25576. Run the two integration tests sequentially. The Paper test downloads pinned, checksum-verified Paper 26.2 build 134 and ViaVersion/ViaBackwards 5.12.1. It accepts the EULA for that isolated development server.

Java tests exercise actual FFmpeg import with/without audio, cache validation, selection guards and debug rotation, plus command/pack/session/backend behavior. Integration tests run real Velocity/LimboAPI and a real Paper backend with protocol clients. **Pack application is simulated**; these tests do not render a game UI or perform a native 26.2/26.3 client login. To redraw the neutral demo, install FFmpeg and Pillow and run `python3 tools/generate-demo.py`; this is optional and not needed for normal builds. The official client-codec checker is optional: `python3 tools/validate-pack.py [pack.zip]`.

GitHub Actions builds/tests source changes and publishes compiled JARs, the installation ZIP and matching FFmpeg source ZIP alongside a published release. Checksums are verified before uploading and kept in the repository. The repository's `downloads/` directory also provides ready-made files when Actions is unavailable.

## Upgrading and licensing

For the bundled FFmpeg update from 0.6.0, replace the proxy JAR and restart Velocity; backend 0.6.0 remains compatible. From versions before 0.6.0, replace **both** proxy and backend JARs, restart them, and use `/crowncinema` everywhere. Permissions remain `crowncinema.*`. Existing pack-download configuration, pack preferences and backend recovery data are retained. Remove duplicate old JARs from plugin directories. Customized `message.upgrade` text is retained; set it to English yourself if an old configuration contains German text.

Original source code and the neutral demo use the [MIT License](LICENSE). Dependencies and user videos retain their own licenses; see [NOTICE.md](NOTICE.md). Bundled FFmpeg has its own LGPL license; the full corresponding source archive and license notices accompany the compiled downloads.
