package de.crown.cinema.backend;

import java.util.Random;
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrownCinemaBackend extends JavaPlugin {
    private CinemaController controller;
    @Override public void onEnable() {
        saveDefaultConfig();
        var world = new WorldCreator(getConfig().getString("world-name", "crown_cinema"))
                .generator(new ChunkGenerator() {
                    @Override public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
                        return createChunkData(world);
                    }
                }).createWorld();
        if (world == null) throw new IllegalStateException("Could not create the cinema world");
        world.setTime(18000); world.setStorm(false); world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        for (int x=-3;x<=3;x++) for (int y=69;y<=76;y++) for (int z=-3;z<=3;z++)
            if (Math.abs(x)==3 || y==69 || y==76 || Math.abs(z)==3) world.getBlockAt(x,y,z).setType(Material.BLACK_CONCRETE, false);
        controller = new CinemaController(this, new Location(world, .5, 72, .5, 180, 0), System::currentTimeMillis);
        controller.enable();
    }
    @Override public void onDisable() { if (controller != null) controller.disable(); }
    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (controller == null) { sender.sendMessage("Crown Cinema Backend is not running. Check the server log."); return true; }
        String action = args.length == 0 ? "leave" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("leave") && args.length <= 1 && sender instanceof org.bukkit.entity.Player player) {
            sender.sendMessage(controller.recover(player) ? "Return completed." : "No return completed. Contact an administrator if the return is blocked."); return true;
        }
        if (!sender.hasPermission("crowncinema.backend.admin")) { sender.sendMessage("Missing permission: crowncinema.backend.admin."); return true; }
        if (!java.util.List.of("recover","status").contains(action) || args.length > 2) return false;
        var player = args.length == 2 ? getServer().getPlayerExact(args[1]) : sender instanceof org.bukkit.entity.Player p ? p : null;
        if (player == null) { sender.sendMessage("Specify an online player: /crownbackend " + action + " <player>"); return true; }
        if (action.equals("status")) sender.sendMessage(player.getName() + ": " + controller.status(player));
        else sender.sendMessage(controller.recover(player) ? "Return completed: " + player.getName() : "Return not completed. Check status and the server log.");
        return true;
    }
}
