package de.crown.cinema.backend;

import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

record SavedPlayer(Location location, GameMode gameMode, boolean invulnerable,
                   boolean allowFlight, boolean flying, float walkSpeed, float flySpeed) {
    static SavedPlayer capture(Player player) {
        return new SavedPlayer(player.getLocation().clone(), player.getGameMode(), player.isInvulnerable(),
                player.getAllowFlight(), player.isFlying(), player.getWalkSpeed(), player.getFlySpeed());
    }
    void save(ConfigurationSection section) {
        section.set("world", location.getWorld().getName());
        section.set("x", location.getX()); section.set("y", location.getY()); section.set("z", location.getZ());
        section.set("yaw", location.getYaw()); section.set("pitch", location.getPitch());
        section.set("game-mode", gameMode.name());
        section.set("invulnerable", invulnerable); section.set("allow-flight", allowFlight);
        section.set("flying", flying); section.set("walk-speed", walkSpeed); section.set("fly-speed", flySpeed);
    }
    static SavedPlayer load(ConfigurationSection section) {
        Location location = new Location(Bukkit.getWorld(section.getString("world")),
                section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float)section.getDouble("yaw"), (float)section.getDouble("pitch"));
        return new SavedPlayer(location, GameMode.valueOf(section.getString("game-mode")),
                section.getBoolean("invulnerable"), section.getBoolean("allow-flight"), section.getBoolean("flying"),
                (float) section.getDouble("walk-speed"), (float) section.getDouble("fly-speed"));
    }
    boolean restore(Player player, boolean teleport) {
        // Never release a player into normal gameplay while still trapped in
        // the cinema because another plugin cancelled/redirected the return.
        if (teleport) {
            if (location == null || location.getWorld() == null || !player.teleport(location)) return false;
            Location actual = player.getLocation();
            if (!location.getWorld().equals(actual.getWorld()) || actual.distanceSquared(location) > .25) return false;
        }
        player.setGameMode(gameMode);
        if (player.getGameMode() != gameMode) return false;
        player.setAllowFlight(allowFlight);
        player.setFlying(flying && allowFlight);
        player.setWalkSpeed(walkSpeed); player.setFlySpeed(flySpeed);
        player.setFallDistance(0);
        player.setInvulnerable(invulnerable);
        return true;
    }
}
