package de.crown.cinema;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import de.crown.cinema.bridge.BridgeMessage;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import net.kyori.adventure.text.Component;

final class BackendBridge {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(BridgeMessage.CHANNEL);
    private record Pending(CinemaSession session, ServerConnection connection, UUID token) { }
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;

    BackendBridge(ScheduledExecutorService executor) { this.executor = executor; }

    void start(CinemaSession session, ServerConnection connection, int seconds) {
        var entry = new Pending(session, connection, UUID.randomUUID());
        pending.put(session.player().getUniqueId(), entry);
        session.backendRequested(() -> stop(entry));
        if (!connection.sendPluginMessage(CHANNEL, new BridgeMessage(BridgeMessage.Action.START, entry.token(), seconds).encode()))
            session.finish(false);
    }

    private void stop(Pending entry) {
        pending.remove(entry.session().player().getUniqueId(), entry);
        entry.connection().sendPluginMessage(CHANNEL, new BridgeMessage(BridgeMessage.Action.STOP, entry.token(), 0).encode());
    }

    void message(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        // Never forward client-supplied requests to a backend or backend responses to clients.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;
        Pending entry = pending.get(source.getPlayer().getUniqueId());
        if (entry == null || entry.connection() != source) return;
        try {
            var message = BridgeMessage.decode(event.getData());
            if (!entry.token().equals(message.token())) return;
            switch (message.action()) {
                case READY -> entry.session().onBackendSpawn(executor);
                case REJECT -> {
                    source.getPlayer().sendMessage(Component.text("Animation skipped: the backend rejected playback (for example, active combat)."));
                    entry.session().finish(false);
                }
                case ENDED -> entry.session().finish(false);
                default -> { }
            }
        } catch (IOException ignored) { /* Unrelated/malformed data cannot start a session. */ }
    }
}
