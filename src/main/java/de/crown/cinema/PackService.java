package de.crown.cinema;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class PackService {
    static final UUID PACK_ID = UUID.fromString("43e702b4-74f4-465a-8f40-d48b901d5638");
    private final ClientPolicy policy;
    private final ProxyServer proxy;
    private final CinemaConfig config;
    private final PackPreferences preferences;
    record Payload(Path file, String hash) {
        static Payload load(Path file) throws IOException {
            try (var input = Files.newInputStream(file)) {
                var digest = MessageDigest.getInstance("SHA-1"); byte[] bytes = new byte[65536]; int n;
                while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
                return new Payload(file, HexFormat.of().formatHex(digest.digest()));
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        byte[] digest() { return HexFormat.of().parseHex(hash); }
    }
    private volatile Payload current;
    private final Map<String, Payload> versions = new ConcurrentHashMap<>();
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> states = new ConcurrentHashMap<>();

    private final java.util.function.Consumer<Player> retryOffer;
    private final Map<UUID, Map<UUID, com.velocitypowered.api.proxy.ServerConnection>> backendOffers = new ConcurrentHashMap<>();
    private final Map<UUID, String> backendStates = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> knownBackendPacks = new ConcurrentHashMap<>();

    PackService(ProxyServer proxy, CinemaConfig config, ClientPolicy policy, Path directory) throws IOException {
        this(proxy, config, policy, directory, player -> { });
    }

    PackService(ProxyServer proxy, CinemaConfig config, ClientPolicy policy, Path directory,
                java.util.function.Consumer<Player> retryOffer) throws IOException {
        this(proxy, config, policy, directory, retryOffer, null);
    }

    PackService(ProxyServer proxy, CinemaConfig config, ClientPolicy policy, Path directory,
                java.util.function.Consumer<Player> retryOffer, Path importedPack) throws IOException {
        this.policy = policy;
        this.proxy = proxy;
        this.config = config;
        this.retryOffer = retryOffer;
        this.preferences = new PackPreferences(directory);
        if (importedPack == null) {
            Path pack = directory.resolve("crown-cinema.zip");
            try (var in = PackService.class.getResourceAsStream("/cinema/crown-cinema.zip")) {
                if (in == null) throw new IOException("Missing cinema resource pack");
                Files.copy(in, pack, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            current = Payload.load(pack);
        } else current = Payload.load(importedPack);
        versions.put(current.hash(), current);
    }

    synchronized boolean install(Payload payload) {
        if (current.hash().equals(payload.hash())) return false;
        versions.put(payload.hash(), payload); current = payload;
        offered.clear(); states.clear(); return true;
    }
    Payload download(String version) { return version == null ? current : versions.get(version); }

    boolean configured() { return config.packUrl() != null || config.httpEnabled(); }
    String hash() { return current.hash(); }

    synchronized void offer(Player player) {
        if (!configured() || !policy.supports(player.getProtocolVersion()) || !player.isActive()
                || preference(player) == PackPreferences.Choice.OFF) return;
        var url = config.packUrlFor(player.getVirtualHost().map(address -> address.getHostString()).orElse(null));
        if (url == null) return;
        if (hasCurrent(player)) return;
        // Backend pack managers must finish their reload before our optional
        // pack starts another reload. Do not interrupt their pending requests.
        if (!backendOffers.getOrDefault(player.getUniqueId(), Map.of()).isEmpty()
                || player.getPendingResourcePacks().stream().anyMatch(p -> p.getOrigin() == ResourcePackInfo.Origin.DOWNSTREAM_SERVER)) {
            retryOffer.accept(player); return;
        }
        Payload payload = current;
        if (owns(player)) { player.removeResourcePacks(PACK_ID); offered.remove(player.getUniqueId()); }
        if (!offered.add(player.getUniqueId())) return;
        try {
            var offer = proxy.createResourcePackBuilder(url.toASCIIString() + (config.packUrl() == null ? "?v=" + payload.hash() : ""))
                    .setId(PACK_ID).setHash(payload.digest()).setShouldForce(config.required())
                    .setPrompt(Component.text("Crown Cinema - video cinematics")).build();
            player.sendResourcePackOffer(offer);
        }
        catch (RuntimeException e) { offered.remove(player.getUniqueId()); throw e; }
    }

    boolean ready(Player player) {
        return policy.supports(player.getProtocolVersion()) && player.getAppliedResourcePacks().stream()
                .anyMatch(p -> matches(p, current));
    }

    private boolean hasCurrent(Player player) {
        return ready(player) || player.getPendingResourcePacks().stream().anyMatch(p -> matches(p, current));
    }
    private static boolean matches(ResourcePackInfo pack, Payload payload) {
        return pack.getId().equals(PACK_ID) && java.util.Arrays.equals(pack.getHash(), payload.digest());
    }

    private boolean owns(Player player) {
        return player.getAppliedResourcePacks().stream().anyMatch(p -> p.getId().equals(PACK_ID))
                || player.getPendingResourcePacks().stream().anyMatch(p -> p.getId().equals(PACK_ID));
    }

    void status(PlayerResourcePackStatusEvent event) throws IOException {
        UUID id = event.getPackId();
        if (id == null && event.getPackInfo() != null) id = event.getPackInfo().getId();
        if (!PACK_ID.equals(id)) {
            var tracked = backendOffers.get(event.getPlayer().getUniqueId());
            if (id != null && (knownBackendPacks.getOrDefault(event.getPlayer().getUniqueId(), Set.of()).contains(id)
                    || event.getPackInfo() != null && event.getPackInfo().getOrigin() == ResourcePackInfo.Origin.DOWNSTREAM_SERVER)) {
                backendStates.put(event.getPlayer().getUniqueId(), id + " " + event.getStatus());
                if (!event.getStatus().isIntermediate()) {
                    if (tracked != null) tracked.remove(id);
                    retryOffer.accept(event.getPlayer());
                }
            }
            return;
        }
        states.put(event.getPlayer().getUniqueId(), event.getStatus());
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.ACCEPTED && offered.contains(event.getPlayer().getUniqueId()))
            preferences.set(event.getPlayer().getUniqueId(), PackPreferences.Choice.ON);
        else if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED)
            preferences.set(event.getPlayer().getUniqueId(), PackPreferences.Choice.OFF);
        switch (event.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                event.getPlayer().sendMessage(Component.text("Crown animations are disabled without the resource pack. You can continue playing normally.", NamedTextColor.YELLOW));
            default -> { }
        }
    }

    PackPreferences.Choice preference(Player player) { return preferences.get(player.getUniqueId()); }

    void preference(Player player, PackPreferences.Choice choice) throws IOException {
        preferences.set(player.getUniqueId(), choice);
        offered.remove(player.getUniqueId());
        if (choice == PackPreferences.Choice.OFF) {
            if (owns(player)) player.removeResourcePacks(PACK_ID);
        } else offer(player);
    }

    void protect(ServerResourcePackRemoveEvent event) {
        Player player = event.getServerConnection().getPlayer();
        if (!policy.supports(player.getProtocolVersion()) || !event.getResult().isAllowed()) return;
        if (PACK_ID.equals(event.getPackId()) && owns(player)) {
            event.setResult(ResultedEvent.GenericResult.denied());
        } else if (event.getPackId() == null) {
            // Keep the backend's clear/reload transaction intact. Replacing it
            // with our own removals also touched Nexo's pending pack requests.
            // Velocity applies the clear after this event; restore only our pack
            // later, once subsequent backend downloads have finished.
            backendOffers.remove(player.getUniqueId());
            offered.remove(player.getUniqueId());
            states.remove(player.getUniqueId());
            retryOffer.accept(player);
        } else {
            var tracked = backendOffers.get(player.getUniqueId());
            if (tracked != null) tracked.remove(event.getPackId());
        }
    }

    void backendOffer(ServerResourcePackSendEvent event) {
        if (!event.getResult().isAllowed()) return;
        Player player = event.getServerConnection().getPlayer();
        if (!policy.supports(player.getProtocolVersion())) return;
        var pack = event.getProvidedResourcePack();
        knownBackendPacks.computeIfAbsent(player.getUniqueId(), key -> ConcurrentHashMap.newKeySet()).add(pack.getId());
        backendOffers.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>()).put(pack.getId(), event.getServerConnection());
        backendStates.put(player.getUniqueId(), pack.getId() + " OFFERED");
    }

    void backendChanged(Player player) {
        var tracked = backendOffers.get(player.getUniqueId());
        if (tracked != null) tracked.values().removeIf(source -> player.getCurrentServer().orElse(null) != source);
    }

    void forget(Player player) {
        offered.remove(player.getUniqueId()); states.remove(player.getUniqueId());
        backendOffers.remove(player.getUniqueId()); backendStates.remove(player.getUniqueId());
        knownBackendPacks.remove(player.getUniqueId());
    }
    String backendStatusOf(Player player) {
        return "applied=" + player.getAppliedResourcePacks().stream().filter(p -> p.getOrigin() == ResourcePackInfo.Origin.DOWNSTREAM_SERVER).count()
                + ", pending=" + backendOffers.getOrDefault(player.getUniqueId(), Map.of()).size()
                + ", last=" + backendStates.getOrDefault(player.getUniqueId(), "no response");
    }
    String statusOf(Player player) {
        return ready(player) ? "applied" : states.getOrDefault(player.getUniqueId(), PlayerResourcePackStatusEvent.Status.DISCARDED).name();
    }
}
