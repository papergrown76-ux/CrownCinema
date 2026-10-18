package de.crown.cinema;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.player.GameMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class CrownCinema implements CinemaSession.Listener {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final Map<UUID, CinemaSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<UUID, Ticket> notifications = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> welcomed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Player> scheduledPackOffers = new ConcurrentHashMap<>();
    private CinemaConfig config;
    private ServerSettings serverSettings;
    private ServerAdminCommand serverAdmin;
    private ClientPolicy policy;
    private PackService packs;
    private volatile Runnable pendingVideoPublish;
    private Limbo room;
    private PackHttpServer http;
    private BackendBridge bridge;
    private VideoManager videos;
    private AdminDialogs dialogs;
    private DebugLog debug;
    private final java.util.concurrent.ScheduledExecutorService backendExecutor = java.util.concurrent.Executors.newScheduledThreadPool(2);
    private volatile boolean running;

    @Inject public CrownCinema(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            config = CinemaConfig.load(directory);
            debug = new DebugLog(directory, logger);
            serverSettings = new ServerSettings(directory,config.serverModes());
            serverAdmin = new ServerAdminCommand(proxy,serverSettings,this::serverModeChanged,logger);
            videos = new VideoManager(directory, this::whenPlaybackIdle, this::catalogInstalled);
            var assets = videos.snapshot();
            if (assets.clips().size() > 1 && (config.packUrl() != null || !config.httpEnabled()))
                throw new IllegalArgumentException("Custom videos require pack.url empty and pack.http.enabled=true for automatic hosting");
            logger.info("Video: {} ({} frames, {} fps)", assets.defaultClip().name(), assets.defaultClip().sequence().frames(), assets.defaultClip().sequence().fps());
            debug.record("video.ready", "name=" + assets.defaultClip().name() + " clips=" + assets.clips().size());
            var factory = (LimboFactory) proxy.getPluginManager().getPlugin("limboapi")
                    .flatMap(p -> p.getInstance()).orElseThrow(() -> new IllegalStateException("LimboAPI is required on the proxy"));
            policy = new ClientPolicy(factory.getPrepareMinVersion(), factory.getPrepareMaxVersion());
            packs = new PackService(proxy, config, policy, directory, this::schedulePackOffer, assets.pack().file());
            bridge = new BackendBridge(backendExecutor);
            proxy.getChannelRegistrar().register(BackendBridge.CHANNEL);
            var world = factory.createVirtualWorld(Dimension.OVERWORLD, 0.5, 72, 0.5, 180, 0);
            var black = factory.createSimpleBlock("minecraft:black_concrete");
            for (int x = -3; x <= 3; x++) for (int y = 69; y <= 76; y++) for (int z = -3; z <= 3; z++) {
                if (x == -3 || x == 3 || y == 69 || y == 76 || z == -3 || z == 3) world.setBlock(x, y, z, black);
            }
            room = factory.createLimbo(world).setName("Crown Cinema").setGameMode(GameMode.SPECTATOR)
                    .setWorldTime(18000).setViewDistance(2).setSimulationDistance(2).setReducedDebugInfo(true);
            dialogs = new AdminDialogs(proxy, serverSettings, videos);
            var command = new CinemaCommand();
            var meta = proxy.getCommandManager().metaBuilder("crowncinema").plugin(this).build();
            proxy.getCommandManager().register(meta, command);
            room.registerCommand(meta, command);
            if (config.httpEnabled()) http = new PackHttpServer(config.httpBind(), config.httpPort(), packs::download);
            running = true;
            if (http != null) logger.info("Pack download server listening on {}:{}; allow this TCP port through your firewall. Public host: {}",
                    config.httpBind(), http.port(), config.httpPublicHost().isEmpty() ? "player connection hostname (automatic)" : config.httpPublicHost());
            if (!packs.configured()) logger.warn("Enable pack.http.enabled or set pack.url in {}/config.properties, then restart the proxy. Playback is disabled until then.", directory);
            logger.info("Crown Cinema initialized; protocol range {}–{}, resource pack SHA-1 {}", ClientPolicy.MINIMUM, policy.maximum(), packs.hash());
            if (config.serverModes().isEmpty()) logger.warn("No cinema.server.<name>=proxy|bridge configured; playback is disabled on all backends until configured.");
        } catch (Exception e) {
            logger.error("Crown Cinema could not initialize; normal gameplay remains available", e);
            if (http != null) http.close();
            if (room != null) room.dispose();
        }
    }

    @Subscribe public void pluginMessage(com.velocitypowered.api.event.connection.PluginMessageEvent event) {
        if (debug != null && event.getIdentifier().getId().equals("crowncinema:bridge")) {
            try {
                var message = de.crown.cinema.bridge.BridgeMessage.decode(event.getData());
                debug.record("bridge.message", "source=" + event.getSource().getClass().getSimpleName() + " action=" + message.action() + " token=" + message.token());
            } catch (java.io.IOException e) { debug.record("bridge.malformed", "source=" + event.getSource().getClass().getSimpleName()); }
        }
        if (bridge != null) bridge.message(event);
    }

    @Subscribe public void connected(ServerPostConnectEvent event) {
        if (!running) return;
        Player player = event.getPlayer();
        packs.backendChanged(player);
        CinemaSession session = sessions.get(player.getUniqueId());
        if (session != null) session.externalSwitch();
        if (welcomed.add(player.getUniqueId())) {
            if (policy.needsUpgrade(player.getProtocolVersion())) {
                player.sendMessage(Component.text(config.upgradeMessage(), NamedTextColor.YELLOW));
            } else if (!policy.supports(player.getProtocolVersion())) {
                player.sendMessage(Component.text("Your client version is not supported by Crown Cinema yet. You can continue playing normally.", NamedTextColor.YELLOW));
            }
        }
        Ticket notification = notifications.remove(player.getUniqueId());
        if (notification != null && notification.valid()) notifyJoin(player, notification);
        schedulePackOffer(player);
    }

    private void schedulePackOffer(Player player) {
        if (!running || !player.isActive() || scheduledPackOffers.putIfAbsent(player.getUniqueId(), player) != null) return;
        proxy.getScheduler().buildTask(this, () -> {
            if (!scheduledPackOffers.remove(player.getUniqueId(), player) || !running || !player.isActive()) return;
            if (serverSettings.mode(player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("")) == CinemaConfig.ServerMode.OFF) return;
            if (sessions.containsKey(player.getUniqueId())) { schedulePackOffer(player); return; }
            try { packs.offer(player); }
            catch (RuntimeException e) { logger.warn("Pack offer failed for {}", player.getUsername(), e); }
        }).delay(Duration.ofSeconds(1)).schedule();
    }

    @Subscribe public void packStatus(PlayerResourcePackStatusEvent event) {
        if (debug != null) debug.record("pack.status", "player=" + event.getPlayer().getUsername() + " pack=" + event.getPackId() + " status=" + event.getStatus());
        if (running) try { packs.status(event); }
        catch (java.io.IOException e) { logger.error("Could not save resource-pack choice for {}", event.getPlayer().getUsername(), e); }
    }
    // Run on the firing thread: independent async pack callbacks can reverse clear/replace packets.
    @Subscribe(order = PostOrder.LAST, async = false) public void protectPack(ServerResourcePackRemoveEvent event) {
        if (debug != null) debug.record("pack.remove", "player=" + event.getServerConnection().getPlayer().getUsername() + " pack=" + event.getPackId() + " allowed=" + event.getResult().isAllowed());
        if (running) packs.protect(event);
    }
    @Subscribe(order = PostOrder.LAST, async = false) public void backendPack(ServerResourcePackSendEvent event) {
        if (debug != null) debug.record("pack.backend.offer", "player=" + event.getServerConnection().getPlayer().getUsername() + " server=" + event.getServerConnection().getServerInfo().getName()
                + " pack=" + event.getProvidedResourcePack().getId() + " allowed=" + event.getResult().isAllowed());
        if (running) packs.backendOffer(event);
    }

    @Subscribe public void disconnected(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        CinemaSession session = sessions.get(id);
        if (session != null && session.player() == event.getPlayer()) session.onDisconnect();
        tickets.remove(id); notifications.remove(id); welcomed.remove(id);
        scheduledPackOffers.remove(id, event.getPlayer());
        if (packs != null) packs.forget(event.getPlayer());
    }

    @Override public void completed(CinemaSession session, boolean announced) {
        if (!sessions.remove(session.player().getUniqueId(), session)) return;
        if (pendingVideoPublish != null && running) backendExecutor.execute(this::applyPendingVideo);
        if (announced && session.player().isActive()) {
            Ticket ticket = new Ticket(session.target().getServerInfo().getName(), session.displayName(),
                    Instant.now().plusSeconds(config.ticketSeconds()));
            tickets.put(session.player().getUniqueId(), ticket);
            if (serverSettings.mode(session.player().getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("")) == CinemaConfig.ServerMode.BRIDGE)
                notifyJoin(session.player(), ticket);
            else notifications.put(session.player().getUniqueId(), ticket);
        }
    }

    private void notifyJoin(Player player, Ticket ticket) {
        player.sendMessage(Component.text(ticket.displayName() + " is now available! ", NamedTextColor.GOLD)
                .append(Component.text("[JOIN NOW]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/crowncinema join"))
                        .hoverEvent(HoverEvent.showText(Component.text("Join " + ticket.displayName() + "")))));
    }

    private synchronized boolean start(Player player, RegisteredServer target, String displayName) {
        if (!running || pendingVideoPublish != null || !policy.supports(player.getProtocolVersion()) || !packs.ready(player)
                || sessions.size() >= config.maxViewers() || player.getCurrentServer().isEmpty()) return false;
        var connection = player.getCurrentServer().orElseThrow();
        var mode = serverSettings.mode(connection.getServerInfo().getName());
        debug.record("cinema.request", "player=" + player.getUsername() + " server=" + connection.getServerInfo().getName() + " mode=" + mode + " packReady=" + packs.ready(player));
        if (mode == CinemaConfig.ServerMode.OFF) return false;
        var clip = videos.snapshot().forServer(target.getServerInfo().getName());
        debug.record("cinema.video", "target=" + target.getServerInfo().getName() + " video=" + clip.name() + " font=" + clip.font());
        var session = new CinemaSession(player, connection.getServer(), target,
                displayName, clip.sequence(), config.endcardSeconds(), this, logger, clip.font(), clip.sound());
        if (sessions.putIfAbsent(player.getUniqueId(), session) != null) return false;
        try {
            if (mode == CinemaConfig.ServerMode.BRIDGE) bridge.start(session, connection, (int) clip.sequence().duration().toSeconds() + config.endcardSeconds() + 5);
            else room.spawnPlayer(player, session);
            proxy.getScheduler().buildTask(this, session::startupTimeout).delay(Duration.ofSeconds(10)).schedule();
            return true;
        } catch (RuntimeException e) {
            logger.error("Cannot start cinema for {}", player.getUsername(), e);
            session.finish(false);
            return false;
        }
    }

    private synchronized void whenPlaybackIdle(Runnable publish) {
        if (!running) return;
        if (sessions.isEmpty()) publish.run();
        else pendingVideoPublish = publish;
    }

    private synchronized void applyPendingVideo() {
        if (!running || !sessions.isEmpty() || pendingVideoPublish == null) return;
        Runnable publish = pendingVideoPublish; pendingVideoPublish = null; publish.run();
    }

    private void catalogInstalled(VideoCatalog catalog) {
        boolean changed = packs.install(catalog.pack());
        debug.record("video.updated", "default=" + catalog.defaultClip().name() + " clips=" + catalog.clips().size() + " packChanged=" + changed);
        if (changed) for (var player : proxy.getAllPlayers()) schedulePackOffer(player);
    }

    private synchronized void serverModeChanged(RegisteredServer server,CinemaConfig.ServerMode mode) {
        for (var session : List.copyOf(sessions.values())) {
            if (session.previousServerName().equalsIgnoreCase(server.getServerInfo().getName())) {
                try { session.finish(false); }
                catch (RuntimeException e) { logger.warn("Could not stop cinema after mode change for {}",session.player().getUsername(),e); }
            }
        }
        if (mode != CinemaConfig.ServerMode.OFF) for (var player : server.getPlayersConnected()) {
            try { packs.offer(player); }
            catch (RuntimeException e) { logger.warn("Could not offer pack after mode change for {}",player.getUsername(),e); }
        }
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        running = false;
        pendingVideoPublish = null;
        if (videos != null) videos.close();
        List.copyOf(sessions.values()).forEach(s -> s.finish(false));
        if (http != null) http.close();
        if (room != null) room.dispose();
        backendExecutor.shutdownNow();
        proxy.getChannelRegistrar().unregister(BackendBridge.CHANNEL);
        tickets.clear(); notifications.clear(); welcomed.clear();
        proxy.getScheduler().tasksByPlugin(this).forEach(t -> t.cancel());
    }

    private record Ticket(String server, String displayName, Instant expires) {
        boolean valid() { return Instant.now().isBefore(expires); }
    }

    final class CinemaCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            if (!running) { message(source, "Crown Cinema is not running. Check the proxy log."); return; }
            if (args.length == 1 && args[0].equalsIgnoreCase("join")) { join(source); return; }
            if (args.length >= 1 && args[0].equalsIgnoreCase("pack")) { pack(source, args); return; }
            if (!source.hasPermission("crowncinema.admin")) { message(source, "You do not have permission."); return; }
            if (args.length == 0 || args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("gui")) {
                try {
                    debug.record("gui.request", "source=" + source.getClass().getName() + " protocol=" + (source instanceof Player p ? p.getProtocolVersion() : "console"));
                    if (args.length == 4 && args[1].equalsIgnoreCase("mode")) {
                        serverAdmin.execute(source, new String[]{"server", args[2], args[3]});
                        dialogs.open(source, new String[]{"gui", "server", args[2]});
                    } else {
                        boolean opened = dialogs.open(source, args); debug.record("gui.open", "opened=" + opened);
                        if (!opened) serverAdmin.execute(source, new String[]{"servers"});
                    }
                } catch (RuntimeException | java.io.IOException e) { logger.warn("Could not open admin dialog", e); message(source, "Could not open dialog; use /crowncinema servers or /crowncinema video list."); }
                return;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("video")) { video(source, args); return; }
            if (args.length == 2 && args[0].equalsIgnoreCase("debug") && List.of("on", "off").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                try { debug.set(args[1].equalsIgnoreCase("on")); message(source, "Debug " + (debug.enabled() ? "enabled" : "disabled") + ". Log: plugins/crowncinema/debug.log (maximum 2 MiB plus one rotated file)."); }
                catch (java.io.IOException e) { message(source, "Could not save debug mode: " + e.getMessage()); }
                return;
            }
            if (args.length >= 1 && List.of("servers","server","menu").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
                serverAdmin.execute(source,args); return;
            }
            if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("status")) {
                message(source, "Video: " + videos.snapshot().defaultClip().name() + " | Import running: " + videos.busy() + " | Debug: " + debug.enabled());
                message(source, "Pack download: " + (config.packUrl() != null ? config.packUrl() : http != null
                        ? "self-hosted, port " + http.port() : "disabled") + " | Viewers: " + sessions.size()
                        + " | SHA-1: " + packs.hash());
                Player p = args.length == 2 ? proxy.getPlayer(args[1]).orElse(null) : source instanceof Player self ? self : null;
                if (args.length == 2 && p == null) { message(source, "Player is not connected: " + args[1]); return; }
                if (p != null) {
                    message(source, "Your pack: " + packs.statusOf(p));
                    message(source, "Backend packs: " + packs.backendStatusOf(p));
                    message(source, "Saved pack preference: " + packs.preference(p));
                    message(source, "Playback on this server: " + p.getCurrentServer().map(s -> serverSettings.mode(s.getServerInfo().getName()).name()).orElse("OFF"));
                    message(source, "Video for this target server: " + p.getCurrentServer().map(c -> videos.snapshot().forServer(c.getServerInfo().getName()).name()).orElse("none"));
                    message(source, "Download URL: " + config.packUrlFor(p.getVirtualHost().map(a -> a.getHostString()).orElse(null)));
                }
                return;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
                if (args[1].equalsIgnoreCase("all")) List.copyOf(sessions.values()).forEach(s -> s.finish(false));
                else proxy.getPlayer(args[1]).map(Player::getUniqueId).map(sessions::get).ifPresent(s -> s.finish(false));
                message(source, "Stop requested."); return;
            }
            if (args.length >= 2 && (args[0].equalsIgnoreCase("announce") || args[0].equalsIgnoreCase("preview"))) {
                if (!packs.configured()) { message(source, "Enable pack.http.enabled or configure pack.url, then restart the proxy."); return; }
                var target = proxy.getServer(args[1]);
                if (target.isEmpty()) { message(source, "Unknown Velocity server: " + args[1]); return; }
                String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : args[1];
                if (name.isBlank() || name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)) {
                    message(source, "The display name must contain 1-64 characters and no control characters."); return;
                }
                var viewers = args[0].equalsIgnoreCase("preview")
                        ? source instanceof Player p ? List.of(p) : List.<Player>of()
                        : List.copyOf(proxy.getAllPlayers());
                if (viewers.isEmpty()) { message(source, "No viewers. Preview is only available in game."); return; }
                int count = 0;
                for (Player player : viewers) if (start(player, target.orElseThrow(), name)) count++;
                message(source, "Animation requested for " + count + " players; " + (viewers.size() - count)
                        + " skipped (server mode, client, pack, active session or viewer limit). Backends can reject bridge requests.");
                return;
            }
            message(source, "/crowncinema servers | server <name> <off|proxy|bridge> | announce <server> [display name] | preview <server> [display name] | stop <player|all> | status [player] | video <list|import file|use file|server name file|reload|unload file|default> | debug <on|off> | pack <on|off|ask>");
        }

        private void video(CommandSource source, String[] args) {
            try {
                if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
                    var state = videos.snapshot();
                    message(source, "Active: " + state.defaultClip().name() + " | Available in plugins/crowncinema/videos/: " + videos.list());
                    message(source, "Loaded: " + state.clips().keySet() + " | Server videos: " + state.servers() + " | Import running: " + videos.busy()); return;
                }
                if (config.packUrl() != null || !config.httpEnabled()) { message(source, "Live video updates require pack.url empty and pack.http.enabled=true."); return; }
                String action, name = "", server = "";
                if (args.length == 2 && args[1].equalsIgnoreCase("default")) { action = "use"; name = "builtin"; }
                else if (args.length == 2 && args[1].equalsIgnoreCase("reload")) action = "reload";
                else if (args.length >= 3 && List.of("use", "import", "unload").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    action = args[1].toLowerCase(java.util.Locale.ROOT); name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                } else if (args.length >= 4 && args[1].equalsIgnoreCase("server")) {
                    var target = proxy.getServer(args[2]);
                    if (target.isEmpty()) { message(source, "Unknown Velocity server: " + args[2]); return; }
                    action = "server"; server = target.orElseThrow().getServerInfo().getName().toLowerCase(java.util.Locale.ROOT);
                    name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                } else { message(source, "/crowncinema video list | import <file> | use <file> | server <server> <file|default> | reload | unload <file> | default"); return; }
                videos.update(action, name, server, text -> {
                    if (!(source instanceof Player player) || player.isActive()) message(source, text);
                    debug.record("video.import", text);
                });
            } catch (java.io.IOException e) { message(source, "Video selection failed: " + e.getMessage()); }
        }

        private void pack(CommandSource source, String[] args) {
            if (!(source instanceof Player player)) { message(source, "Only available in game."); return; }
            if (!source.hasPermission("crowncinema.pack")) { message(source, "Missing permission: crowncinema.pack."); return; }
            if (args.length == 1) {
                message(source, "Pack preference: " + packs.preference(player) + " | /crowncinema pack on|off|ask"); return;
            }
            if (args.length != 2 || !List.of("on", "off", "ask").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                message(source, "/crowncinema pack on|off|ask"); return;
            }
            var choice = PackPreferences.Choice.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
            if (choice == PackPreferences.Choice.OFF) {
                CinemaSession session = sessions.get(player.getUniqueId());
                if (session != null) session.finish(false);
            }
            try { packs.preference(player, choice); }
            catch (java.io.IOException | RuntimeException e) {
                logger.error("Could not change resource-pack choice for {}", player.getUsername(), e);
                message(source, "Could not fully apply the pack preference. Contact an administrator."); return;
            }
            message(source, "Pack preference saved: " + choice + ".");
            if (choice == PackPreferences.Choice.OFF) message(source, "You will no longer receive Crown pack offers. Use /crowncinema pack on to enable them again.");
            else message(source, "To avoid repeated prompts, set Server Resource Packs to Enabled for this entry in your Minecraft server list.");
        }

        private void join(CommandSource source) {
            if (!(source instanceof Player player)) { message(source, "Only available in game."); return; }
            Ticket ticket = tickets.get(player.getUniqueId());
            if (ticket == null || !ticket.valid()) {
                tickets.remove(player.getUniqueId()); message(source, "No current server announcement."); return;
            }
            var target = proxy.getServer(ticket.server());
            if (target.isEmpty()) { message(source, "This server is no longer registered."); return; }
            if (sessions.containsKey(player.getUniqueId())) { message(source, "Please wait until the animation finishes."); return; }
            // Normal Velocity connection request: existing permission/routing plugins retain control.
            player.createConnectionRequest(target.orElseThrow()).connectWithIndication().thenAccept(success -> {
                if (success) tickets.remove(player.getUniqueId(), ticket);
            });
        }

        @Override public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            boolean admin = invocation.source().hasPermission("crowncinema.admin");
            boolean pack = invocation.source().hasPermission("crowncinema.pack");
            if (args.length <= 1) {
                var commands = new java.util.ArrayList<String>();
                commands.add("join");
                if (pack) commands.add("pack");
                if (admin) commands.addAll(List.of("servers","server","menu","gui","announce", "preview", "status", "stop", "video", "debug"));
                return commands;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("pack") && pack) return List.of("on", "off", "ask");
            if (!admin) return List.of();
            if (args.length == 2 && args[0].equalsIgnoreCase("debug")) return List.of("on", "off");
            if (args.length == 2 && args[0].equalsIgnoreCase("video")) return List.of("list", "import", "use", "server", "reload", "unload", "default");
            if (args.length >= 3 && args[0].equalsIgnoreCase("video") && List.of("use", "import", "unload").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                try { return videos.list(); } catch (java.io.IOException e) { return List.of(); }
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("video") && args[1].equalsIgnoreCase("server"))
                return proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).toList();
            if (args.length >= 4 && args[0].equalsIgnoreCase("video") && args[1].equalsIgnoreCase("server")) {
                try { var names = new java.util.ArrayList<>(videos.list()); names.add("default"); names.add("builtin"); return names; }
                catch (java.io.IOException e) { return List.of("default", "builtin"); }
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("announce") || args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("server")))
                return proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).filter(n -> n.startsWith(args[1])).toList();
            if (args.length >= 3 && args[0].equalsIgnoreCase("server"))
                return List.of("off","proxy","bridge").stream().filter(m -> m.startsWith(args[args.length-1].toLowerCase(java.util.Locale.ROOT))).toList();
            return List.of();
        }
    }

    private static void message(CommandSource source, String text) { source.sendMessage(Component.text(text, NamedTextColor.YELLOW)); }
}
