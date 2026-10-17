package de.crown.cinema.bridge;

import java.io.*;
import java.util.UUID;

/** Identical bounded wire format compiled into both plugins. */
public record BridgeMessage(Action action, UUID token, int seconds) {
    public static final String CHANNEL = "crowncinema:bridge";
    public enum Action { START, READY, REJECT, STOP, ENDED }
    public BridgeMessage {
        if (action == null || token == null || seconds < 0 || seconds > 30) throw new IllegalArgumentException("Invalid bridge message");
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeByte(1); out.writeByte(action.ordinal());
            out.writeLong(token.getMostSignificantBits()); out.writeLong(token.getLeastSignificantBits());
            out.writeByte(seconds);
            return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static BridgeMessage decode(byte[] data) throws IOException {
        if (data.length != 19) throw new IOException("Invalid bridge message size");
        var in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != 1) throw new IOException("Unsupported bridge protocol");
        int action = in.readUnsignedByte();
        if (action >= Action.values().length) throw new IOException("Invalid bridge action");
        UUID token = new UUID(in.readLong(), in.readLong());
        int seconds = in.readUnsignedByte();
        if (seconds > 30) throw new IOException("Invalid bridge timeout");
        return new BridgeMessage(Action.values()[action], token, seconds);
    }
}
