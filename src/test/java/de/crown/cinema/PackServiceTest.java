package de.crown.cinema;

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Properties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackServiceTest {
    @TempDir Path directory;
    private Player player;
    private PackService service;
    private ResourcePackInfo own;
    private ServerConnection backend;
    private final java.util.ArrayList<Player> retries = new java.util.ArrayList<>();
    @BeforeEach void setup() throws Exception {
        var proxy = mock(ProxyServer.class);
        var builder = mock(ResourcePackInfo.Builder.class, RETURNS_SELF);
        own = mock(ResourcePackInfo.class);
        when(own.getId()).thenReturn(PackService.PACK_ID);
        when(builder.build()).thenReturn(own);
        when(proxy.createResourcePackBuilder(anyString())).thenReturn(builder);
        Properties p = new Properties(); p.setProperty("pack.url", "https://example.com/cinema.zip");
        service = new PackService(proxy, CinemaConfig.from(p),
                new ClientPolicy(ProtocolVersion.MINIMUM_VERSION, ProtocolVersion.MAXIMUM_VERSION), directory, retries::add);
        when(own.getHash()).thenReturn(java.util.HexFormat.of().parseHex(service.hash()));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isActive()).thenReturn(true);
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_3);
        backend = mock(ServerConnection.class);
        when(backend.getPlayer()).thenReturn(player);
    }
    @Test void neverOffersPackToOldClientOrDisconnectsIt() {
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_2);
        service.offer(player);
        verify(player, never()).sendResourcePackOffer(any());
        verify(player, never()).disconnect(any());
    }
    @Test void buildsAutomaticOfferFromPlayerHostnameAndPackDigest() throws Exception {
        var proxy = mock(ProxyServer.class);
        var builder = mock(ResourcePackInfo.Builder.class, RETURNS_SELF);
        when(proxy.createResourcePackBuilder(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(own);
        var automatic = new PackService(proxy, CinemaConfig.from(new Properties()),
                new ClientPolicy(ProtocolVersion.MINIMUM_VERSION, ProtocolVersion.MAXIMUM_VERSION), directory);
        when(player.getVirtualHost()).thenReturn(Optional.of(InetSocketAddress.createUnresolved("play.example.org", 25565)));
        automatic.offer(player);
        verify(proxy).createResourcePackBuilder("http://play.example.org:8124/crown-cinema.zip?v=" + automatic.hash());
        verify(builder).setHash(java.util.HexFormat.of().parseHex(automatic.hash()));
        verify(player).sendResourcePackOffer(own);
    }
    @Test void offersOnlyOncePerConnectionAndAllowsNewConnection() {
        service.offer(player); service.offer(player);
        verify(player, times(1)).sendResourcePackOffer(own);
        service.forget(player); service.offer(player);
        verify(player, times(2)).sendResourcePackOffer(own);
    }
    @Test void acceptedAndDownloadedAreNotProofOfAppliedPack() throws Exception {
        service.status(new PlayerResourcePackStatusEvent(player, PackService.PACK_ID, PlayerResourcePackStatusEvent.Status.ACCEPTED, own));
        assertFalse(service.ready(player));
        service.status(new PlayerResourcePackStatusEvent(player, PackService.PACK_ID, PlayerResourcePackStatusEvent.Status.DOWNLOADED, own));
        assertFalse(service.ready(player));
        when(player.getAppliedResourcePacks()).thenReturn(List.of(own));
        assertTrue(service.ready(player));
    }
    @Test void remembersAcceptanceAndDeclineAcrossConnectionsAndCanOptBackIn() throws Exception {
        service.offer(player);
        service.status(new PlayerResourcePackStatusEvent(player, PackService.PACK_ID, PlayerResourcePackStatusEvent.Status.ACCEPTED, own));
        assertEquals(PackPreferences.Choice.ON, new PackPreferences(directory).get(player.getUniqueId()));
        service.status(new PlayerResourcePackStatusEvent(player, PackService.PACK_ID, PlayerResourcePackStatusEvent.Status.DECLINED, own));
        service.forget(player);
        service.offer(player);
        verify(player, times(1)).sendResourcePackOffer(own);
        assertEquals(PackPreferences.Choice.OFF, new PackPreferences(directory).get(player.getUniqueId()));
        service.preference(player, PackPreferences.Choice.ON);
        verify(player, times(2)).sendResourcePackOffer(own);
    }
    @Test void optingOutRemovesOnlyTheCinemaPack() throws Exception {
        when(player.getAppliedResourcePacks()).thenReturn(List.of(own));
        service.preference(player, PackPreferences.Choice.OFF);
        verify(player).removeResourcePacks(PackService.PACK_ID);
        assertEquals(PackPreferences.Choice.OFF, new PackPreferences(directory).get(player.getUniqueId()));
    }
    @Test void backendClearAllPassesThroughAndOnlyRequestsOurPackAgain() {
        ResourcePackInfo other = pack(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
        ResourcePackInfo pending = pack(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
        ResourcePackInfo anotherPlugin = pack(ResourcePackInfo.Origin.PLUGIN_ON_PROXY);
        when(player.getAppliedResourcePacks()).thenReturn(List.of(own, other, anotherPlugin));
        when(player.getPendingResourcePacks()).thenReturn(List.of(pending));
        var event = new ServerResourcePackRemoveEvent(null, backend);
        service.protect(event);
        assertTrue(event.getResult().isAllowed());
        verify(player, never()).removeResourcePacks(any(UUID.class));
        assertEquals(List.of(player), retries);
    }
    @Test void waitsForBackendCompletionBeforeOfferingCinemaAndDoesNotChangeBackendPreference() throws Exception {
        ResourcePackInfo nexo = pack(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
        service.backendOffer(new ServerResourcePackSendEvent(nexo, backend));
        service.offer(player);
        verify(player, never()).sendResourcePackOffer(any());
        service.status(new PlayerResourcePackStatusEvent(player, nexo.getId(), PlayerResourcePackStatusEvent.Status.ACCEPTED, nexo));
        service.offer(player);
        verify(player, never()).sendResourcePackOffer(any());
        service.status(new PlayerResourcePackStatusEvent(player, nexo.getId(), PlayerResourcePackStatusEvent.Status.SUCCESSFUL, nexo));
        service.offer(player);
        verify(player).sendResourcePackOffer(own);
        assertEquals(PackPreferences.Choice.ASK, service.preference(player));
        assertTrue(service.backendStatusOf(player).contains("SUCCESSFUL"));
    }
    @Test void backendFailureDoesNotOptPlayerOutOfCinema() throws Exception {
        ResourcePackInfo nexo = pack(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
        service.backendOffer(new ServerResourcePackSendEvent(nexo, backend));
        service.status(new PlayerResourcePackStatusEvent(player, nexo.getId(), PlayerResourcePackStatusEvent.Status.DECLINED, nexo));
        assertEquals(PackPreferences.Choice.ASK, service.preference(player));
        assertTrue(service.backendStatusOf(player).contains("DECLINED"));
        service.offer(player); verify(player).sendResourcePackOffer(own);
    }
    @Test void laterBackendReloadFailureRemainsVisibleWithoutQueuedPackInfo() throws Exception {
        ResourcePackInfo nexo = pack(ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
        service.backendOffer(new ServerResourcePackSendEvent(nexo, backend));
        service.status(new PlayerResourcePackStatusEvent(player, nexo.getId(), PlayerResourcePackStatusEvent.Status.SUCCESSFUL, nexo));
        service.status(new PlayerResourcePackStatusEvent(player, nexo.getId(), PlayerResourcePackStatusEvent.Status.FAILED_RELOAD, null));
        assertTrue(service.backendStatusOf(player).contains("FAILED_RELOAD"));
        assertEquals(PackPreferences.Choice.ASK, service.preference(player));
    }
    @Test void clearBeforeCinemaAcceptedAllowsFreshOffer() {
        service.offer(player);
        var clear = new ServerResourcePackRemoveEvent(null, backend);
        service.protect(clear);
        assertTrue(clear.getResult().isAllowed());
        service.offer(player); verify(player, times(2)).sendResourcePackOffer(own);
    }
    @Test void anotherPluginsDeniedClearIsLeftUntouched() {
        var clear = new ServerResourcePackRemoveEvent(null, backend);
        clear.setResult(com.velocitypowered.api.event.ResultedEvent.GenericResult.denied());
        service.protect(clear);
        assertTrue(retries.isEmpty());
        assertFalse(clear.getResult().isAllowed());
    }
    @Test void specificRemovalOfOtherPackIsUnaffected() {
        when(player.getAppliedResourcePacks()).thenReturn(List.of(own));
        var event = new ServerResourcePackRemoveEvent(UUID.randomUUID(), backend);
        service.protect(event);
        assertTrue(event.getResult().isAllowed());
        var ownRemoval = new ServerResourcePackRemoveEvent(PackService.PACK_ID, backend);
        service.protect(ownRemoval);
        assertFalse(ownRemoval.getResult().isAllowed());
    }
    @Test void hotUpdateWaitsForTheNewHashAndNeverRemovesBackendPacks() throws Exception {
        var old = own; when(player.getAppliedResourcePacks()).thenReturn(List.of(old)); assertTrue(service.ready(player));
        Path file = directory.resolve("new.zip"); java.nio.file.Files.writeString(file, "new resource pack");
        var next = PackService.Payload.load(file); assertTrue(service.install(next)); assertFalse(service.ready(player));
        service.offer(player); verify(player).removeResourcePacks(PackService.PACK_ID); verify(player).sendResourcePackOffer(own);
        service.status(new PlayerResourcePackStatusEvent(player, PackService.PACK_ID, PlayerResourcePackStatusEvent.Status.SUCCESSFUL, old));
        assertFalse(service.ready(player), "A stale successful response must not start a new video");
        var updated = mock(ResourcePackInfo.class); when(updated.getId()).thenReturn(PackService.PACK_ID); when(updated.getHash()).thenReturn(next.digest());
        when(player.getAppliedResourcePacks()).thenReturn(List.of(updated)); assertTrue(service.ready(player));
        assertNotNull(service.download(next.hash())); assertFalse(service.install(next));
    }
    private ResourcePackInfo pack(ResourcePackInfo.Origin origin) {
        ResourcePackInfo pack = mock(ResourcePackInfo.class);
        when(pack.getId()).thenReturn(UUID.randomUUID()); when(pack.getOrigin()).thenReturn(origin);
        return pack;
    }
}
