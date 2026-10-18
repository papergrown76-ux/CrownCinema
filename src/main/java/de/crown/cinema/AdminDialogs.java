package de.crown.cinema;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.nbt.*;

/** Inline vanilla dialogs, without backend registries, inventory spoofing or a client mod. */
final class AdminDialogs {
    private static final int PAGE_SIZE = 8;
    private final ProxyServer proxy;
    private final ServerSettings settings;
    private final VideoManager videos;
    record Button(String label, String command) { }
    AdminDialogs(ProxyServer proxy, ServerSettings settings, VideoManager videos) {
        this.proxy = proxy; this.settings = settings; this.videos = videos;
    }

    boolean open(CommandSource source, String[] args) throws IOException {
        if (!(source instanceof Player player) || player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_6)) return false;
        if (!source.hasPermission("crowncinema.admin")) return false;
        String section = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "home";
        List<Button> actions = new ArrayList<>(); String title = "Crown Cinema";
        String description = "Default video: " + videos.snapshot().defaultClip().name() + ". Import running: " + videos.busy();
        switch (section) {
            case "servers" -> {
                title += " - Servers";
                for (var server : proxy.getAllServers().stream().sorted(Comparator.comparing(s -> s.getServerInfo().getName())).toList()) {
                    String name = server.getServerInfo().getName();
                    actions.add(new Button(name + " | " + settings.mode(name) + " | " + videos.snapshot().servers().getOrDefault(name.toLowerCase(Locale.ROOT), "default"), "/crowncinema gui server " + name));
                }
            }
            case "server" -> {
                if (args.length < 3 || proxy.getServer(args[2]).isEmpty()) return false;
                String name = proxy.getServer(args[2]).orElseThrow().getServerInfo().getName(); title += " - " + name;
                description = "Playback mode: " + settings.mode(name) + ". Announcement video: " + videos.snapshot().forServer(name).name();
                for (String mode : List.of("off", "proxy", "bridge")) actions.add(new Button(mode.toUpperCase(Locale.ROOT), "/crowncinema gui mode " + name + " " + mode));
                actions.add(new Button("Choose announcement video", "/crowncinema gui videos " + name));
                actions.add(new Button("Use default video", "/crowncinema video server " + name + " default"));
                actions.add(new Button("Servers", "/crowncinema gui servers"));
            }
            case "default", "import", "videos" -> {
                String server = section.equals("videos") && args.length > 2 ? args[2] : null;
                if (section.equals("videos") && (server == null || proxy.getServer(server).isEmpty())) return false;
                title += " - Choose video";
                description = server == null ? "Choose a video to " + (section.equals("import") ? "import without changing the default." : "use as default, without restarting.") : "Video for announcements of " + server;
                if (!section.equals("import")) actions.add(new Button("Neutral demo", server == null ? "/crowncinema video default" : "/crowncinema video server " + server + " builtin"));
                for (String name : videos.list()) if (name.codePoints().noneMatch(Character::isISOControl)) {
                    actions.add(new Button(name, server != null ? "/crowncinema video server " + server + " " + name : "/crowncinema video " + (section.equals("import") ? "import " : "use ") + name));
                }
            }
            default -> {
                actions.add(new Button("Default video", "/crowncinema gui default"));
                actions.add(new Button("Import a video", "/crowncinema gui import"));
                actions.add(new Button("Server settings", "/crowncinema gui servers"));
                actions.add(new Button("Video status", "/crowncinema video list"));
            }
        }
        int argument = section.equals("videos") ? 3 : 2;
        int page = 1;
        if (args.length > argument && !section.equals("server")) {
            try { page = Integer.parseInt(args[argument]); } catch (NumberFormatException e) { return false; }
        }
        int pages = Math.max(1, (actions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 1 || page > pages) return false;
        var visible = new ArrayList<>(actions.subList(Math.min(actions.size(), (page - 1) * PAGE_SIZE), Math.min(actions.size(), page * PAGE_SIZE)));
        String base = "/crowncinema gui " + section + (section.equals("videos") ? " " + args[2] : "");
        if (page > 1) visible.add(new Button("Previous", base + " " + (page - 1)));
        if (page < pages) visible.add(new Button("Next", base + " " + (page + 1)));
        if (!section.equals("home")) visible.add(new Button("Main menu", "/crowncinema gui"));
        if (!(player instanceof ConnectedPlayer connected)) return false;
        if (connected.getConnection().getState() != StateRegistry.PLAY) return false;
        int packetId = packetId(player.getProtocolVersion().getProtocol());
        if (packetId < 0) return false;
        // Velocity b41 registers its DialogShowPacket only in CONFIG. Raw PLAY payloads
        // use the normal compression/encryption pipeline, just like forwarded backend packets.
        var buffer = Unpooled.buffer(); boolean handedOff = false;
        try {
            ProtocolUtils.writeVarInt(buffer, packetId);
            ProtocolUtils.writeVarInt(buffer, 0); // Inline registry holder, independent of backend registries.
            ProtocolUtils.writeBinaryTag(buffer, player.getProtocolVersion(), dialog(title + " (" + page + "/" + pages + ")", description, visible));
            var future = connected.getConnection().write(buffer); handedOff = true;
            if (future != null) future.addListener(result -> {
                if (!result.isSuccess()) {
                    System.getLogger(AdminDialogs.class.getName()).log(System.Logger.Level.WARNING, "Vanilla dialog transport failed", result.cause());
                    player.sendMessage(net.kyori.adventure.text.Component.text("Could not send settings dialog. Use /crowncinema servers."));
                }
            });
        } finally { if (!handedOff) buffer.release(); }
        return true;
    }

    static int packetId(int protocol) {
        return switch (protocol) {
            case 771, 772 -> 0x85; // 1.21.6-1.21.8, pinned minecraft-data protocol tables.
            case 773, 774 -> 0x8a; // 1.21.9-1.21.11.
            case 775, 776 -> 0x8c; // 26.1 data + actual Paper 26.2 GameProtocols table.
            case 777 -> 0x8f; // Actual unmodified 26.3 GameProtocols table.
            default -> -1;
        };
    }

    static CompoundBinaryTag dialog(String title, String description, List<Button> buttons) {
        var actions = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (var button : buttons) actions.add(CompoundBinaryTag.builder().putString("label", button.label()).putInt("width", 180)
                .put("action", CompoundBinaryTag.builder().putString("type", "minecraft:run_command").putString("command", button.command()).build()).build());
        return CompoundBinaryTag.builder().putString("type", "minecraft:multi_action").putString("title", title)
                .putBoolean("can_close_with_escape", true).putBoolean("pause", false).putString("after_action", "close")
                .putInt("columns", 2).put("actions", actions.build())
                .put("exit_action", CompoundBinaryTag.builder().putString("label", "Close").build())
                .put("body", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(CompoundBinaryTag.builder()
                        .putString("type", "minecraft:plain_message").putString("contents", description).putInt("width", 380).build()).build()).build();
    }
}
