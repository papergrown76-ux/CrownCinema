package de.crown.cinema;

import com.velocitypowered.proxy.protocol.packet.ClientboundSoundEntityPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStopSoundPacket;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;

/** Sound transport for the pinned Velocity/LimboAPI combination. */
final class CinemaAudio {
    static final Key SOUND = Key.key("crowncinema", "video");
    // LimboAPI bfef579's JoinGamePacket assigns the local player entity ID 1.
    // The protocol smoke test checks this against the actual login packet.
    private static final int LIMBO_PLAYER_ID = 1;

    private CinemaAudio() { }

    static void play(LimboPlayer player, Key sound) {
        // Velocity's one-argument Audience.playSound is a no-op. Its emitter
        // overload also requires a backend connection, absent in our Limbo.
        // Use Velocity's own version-aware encoder through LimboAPI instead.
        player.writePacketAndFlush(new ClientboundSoundEntityPacket(
                Sound.sound(sound, Sound.Source.MASTER, 0.8f, 1), null, LIMBO_PLAYER_ID));
    }

    static void stop(LimboPlayer player, Key sound) {
        player.writePacketAndFlush(new ClientboundStopSoundPacket(SoundStop.namedOnSource(sound, Sound.Source.MASTER)));
    }
}
