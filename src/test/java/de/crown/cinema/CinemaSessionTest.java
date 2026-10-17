package de.crown.cinema;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.protocol.packet.ClientboundSoundEntityPacket;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.sound.Sound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CinemaSessionTest {
    private Player player;
    private RegisteredServer previous;
    private LimboPlayer limbo;
    private CinemaSession.Listener listener;
    private CinemaSession session;
    private ScheduledFuture<?> task;
    @BeforeEach void setup() {
        player = mock(Player.class); when(player.isActive()).thenReturn(true);
        previous = mock(RegisteredServer.class);
        limbo = mock(LimboPlayer.class);
        var executor = mock(ScheduledExecutorService.class);
        task = mock(ScheduledFuture.class);
        doReturn(task).when(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
        when(limbo.getScheduledExecutor()).thenReturn(executor);
        listener = mock(CinemaSession.Listener.class);
        session = new CinemaSession(player, previous, mock(RegisteredServer.class), "Survival",
                new Sequence(72,12,0xe100), 3, listener, mock(Logger.class));
    }
    @Test void finishesAndReturnsToOriginalBackendExactlyOnce() {
        session.onSpawn(mock(Limbo.class), limbo);
        session.tick(System.nanoTime() + 10_000_000_000L);
        session.onDisconnect(); session.finish(false);
        verify(limbo).disconnect(previous);
        verify(task).cancel(false);
        verify(listener).completed(session, true);
        verify(listener, times(1)).completed(any(), anyBoolean());
    }
    @Test void startsNamedMasterSoundOnTheActualLimboPlayer() {
        session.onSpawn(mock(Limbo.class), limbo);
        verify(limbo).writePacketAndFlush(argThat(packet -> packet instanceof ClientboundSoundEntityPacket sound
                && sound.getEmitterEntityId() == 1
                && sound.getSound().name().equals(CinemaAudio.SOUND)
                && sound.getSound().source() == Sound.Source.MASTER
                && sound.getSound().volume() == 0.8f
                && sound.getSound().pitch() == 1));
        verify(player, never()).playSound(any(Sound.class));
    }
    @Test void lateSpawnAfterStopIsReturnedWithoutPlayingMovie() {
        session.finish(false);
        session.onSpawn(mock(Limbo.class), limbo);
        verify(limbo).disconnect(previous);
        verify(player, never()).showTitle(any());
        verify(listener).completed(session, false);
    }
    @Test void manualServerSwitchIsNeverOverridden() {
        session.onSpawn(mock(Limbo.class), limbo);
        session.externalSwitch(); session.finish(true);
        verify(limbo, never()).disconnect(any(RegisteredServer.class));
        verify(task).cancel(false);
        verify(listener).completed(session, false);
    }
    @Test void backendModeCompletesWithoutAnyServerReconnect() {
        Runnable stop = mock(Runnable.class);
        var executor = mock(ScheduledExecutorService.class);
        doReturn(task).when(executor).scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),any());
        session.backendRequested(stop); session.onBackendSpawn(executor);
        verify(player).playSound(any(Sound.class),eq(Sound.Emitter.self()));
        session.tick(System.nanoTime()+10_000_000_000L);
        verify(stop).run(); verify(listener).completed(session,true);
        verify(player,never()).createConnectionRequest(any());
    }
    @Test void missingBackendBridgeTimesOutWithoutMovingPlayerToAnotherServer() {
        Runnable stop = mock(Runnable.class);
        session.backendRequested(stop); session.startupTimeout();
        verify(stop).run(); verify(listener).completed(session,false);
        verify(player,never()).createConnectionRequest(any());
    }
    @Test void failureDuringSpawnReleasesPlayerAndSession() {
        doThrow(new IllegalStateException("connection closed")).when(limbo).sendAbilities(anyByte(), anyFloat(), anyFloat());
        session.onSpawn(mock(Limbo.class), limbo);
        verify(limbo).disconnect(previous);
        verify(listener).completed(session, false);
    }
    @Test void disconnectedPlayerDoesNotReceiveJoinTicketOrReconnect() {
        session.onSpawn(mock(Limbo.class), limbo);
        when(player.isActive()).thenReturn(false);
        session.tick(System.nanoTime() + 10_000_000_000L);
        verify(listener).completed(session, false);
        verify(limbo, never()).disconnect(any(RegisteredServer.class));
        verify(task).cancel(false);
    }
}
