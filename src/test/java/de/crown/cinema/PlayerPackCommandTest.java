package de.crown.cinema;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import static org.mockito.Mockito.*;

class PlayerPackCommandTest {
    @TempDir Path directory;
    @Test void normalPlayerCanChangeOwnChoiceWithOnlyPackPermission() throws Exception {
        var player=mock(Player.class);
        when(player.hasPermission("crowncinema.pack")).thenReturn(true);
        var packs=mock(PackService.class);
        var plugin=new CrownCinema(mock(ProxyServer.class),mock(Logger.class),directory);
        for(var entry:java.util.Map.of("running",(Object)true,"packs",packs).entrySet()) {
            var field=CrownCinema.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());
        }
        var invocation=mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(player);when(invocation.arguments()).thenReturn(new String[]{"pack","on"});
        plugin.new CinemaCommand().execute(invocation);
        verify(packs).preference(player,PackPreferences.Choice.ON);
        verify(player,never()).hasPermission("crowncinema.admin");
    }
    @Test void packPermissionDoesNotAllowAnnouncementsAndMissingPermissionCannotChangeChoice() throws Exception {
        var player=mock(Player.class);var packs=mock(PackService.class);
        var plugin=new CrownCinema(mock(ProxyServer.class),mock(Logger.class),directory);
        for(var entry:java.util.Map.of("running",(Object)true,"packs",packs).entrySet()) {
            var field=CrownCinema.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(plugin,entry.getValue());
        }
        var invocation=mock(SimpleCommand.Invocation.class);when(invocation.source()).thenReturn(player);
        when(invocation.arguments()).thenReturn(new String[]{"pack","off"});
        plugin.new CinemaCommand().execute(invocation);
        verify(packs,never()).preference(any(),any());
        when(player.hasPermission("crowncinema.pack")).thenReturn(true);
        when(invocation.arguments()).thenReturn(new String[]{"announce","lobby"});
        plugin.new CinemaCommand().execute(invocation);
        verify(player).hasPermission("crowncinema.admin");
    }
}
