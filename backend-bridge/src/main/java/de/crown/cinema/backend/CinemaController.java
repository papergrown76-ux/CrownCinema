package de.crown.cinema.backend;

import de.crown.cinema.bridge.BridgeMessage;
import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

final class CinemaController implements Listener {
    private final JavaPlugin plugin;
    private final java.util.function.LongSupplier clock;
    private record Active(UUID token, SavedPlayer saved, long expires) { }
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Set<UUID> internalChanges = new HashSet<>();
    private final Set<UUID> returning = new HashSet<>();
    private final Set<UUID> warned = new HashSet<>();
    private java.io.File recoveryFile;
    private YamlConfiguration recovery;
    private Location cinema;

    CinemaController(JavaPlugin plugin, Location cinema, java.util.function.LongSupplier clock) {
        this.plugin = plugin; this.cinema = cinema; this.clock = clock;
        recoveryFile = new java.io.File(plugin.getDataFolder(), "recovery.yml");
        recovery = YamlConfiguration.loadConfiguration(recoveryFile);
    }

    private Server getServer() { return plugin.getServer(); }
    private org.bukkit.configuration.file.FileConfiguration getConfig() { return plugin.getConfig(); }
    private java.util.logging.Logger getLogger() { return plugin.getLogger(); }

    void enable() {
        getServer().getPluginManager().registerEvents(this, plugin);
        getServer().getMessenger().registerOutgoingPluginChannel(plugin, BridgeMessage.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(plugin, BridgeMessage.CHANNEL, this::message);
        getServer().getScheduler().runTaskTimer(plugin, this::expire, 20, 20);
        getServer().getOnlinePlayers().forEach(this::recover);
    }

    void expire() {
        long now = clock.getAsLong();
        for (var entry : List.copyOf(active.entrySet())) if (entry.getValue().expires() < now || returning.contains(entry.getKey())) {
            var player = getServer().getPlayer(entry.getKey());
            if (player != null) finish(player, true, true);
        }
        combat.entrySet().removeIf(e -> now - e.getValue() > 300_000);
    }

    void disable() {
        for (UUID id : List.copyOf(active.keySet())) {
            var player = getServer().getPlayer(id);
            if (player != null) finish(player, true, true);
        }
    }

    void message(String channel, Player player, byte[] data) {
        try {
            var message = BridgeMessage.decode(data);
            if (getConfig().getBoolean("debug", false)) getLogger().info("[debug] bridge action=" + message.action() + " player=" + player.getName() + " token=" + message.token() + " state=" + status(player));
            if (message.action() == BridgeMessage.Action.START) start(player, message);
            else if (message.action() == BridgeMessage.Action.STOP) {
                var current = active.get(player.getUniqueId());
                if (current != null && current.token().equals(message.token())) finish(player, true, false);
            }
        } catch (IOException e) { getLogger().warning("Rejected malformed cinema bridge message"); }
    }

    private void start(Player player, BridgeMessage message) {
        long cooldown = Math.max(0, Math.min(300, getConfig().getInt("combat-cooldown-seconds", 15))) * 1000L;
        if (message.seconds() < 1 || active.containsKey(player.getUniqueId())
                || recovery.contains(player.getUniqueId().toString()) || player.isDead() || player.isInsideVehicle()
                || clock.getAsLong() - combat.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            reply(player, BridgeMessage.Action.REJECT, message.token()); return;
        }
        var event = new CinemaPrepareEvent(player);
        getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) { reply(player, BridgeMessage.Action.REJECT, message.token()); return; }
        SavedPlayer saved = SavedPlayer.capture(player);
        saved.save(recovery.createSection(player.getUniqueId().toString()));
        try { saveRecovery(); }
        catch (IOException e) {
            recovery.set(player.getUniqueId().toString(), null);
            getLogger().severe("Could not save player recovery data; cinema start rejected: " + e.getMessage());
            reply(player, BridgeMessage.Action.REJECT, message.token()); return;
        }
        // Normal Bukkit state changes and teleports: other plugins may veto them.
        try {
            player.setInvulnerable(true);
            player.setGameMode(GameMode.SPECTATOR);
            if (player.getGameMode() != GameMode.SPECTATOR || !player.teleport(cinema)) {
                if (saved.restore(player, false)) clearRecovery(player);
                reply(player, BridgeMessage.Action.REJECT, message.token()); return;
            }
            active.put(player.getUniqueId(), new Active(message.token(), saved, clock.getAsLong() + message.seconds()*1000L));
            player.setFlySpeed(0); player.setWalkSpeed(0);
            getLogger().info("Cinema prepared for " + player.getName() + "; waiting to start playback");
            for (Player other : getServer().getOnlinePlayers()) if (!other.equals(player)) player.hidePlayer(plugin, other);
            // Let the normal world/chunk packets reach the client before starting
            // its six-second title/sound timeline.
            getServer().getScheduler().runTaskLater(plugin, () -> {
                var current = active.get(player.getUniqueId());
                if (player.isOnline() && current != null && current.token().equals(message.token())) {
                    try {
                        reply(player, BridgeMessage.Action.READY, message.token());
                        getLogger().info("Cinema READY sent for " + player.getName());
                    } catch (RuntimeException e) {
                        getLogger().log(java.util.logging.Level.SEVERE,"Could not confirm cinema start for " + player.getName(),e);
                        finish(player,true,true);
                    }
                }
            }, 10);
        } catch (RuntimeException e) {
            if (active.containsKey(player.getUniqueId())) finish(player, true, false);
            else { if (saved.restore(player, true)) clearRecovery(player); }
            getLogger().severe("Cinema start failed: " + e.getMessage());
            reply(player, BridgeMessage.Action.REJECT, message.token());
        }
    }

    private boolean finish(Player player, boolean teleport, boolean notify) {
        UUID id = player.getUniqueId();
        Active current = active.get(id);
        if (current == null) return false;
        boolean restored = false;
        internalChanges.add(id);
        try {
            restored = current.saved().restore(player, teleport);
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE,"Could not restore cinema player " + player.getName(),e);
        } finally {
            internalChanges.remove(id);
        }
        if (!restored) {
            returning.add(id);
            if (warned.add(id)) {
                getLogger().warning("Return blocked for " + player.getName() + "; protection/recovery remain active and return will be retried");
                if (player.isOnline()) player.sendMessage("Return from cinema is blocked. Protection remains active; retrying automatically. Help: /crownbackend leave");
            }
            return false;
        }
        active.remove(id,current); returning.remove(id); warned.remove(id);
        clearRecovery(player);
        for (Player other : getServer().getOnlinePlayers()) player.showPlayer(plugin, other);
        if (notify && player.isOnline()) reply(player, BridgeMessage.Action.ENDED, current.token());
        getLogger().info("Cinema player restored: " + player.getName());
        return true;
    }

    private void clearRecovery(Player player) {
        String key = player.getUniqueId().toString();
        var previous = recovery.getConfigurationSection(key);
        Map<String, Object> saved = previous == null ? Map.of() : new HashMap<>(previous.getValues(false));
        recovery.set(key, null);
        try { saveRecovery(); }
        catch (IOException e) {
            recovery.createSection(key, saved);
            getLogger().severe("Could not clear recovery record: " + e.getMessage());
        }
    }

    private void saveRecovery() throws IOException {
        var destination = recoveryFile.toPath();
        var temporary = java.nio.file.Files.createTempFile(destination.getParent(), "recovery-", ".tmp");
        try {
            java.nio.file.Files.writeString(temporary, recovery.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            try { java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
    }

    boolean recover(Player player) {
        if (active.containsKey(player.getUniqueId())) return finish(player,true,true);
        var section = recovery.getConfigurationSection(player.getUniqueId().toString());
        if (section == null) return false;
        internalChanges.add(player.getUniqueId());
        try {
            if (SavedPlayer.load(section).restore(player, true)) { clearRecovery(player); return true; }
        }
        catch (RuntimeException e) { getLogger().severe("Player recovery failed for " + player.getName() + ": " + e.getMessage()); }
        finally { internalChanges.remove(player.getUniqueId()); }
        return false;
    }

    String status(Player player) {
        return "Active=" + active.containsKey(player.getUniqueId()) + ", return pending=" + returning.contains(player.getUniqueId())
                + ", recovery data=" + recovery.contains(player.getUniqueId().toString()) + ", world=" + player.getWorld().getName();
    }

    private void reply(Player player, BridgeMessage.Action action, UUID token) {
        player.sendPluginMessage(plugin, BridgeMessage.CHANNEL, new BridgeMessage(action, token, 0).encode());
    }

    @EventHandler public void join(PlayerJoinEvent event) { getServer().getScheduler().runTask(plugin, () -> recover(event.getPlayer())); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        finish(player,true,false);
        // Failed logout returns retain durable data for the next login, not an
        // in-memory session that would prevent a fresh recovery attempt.
        active.remove(player.getUniqueId()); returning.remove(player.getUniqueId()); warned.remove(player.getUniqueId());
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void move(PlayerMoveEvent event) {
        if (!(event instanceof PlayerTeleportEvent) && active.containsKey(event.getPlayer().getUniqueId())) event.setTo(event.getFrom());
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void teleported(PlayerTeleportEvent event) {
        var player = event.getPlayer();
        var current = active.get(player.getUniqueId());
        if (current == null || internalChanges.contains(player.getUniqueId()) || event.getTo() == null) return;
        // A correction/teleport inside the private cinema world is not an exit.
        // Previously this cleared protection and recovery while the player was
        // still in the box, and could cancel READY before playback even began.
        if (cinema.getWorld().equals(event.getTo().getWorld())) return;
        getServer().getScheduler().runTask(plugin, () -> {
            // Verify the teleport actually committed; a later listener may veto it.
            if (active.get(player.getUniqueId()) == current && !cinema.getWorld().equals(player.getLocation().getWorld()))
                finish(player,false,true);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim && active.containsKey(victim.getUniqueId())) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent hit) {
            Player attacker = hit.getDamager() instanceof Player p ? p
                    : hit.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
            if (attacker != null && active.containsKey(attacker.getUniqueId())) event.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void combat(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
        if (attacker != null && event.getEntity() instanceof Player victim) {
            long now = clock.getAsLong(); combat.put(attacker.getUniqueId(), now); combat.put(victim.getUniqueId(), now);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void interact(PlayerInteractEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent event) {
        if (!active.containsKey(event.getPlayer().getUniqueId())) return;
        String command = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (command.equals("/crownbackend leave") || command.equals("/crowncinemabackend:crownbackend leave")
                || event.getPlayer().hasPermission("crowncinema.backend.admin") && (command.startsWith("/crownbackend ") || command.startsWith("/crowncinemabackend:crownbackend "))) return;
        // Proxy-owned /crowncinema is handled before reaching this backend.
        event.setCancelled(true); event.getPlayer().sendMessage("Please wait until the animation finishes.");
    }
}
