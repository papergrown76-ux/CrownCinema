package de.crown.cinema;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/** Proxy-only admin chat menu; no fake inventory or backend plugin is needed. */
final class ServerAdminCommand {
    private static final int PAGE_SIZE = 8;
    private final ProxyServer proxy;
    private final ServerSettings settings;
    private final BiConsumer<RegisteredServer, CinemaConfig.ServerMode> changed;
    private final Logger logger;

    ServerAdminCommand(ProxyServer proxy, ServerSettings settings,
                       BiConsumer<RegisteredServer, CinemaConfig.ServerMode> changed, Logger logger) {
        this.proxy = proxy; this.settings = settings; this.changed = changed; this.logger = logger;
    }

    void execute(CommandSource source, String[] args) {
        if (!source.hasPermission("crowncinema.admin")) {
            send(source,"Missing permission: crowncinema.admin."); return;
        }
        if (!args[0].equalsIgnoreCase("server") || args.length == 1) {
            int page = 1;
            try {
                if (args.length > 2) throw new NumberFormatException();
                if (args.length == 2) page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) { send(source,"/crowncinema servers [page]"); return; }
            menu(source,page); return;
        }
        if (args.length < 3) { send(source,"/crowncinema server <server> <off|proxy|bridge>"); return; }
        String name = String.join(" ",Arrays.copyOfRange(args,1,args.length-1));
        var server = proxy.getServer(name);
        if (server.isEmpty()) { send(source,"Unknown Velocity server: " + name + ". Overview: /crowncinema servers"); return; }
        CinemaConfig.ServerMode mode;
        try { mode = CinemaConfig.ServerMode.valueOf(args[args.length-1].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { send(source,"Allowed modes: off, proxy, bridge."); return; }
        String canonical = server.orElseThrow().getServerInfo().getName();
        var previous = settings.mode(canonical);
        try { settings.set(canonical,mode); }
        catch (IOException | IllegalArgumentException e) {
            logger.error("Could not save cinema mode for {}",canonical,e);
            send(source,"Could not save settings. Check write permissions and the proxy log."); return;
        }
        if (previous != mode) changed.accept(server.orElseThrow(),mode);
        source.sendMessage(Component.text(canonical + ": " + mode + " - saved and active immediately.",NamedTextColor.GREEN));
        if (mode == CinemaConfig.ServerMode.PROXY) send(source,"PROXY is intended for safe lobbies and briefly leaves the backend.");
        if (mode == CinemaConfig.ServerMode.BRIDGE) send(source,"BRIDGE requires the companion plugin on this backend. Selecting it does not verify installation.");
        row(source,server.orElseThrow());
        source.sendMessage(button("[Server overview]","/crowncinema servers",NamedTextColor.AQUA,"Show all registered Velocity servers"));
    }

    private void menu(CommandSource source,int page) {
        var servers = proxy.getAllServers().stream().sorted(Comparator.comparing(s -> s.getServerInfo().getName(),String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1,(servers.size()+PAGE_SIZE-1)/PAGE_SIZE);
        if (page < 1 || page > pages) { send(source,"Page must be between 1 and " + pages + "."); return; }
        source.sendMessage(Component.text("Crown Cinema - Servers (" + page + "/" + pages + ")",NamedTextColor.GOLD));
        if (servers.isEmpty()) send(source,"No servers registered in Velocity.");
        servers.stream().skip((long)(page-1)*PAGE_SIZE).limit(PAGE_SIZE).forEach(s -> row(source,s));
        Component navigation = Component.empty();
        if (page > 1) navigation = navigation.append(button("[Previous] ","/crowncinema servers " + (page-1),NamedTextColor.AQUA,"Previous page"));
        if (page < pages) navigation = navigation.append(button("[Next]","/crowncinema servers " + (page+1),NamedTextColor.AQUA,"Next page"));
        if (page > 1 || page < pages) source.sendMessage(navigation);
        send(source,"Click a mode. Console: crowncinema server <name> off|proxy|bridge");
    }

    private void row(CommandSource source,RegisteredServer server) {
        String name = server.getServerInfo().getName();
        var selected = settings.mode(name);
        Component row = Component.text(name + ": " + selected,NamedTextColor.WHITE)
                .append(Component.text(settings.configured(name) ? " (configured) " : " (not configured) ",
                        settings.configured(name) ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        String[] labels = {"Off","Lobby","Bridge"};
        String[] explanations = {"No animation on this server", "Safe lobby: temporary Limbo cinema with a backend switch", "Stays on the backend; companion plugin required"};
        int index=0;
        for (var mode : CinemaConfig.ServerMode.values()) {
            row = row.append(button("[" + labels[index] + "] ","/crowncinema server " + name + " " + mode.name().toLowerCase(Locale.ROOT),
                    selected == mode ? NamedTextColor.GREEN : NamedTextColor.GRAY,explanations[index]));
            index++;
        }
        source.sendMessage(row);
    }

    private static Component button(String text,String command,NamedTextColor color,String hover) {
        return Component.text(text,color).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Component.text(hover)));
    }
    private static void send(CommandSource source,String text) { source.sendMessage(Component.text(text,NamedTextColor.YELLOW)); }
}
