package de.crown.cinema;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import de.crown.cinema.bridge.BridgeMessage;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendBridgeTest {
    @Test void acceptsOnlyTheExpectedBackendAndNonceAndBlocksClientForwarding() {
        var executor = mock(ScheduledExecutorService.class);
        var bridge = new BackendBridge(executor);
        var session = mock(CinemaSession.class);
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(session.player()).thenReturn(player);
        var backend = mock(ServerConnection.class); when(backend.getPlayer()).thenReturn(player);
        when(backend.sendPluginMessage(any(),any(byte[].class))).thenReturn(true);
        bridge.start(session,backend,14);
        var wire = ArgumentCaptor.forClass(byte[].class);
        verify(backend).sendPluginMessage(eq(BackendBridge.CHANNEL),wire.capture());
        BridgeMessage start;
        try { start=BridgeMessage.decode(wire.getValue()); } catch(Exception e) {throw new AssertionError(e);}
        byte[] ready=new BridgeMessage(BridgeMessage.Action.READY,start.token(),0).encode();
        var forged=new PluginMessageEvent(player,backend,BackendBridge.CHANNEL,ready);
        bridge.message(forged); assertFalse(forged.getResult().isAllowed());
        bridge.message(new PluginMessageEvent(backend,player,BackendBridge.CHANNEL,
                new BridgeMessage(BridgeMessage.Action.READY,UUID.randomUUID(),0).encode()));
        var different=mock(ServerConnection.class); when(different.getPlayer()).thenReturn(player);
        bridge.message(new PluginMessageEvent(different,player,BackendBridge.CHANNEL,ready));
        verify(session,never()).onBackendSpawn(any());
        bridge.message(new PluginMessageEvent(backend,player,BackendBridge.CHANNEL,ready));
        verify(session).onBackendSpawn(executor);
        var stop=ArgumentCaptor.forClass(Runnable.class);
        verify(session).backendRequested(stop.capture()); stop.getValue().run();
        bridge.message(new PluginMessageEvent(backend,player,BackendBridge.CHANNEL,ready));
        verify(session,times(1)).onBackendSpawn(any());
    }
}
