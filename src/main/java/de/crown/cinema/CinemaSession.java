package de.crown.cinema;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import net.kyori.adventure.sound.Sound;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

final class CinemaSession implements LimboSessionHandler {
    interface Listener {
        void completed(CinemaSession session, boolean announced);
    }

    private final Key movieFont, movieSound;
    private final Player player;
    private final RegisteredServer previous;
    private final RegisteredServer target;
    private final String displayName;
    private final Sequence sequence;
    private final int endcardSeconds;
    private final Listener listener;
    private final Logger logger;
    private LimboPlayer limboPlayer;
    private Runnable backendStop;
    private boolean hasSpawned;
    private ScheduledFuture<?> ticker;
    private long started;
    private long lastCorrection;
    private int lastFrame = -1;
    private boolean closed;

    CinemaSession(Player player, RegisteredServer previous, RegisteredServer target, String displayName,
                  Sequence sequence, int endcardSeconds, Listener listener, Logger logger) {
        this(player, previous, target, displayName, sequence, endcardSeconds, listener, logger, Key.key("crowncinema:movie"), CinemaAudio.SOUND);
    }

    CinemaSession(Player player, RegisteredServer previous, RegisteredServer target, String displayName,
                  Sequence sequence, int endcardSeconds, Listener listener, Logger logger, Key font, Key sound) {
        movieFont = font; movieSound = sound;
        this.player = player;
        this.previous = previous;
        this.target = target;
        this.displayName = displayName;
        this.sequence = sequence;
        this.endcardSeconds = endcardSeconds;
        this.listener = listener;
        this.logger = logger;
    }

    Player player() { return player; }
    RegisteredServer target() { return target; }
    String displayName() { return displayName; }
    String previousServerName() { return previous.getServerInfo().getName(); }

    @Override public synchronized void onSpawn(Limbo server, LimboPlayer spawned) {
        if (closed) { spawned.disconnect(previous); return; }
        if (hasSpawned) return;
        hasSpawned = true;
        limboPlayer = spawned;
        // This player has already left the backend. No backend movement/damage packets are forged.
        started = System.nanoTime();
        try {
            spawned.sendAbilities((byte) 7, 0, 0); // invulnerable, flying, allowed to fly
            CinemaAudio.play(spawned, movieSound);
            tick(started);
            ticker = spawned.getScheduledExecutor().scheduleAtFixedRate(this::safeTick,
                    1_000_000_000L / sequence.fps(), 1_000_000_000L / sequence.fps(), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) { fail(e); }
    }

    synchronized void backendRequested(Runnable stop) { backendStop = stop; }

    synchronized void onBackendSpawn(ScheduledExecutorService executor) {
        if (closed || hasSpawned || backendStop == null) return;
        hasSpawned = true;
        started = System.nanoTime();
        try {
            player.playSound(Sound.sound(movieSound, Sound.Source.MASTER, 0.8f, 1), Sound.Emitter.self());
            tick(started);
            ticker = executor.scheduleAtFixedRate(this::safeTick,
                    1_000_000_000L / sequence.fps(), 1_000_000_000L / sequence.fps(), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) { fail(e); }
    }

    private void stopSound() {
        if (limboPlayer != null) CinemaAudio.stop(limboPlayer, movieSound);
        else if (hasSpawned) player.stopSound(net.kyori.adventure.sound.SoundStop.namedOnSource(movieSound, Sound.Source.MASTER));
    }

    private synchronized void safeTick() {
        try { tick(System.nanoTime()); }
        catch (RuntimeException e) { fail(e); }
    }

    synchronized void tick(long now) {
        if (closed) return;
        if (!player.isActive()) { cleanup(false); return; }
        long elapsed = Math.max(0, now - started);
        int frame = sequence.frameAt(elapsed);
        if (frame < sequence.frames()) {
            if (frame != lastFrame) {
                player.showTitle(Title.title(Component.text(sequence.glyph(frame)).font(movieFont).color(NamedTextColor.WHITE),
                        Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)));
                lastFrame = frame;
            }
        } else if (elapsed < sequence.duration().plusSeconds(endcardSeconds).toNanos()) {
            if (lastFrame != sequence.frames()) {
                stopSound();
                player.showTitle(Title.title(Component.text(displayName, NamedTextColor.GOLD),
                        Component.text("JOIN NOW", NamedTextColor.GREEN),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(endcardSeconds), Duration.ZERO)));
                lastFrame = sequence.frames();
            }
        } else { finish(true); }
    }

    synchronized void startupTimeout() {
        if (!closed && !hasSpawned) {
            logger.warn("Cinema spawn timed out for {}", player.getUsername());
            if (backendStop != null && player.isActive()) player.sendMessage(Component.text("Animation skipped: no backend confirmation. Check the companion plugin."));
            cleanup(false);
            if (backendStop == null && player.isActive()) player.createConnectionRequest(previous).connectWithIndication();
        }
    }

    synchronized void finish(boolean announced) {
        if (closed) return;
        cleanup(announced);
        if (limboPlayer != null && player.isActive()) {
            try { limboPlayer.disconnect(previous); }
            catch (RuntimeException e) {
                logger.error("Could not leave cinema for {}", player.getUsername(), e);
                player.createConnectionRequest(previous).connectWithIndication();
            }
        }
    }

    /** An external server switch must win over a cinema; never transfer the player back. */
    synchronized void externalSwitch() { cleanup(false); }
    @Override public synchronized void onDisconnect() { cleanup(false); }

    private void cleanup(boolean announced) {
        if (closed) return;
        closed = true;
        if (ticker != null) ticker.cancel(false);
        try {
            if (player.isActive()) {
                player.clearTitle();
                stopSound();
            }
        } catch (RuntimeException e) {
            logger.warn("Could not clear cinema overlay for {}", player.getUsername(), e);
        } finally {
            try { if (backendStop != null) backendStop.run(); }
            finally { listener.completed(this, announced); }
        }
    }

    private void fail(RuntimeException e) {
        logger.error("Cinema playback failed for {}", player.getUsername(), e);
        finish(false);
    }

    private synchronized void correctCamera() {
        long now = System.nanoTime();
        if (!closed && limboPlayer != null && now - lastCorrection >= 200_000_000L) {
            lastCorrection = now;
            limboPlayer.teleport(0.5, 72, 0.5, 180, 0);
        }
    }

    @Override public void onMove(double x, double y, double z) {
        if (Math.abs(x - 0.5) + Math.abs(y - 72) + Math.abs(z - 0.5) > 0.05) correctCamera();
    }
    @Override public void onMove(double x, double y, double z, float yaw, float pitch) {
        onMove(x, y, z); onRotate(yaw, pitch);
    }
    @Override public void onRotate(float yaw, float pitch) {
        if (Math.abs(yaw - 180) > 0.5 || Math.abs(pitch) > 0.5) correctCamera();
    }
}
