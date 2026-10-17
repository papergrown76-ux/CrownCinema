package de.crown.cinema;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerAdminCommandTest {
    @TempDir Path directory;
    private ProxyServer proxy;
    private CommandSource admin;
    private ServerSettings settings;
    private ServerAdminCommand command;
    private final List<String> changes=new ArrayList<>();

    private RegisteredServer server(String name) {
        var server=mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name,InetSocketAddress.createUnresolved("localhost",25565)));
        return server;
    }
    @BeforeEach void setup() throws Exception {
        Files.writeString(directory.resolve("config.properties"),"pack.http.port=9124\n");
        proxy=mock(ProxyServer.class);admin=mock(CommandSource.class);
        when(admin.hasPermission("crowncinema.admin")).thenReturn(true);
        List<RegisteredServer> servers=List.of(server("survival"),server("lobby"),server("arena"));
        when(proxy.getAllServers()).thenReturn(servers);
        when(proxy.getServer(anyString())).thenAnswer(i -> servers.stream().filter(s -> s.getServerInfo().getName().equalsIgnoreCase(i.getArgument(0))).findFirst());
        settings=new ServerSettings(directory,Map.of());
        command=new ServerAdminCommand(proxy,settings,(s,m)->changes.add(s.getServerInfo().getName()+":"+m),mock(Logger.class));
    }
    private List<Component> messages() {
        var capture=ArgumentCaptor.forClass(Component.class);verify(admin,atLeastOnce()).sendMessage(capture.capture());
        return capture.getAllValues();
    }
    private static String text(Component c) {
        return (c instanceof TextComponent t ? t.content() : "")+c.children().stream().map(ServerAdminCommandTest::text).reduce("",String::concat);
    }
    private static List<String> clicks(Component c) {
        var clicks=new ArrayList<String>();
        if(c.clickEvent()!=null && c.clickEvent().payload() instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text t)clicks.add(t.value());
        c.children().forEach(child->clicks.addAll(clicks(child)));return clicks;
    }
    @Test void menuDiscoversServersAndOffersExecutableModeButtons() {
        command.execute(admin,new String[]{"servers"});
        String output=messages().stream().map(ServerAdminCommandTest::text).reduce("",String::concat);
        assertTrue(output.contains("survival: OFF (not configured)"));
        assertTrue(output.indexOf("arena:")<output.indexOf("lobby:"));
        var clicks=messages().stream().flatMap(c->clicks(c).stream()).toList();
        assertTrue(clicks.containsAll(List.of("/crowncinema server survival off","/crowncinema server survival proxy","/crowncinema server survival bridge")));
    }
    @Test void validAdminChangeIsSavedAppliedAndShownAsConfigured() throws Exception {
        command.execute(admin,new String[]{"server","SURVIVAL","bridge"});
        assertEquals(List.of("survival:BRIDGE"),changes);
        assertEquals(CinemaConfig.ServerMode.BRIDGE,settings.mode("survival"));
        assertEquals(CinemaConfig.ServerMode.BRIDGE,CinemaConfig.load(directory).serverMode("survival"));
        assertTrue(messages().stream().map(ServerAdminCommandTest::text).anyMatch(t->t.contains("survival: BRIDGE (configured)")));
    }
    @Test void explicitOffCountsAsConfiguredWithoutDisruptingAnAlreadyDisabledServer() {
        command.execute(admin,new String[]{"server","arena","off"});
        assertTrue(settings.configured("arena"));assertTrue(changes.isEmpty());
    }
    @Test void unknownServerInvalidModeAndMissingPermissionDoNotChangeConfiguration() throws Exception {
        byte[] original=Files.readAllBytes(directory.resolve("config.properties"));
        command.execute(admin,new String[]{"server","missing","proxy"});
        command.execute(admin,new String[]{"server","lobby","invalid"});
        when(admin.hasPermission("crowncinema.admin")).thenReturn(false);
        command.execute(admin,new String[]{"server","lobby","proxy"});
        command.execute(admin,new String[]{"servers"});
        verify(proxy,never()).getAllServers();
        assertArrayEquals(original,Files.readAllBytes(directory.resolve("config.properties")));
        assertTrue(changes.isEmpty());
    }
    @Test void paginationShowsEveryRegisteredServerWithoutFloodingTheChat() {
        var many=new ArrayList<RegisteredServer>();for(int i=8;i>=0;i--)many.add(server("server"+i));
        when(proxy.getAllServers()).thenReturn(many);
        command.execute(admin,new String[]{"menu","2"});
        var messages=messages();
        String output=messages.stream().map(ServerAdminCommandTest::text).reduce("",String::concat);
        assertTrue(output.contains("server8:"));assertFalse(output.contains("server0:"));
        assertTrue(messages.stream().flatMap(c->clicks(c).stream()).anyMatch(c->c.equals("/crowncinema servers 1")));
    }
}
