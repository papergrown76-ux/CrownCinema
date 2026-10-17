package de.crown.cinema;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

record Sequence(int frames, int fps, int firstGlyph, int columns, int rows, int trackingGlyph, int rowResetGlyph) {
    Sequence(int frames, int fps, int firstGlyph) { this(frames,fps,firstGlyph,1,1,0,0); }
    Sequence {
        if (frames < 2 || frames > 512 || fps < 1 || fps > 20 || (double) frames / fps > 30
                || columns < 1 || columns > 8 || rows < 1 || rows > 8
                || firstGlyph < 0xe000 || firstGlyph + frames*columns*rows > 0xf8ff
                || columns*rows > 1 && (trackingGlyph < 0xe000 || rowResetGlyph < 0xe000
                    || trackingGlyph >= firstGlyph || rowResetGlyph >= firstGlyph || trackingGlyph == rowResetGlyph))
            throw new IllegalArgumentException("Invalid cinema sequence");
    }

    static Sequence load() throws IOException {
        try (InputStream in = Sequence.class.getResourceAsStream("/cinema/sequence.properties")) {
            if (in == null) throw new IOException("Missing bundled neutral demo assets; rebuild the plugin from a complete checkout.");
            return load(in);
        }
    }

    static Sequence load(InputStream in) throws IOException {
        Properties p = new Properties(); p.load(in);
        return new Sequence(Integer.parseInt(p.getProperty("frames")), Integer.parseInt(p.getProperty("fps")),
                Integer.parseInt(p.getProperty("first-glyph")),Integer.parseInt(p.getProperty("columns","1")),
                Integer.parseInt(p.getProperty("rows","1")),Integer.parseInt(p.getProperty("tracking-glyph","0")),
                Integer.parseInt(p.getProperty("row-reset-glyph","0")));
    }

    Duration duration() { return Duration.ofNanos(1_000_000_000L * frames / fps); }
    int frameAt(long elapsedNanos) { return (int) Math.min(frames, Math.max(0, elapsedNanos) / 1_000_000_000.0 * fps); }
    String glyph(int frame) {
        if (frame < 0 || frame >= frames) throw new IllegalArgumentException("Frame out of range");
        if (columns*rows == 1) return Character.toString(firstGlyph + frame);
        StringBuilder result = new StringBuilder();
        for (int row=0;row<rows;row++) {
            for (int col=0;col<columns;col++) {
                result.appendCodePoint(firstGlyph+frame*columns*rows+row*columns+col);
                result.appendCodePoint(trackingGlyph);
            }
            if (row<rows-1) result.appendCodePoint(rowResetGlyph);
        }
        return result.toString();
    }
}
