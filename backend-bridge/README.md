# Optional Crown Cinema Backend

Put `crown-cinema-backend.jar` in the selected Paper/Spigot backend's `plugins/` directory and restart it. Keep Crown Cinema and LimboAPI on Velocity. This addon uses the Bukkit 1.20.4 API and Java 17 bytecode; the server's own newer Java requirement still applies. The tested backend is Paper 26.2 build 134.

At the proxy, choose `/crowncinema server <registered-name> bridge` or use `/crowncinema servers`. Protect backends with normal Velocity forwarding/firewall settings so clients cannot connect directly.

The player stays on the same backend connection. During playback, normal Bukkit spectator/teleport APIs move the player to `crown_cinema`, disable movement/interactions/ordinary backend commands and prevent outgoing/incoming damage. Position, game mode, flight rights and speeds are saved before entry and restored afterward. Same-world corrections do not cancel playback. A real external teleport out of the cinema takes precedence.

`plugins/CrownCinemaBackend/config.yml`:

```yaml
combat-cooldown-seconds: 15
debug: false
world-name: crown_cinema
```

`debug: true` adds bridge action/token and recovery-state details to the backend console after a restart. Proxy debug mode is separate: `/crowncinema debug on`.

The damage cooldown is a simple fallback, not a universal combat-plugin integration. Integrate the actual PvP plugin by cancelling `de.crown.cinema.backend.CinemaPrepareEvent` when its combat state disallows a cinematic. Register that listener in your integration plugin with an appropriate dependency on `CrownCinemaBackend`. Other plugins may veto normal Bukkit changes. No anticheat is bypassed.

Sessions have a bounded timeout. The watchdog restores players if the proxy stops responding. A blocked/redirected return retains protection and `recovery.yml`, then retries every second. Do not remove recovery data while returns are pending. On restart/reconnect, stored recovery is retried. Previously deleted recovery positions cannot be reconstructed.

| Command | Permission |
| --- | --- |
| `/crownbackend leave` | Any player, for their own saved return |
| `/crownbackend status [player]` | `crowncinema.backend.admin`, default OP |
| `/crownbackend recover [player]` | `crowncinema.backend.admin`, default OP |

Backend console commands omit `/` and require a connected player name. Keep the plugin folder and recovery data when replacing an old JAR. Build from the repository root: `./mvnw -f backend-bridge/pom.xml -B -ntp verify`. See the main README for real-Paper integration tests and their rendering limitations.
