package de.crown.cinema.backend;

import de.crown.cinema.bridge.BridgeMessage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CinemaControllerTest {
    @TempDir Path directory;
    private JavaPlugin plugin;
    private Player player;
    private Server server;
    private PluginManager manager;
    private World world, cinemaWorld;
    private Location origin, room;
    private CinemaController controller;
    private final UUID token = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<BridgeMessage> replies = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class); server = mock(Server.class); manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(manager);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return mock(org.bukkit.scheduler.BukkitTask.class); })
                .when(scheduler).runTaskLater(eq(plugin),any(Runnable.class),anyLong());
        doAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return mock(org.bukkit.scheduler.BukkitTask.class); })
                .when(scheduler).runTask(eq(plugin),any(Runnable.class));
        when(server.getOnlinePlayers()).thenReturn(List.of());
        world = mock(World.class); cinemaWorld = mock(World.class);
        when(world.getName()).thenReturn("survival"); when(cinemaWorld.getName()).thenReturn("crown_cinema");
        bukkit = mockStatic(Bukkit.class); bukkit.when(() -> Bukkit.getWorld("survival")).thenReturn(world);
        bukkit.when(Bukkit::getServer).thenReturn(server);
        origin = new Location(world, 123,64,456,45,10); room = new Location(cinemaWorld,.5,72,.5,180,0);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(origin);
        when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.SURVIVAL);
        when(player.getGameMode()).thenAnswer(i -> mode.get());
        doAnswer(i -> { mode.set(i.getArgument(0)); return null; }).when(player).setGameMode(any());
        when(player.getWalkSpeed()).thenReturn(.2f); when(player.getFlySpeed()).thenReturn(.1f);
        when(player.teleport(any(Location.class))).thenReturn(true);
        doAnswer(i -> { replies.add(BridgeMessage.decode(i.getArgument(2))); return null; })
                .when(player).sendPluginMessage(eq(plugin),eq(BridgeMessage.CHANNEL),any(byte[].class));
        controller = new CinemaController(plugin,room,now::get);
    }
    @AfterEach void close() { bukkit.close(); }

    private void start() { controller.message(BridgeMessage.CHANNEL,player,new BridgeMessage(BridgeMessage.Action.START,token,14).encode()); }
    private void stop(UUID id) { controller.message(BridgeMessage.CHANNEL,player,new BridgeMessage(BridgeMessage.Action.STOP,id,0).encode()); }

    @Test void protectsAndFreezesThenRestoresWithoutChangingServer() {
        start();
        assertEquals(BridgeMessage.Action.READY,replies.get(0).action());
        verify(player).teleport(room); verify(player).setInvulnerable(true);
        verify(player).setFlySpeed(0); verify(player).setWalkSpeed(0);
        var damage = mock(EntityDamageEvent.class); when(damage.getEntity()).thenReturn(player);
        controller.damage(damage); verify(damage).setCancelled(true);
        var move = new PlayerMoveEvent(player,room,new Location(room.getWorld(),1,72,1));
        controller.move(move); assertEquals(room,move.getTo());
        stop(token);
        verify(player).teleport(origin); verify(player).setInvulnerable(false);
        verify(player).setWalkSpeed(.2f); verify(player).setFlySpeed(.1f);
        assertEquals(GameMode.SURVIVAL,player.getGameMode());
        var normal = mock(EntityDamageEvent.class); when(normal.getEntity()).thenReturn(player);
        controller.damage(normal); verify(normal,never()).setCancelled(true);
    }
    @Test void wrongTokenCannotReleaseAnotherSessionAndWatchdogRestoresIt() {
        start(); stop(UUID.randomUUID()); verify(player,never()).teleport(origin);
        now.addAndGet(15_000); controller.expire();
        verify(player).teleport(origin); verify(player).setInvulnerable(false);
        assertEquals(BridgeMessage.Action.ENDED,replies.get(replies.size()-1).action());
    }
    @Test void combatAndIntegrationVetoRejectBeforeChangingPlayer() {
        Player attacker = mock(Player.class); when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        var hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getDamager()).thenReturn(attacker); when(hit.getEntity()).thenReturn(player);
        controller.combat(hit);
        start(); assertEquals(BridgeMessage.Action.REJECT,replies.get(0).action());
        verify(player,never()).teleport(any(Location.class)); verify(player,never()).setInvulnerable(anyBoolean());
        now.addAndGet(16_000);
        doAnswer(i -> { ((CinemaPrepareEvent)i.getArgument(0)).setCancelled(true); return null; }).when(manager).callEvent(any(CinemaPrepareEvent.class));
        start(); assertEquals(BridgeMessage.Action.REJECT,replies.get(1).action());
        verify(player,never()).setInvulnerable(anyBoolean());
    }
    @Test void recoveryDataRestoresPlayerAfterBackendRestart() {
        start();
        var restarted = new CinemaController(plugin,room,now::get);
        restarted.recover(player);
        verify(player).teleport(origin); verify(player).setInvulnerable(false);
        assertEquals(GameMode.SURVIVAL,player.getGameMode());
    }
    @Test void deniedTeleportRestoresProtectionAndRejects() {
        when(player.teleport(room)).thenReturn(false);
        start(); assertEquals(BridgeMessage.Action.REJECT,replies.get(0).action());
        verify(player).setInvulnerable(false); assertEquals(GameMode.SURVIVAL,player.getGameMode());
    }
    @Test void externalTeleportWinsOverTheCinemaReturnPosition() {
        start();
        controller.teleported(new PlayerTeleportEvent(player,room,new Location(origin.getWorld(),500,70,500)));
        verify(player,never()).teleport(origin);
        verify(player).setInvulnerable(false);
        assertEquals(BridgeMessage.Action.ENDED,replies.get(replies.size()-1).action());
    }
    @Test void correctionInsideCinemaBeforeReadyDoesNotCancelPlaybackOrLoseReturnData() {
        var scheduled=new ArrayList<Runnable>();
        var scheduler=server.getScheduler();
        doAnswer(i -> { scheduled.add(i.getArgument(1));return mock(org.bukkit.scheduler.BukkitTask.class); })
                .when(scheduler).runTaskLater(eq(plugin),any(Runnable.class),anyLong());
        start();assertTrue(replies.isEmpty());
        controller.teleported(new PlayerTeleportEvent(player,room,new Location(room.getWorld(),.6,72,.5)));
        verify(player,never()).setInvulnerable(false);
        assertTrue(replies.isEmpty());
        scheduled.get(0).run();
        assertEquals(BridgeMessage.Action.READY,replies.get(0).action());
        stop(token);verify(player).teleport(origin);verify(player).setInvulnerable(false);
    }
    @Test void blockedReturnKeepsProtectionAndRetriesWithoutLosingRecovery() {
        start();when(player.teleport(origin)).thenReturn(false);
        stop(token);
        verify(player,never()).setInvulnerable(false);
        assertEquals(GameMode.SPECTATOR,player.getGameMode());
        var hit=mock(EntityDamageEvent.class);when(hit.getEntity()).thenReturn(player);
        controller.damage(hit);verify(hit).setCancelled(true);
        assertTrue(YamlConfiguration.loadConfiguration(directory.resolve("recovery.yml").toFile()).contains(player.getUniqueId().toString()));
        when(player.teleport(origin)).thenReturn(true);now.addAndGet(1000);controller.expire();
        verify(player).setInvulnerable(false);assertEquals(GameMode.SURVIVAL,player.getGameMode());
        assertFalse(YamlConfiguration.loadConfiguration(directory.resolve("recovery.yml").toFile()).contains(player.getUniqueId().toString()));
    }
    @Test void externalTeleportThatDidNotCommitCannotReleasePlayerInsideCinema() {
        var scheduled=new ArrayList<Runnable>();
        var scheduler=server.getScheduler();
        doAnswer(i -> { scheduled.add(i.getArgument(1));return mock(org.bukkit.scheduler.BukkitTask.class); })
                .when(scheduler).runTask(eq(plugin),any(Runnable.class));
        start();when(player.getLocation()).thenReturn(room);
        controller.teleported(new PlayerTeleportEvent(player,room,origin));
        assertEquals(1,scheduled.size());scheduled.get(0).run();
        verify(player,never()).setInvulnerable(false);
        assertEquals(GameMode.SPECTATOR,player.getGameMode());
    }
}
