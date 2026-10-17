import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin: temporarily veto returns; never included in a release. */
public final class BridgeTestFaults extends JavaPlugin implements Listener {
    private long blockedUntil;
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this,this); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if (!sender.hasPermission("crownbridgetest.admin")) return true;
        blockedUntil=System.currentTimeMillis()+3000;
        sender.sendMessage("BRIDGE_TEST_RETURN_BLOCKED"); return true;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) {
        if (System.currentTimeMillis()<blockedUntil && event.getFrom().getWorld().getName().equals("crown_cinema")
                && event.getTo()!=null && !event.getTo().getWorld().equals(event.getFrom().getWorld())) {
            event.setCancelled(true); getLogger().info("BRIDGE_TEST_RETURN_VETO");
        }
    }
}
