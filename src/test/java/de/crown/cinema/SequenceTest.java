package de.crown.cinema;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SequenceTest {
    @Test void playbackUsesElapsedTimeAndSkipsLateFrames() {
        var sequence = new Sequence(72, 12, 0xe100);
        assertEquals(0, sequence.frameAt(0));
        assertEquals(36, sequence.frameAt(3_000_000_000L));
        assertEquals(72, sequence.frameAt(6_000_000_000L));
        assertEquals(72, sequence.frameAt(90_000_000_000L));
        assertEquals(6, sequence.duration().toSeconds());
        assertThrows(IllegalArgumentException.class, () -> sequence.glyph(72));
    }
    @Test void invalidSequenceCannotScheduleUnboundedPlayback() {
        assertThrows(IllegalArgumentException.class, () -> new Sequence(500, 1, 0xe100));
        assertThrows(IllegalArgumentException.class, () -> new Sequence(72, 0, 0xe100));
    }
    @Test void tiledFrameKeepsRowsAlignedWithoutDrawingOversizedGlyphs() {
        var sequence = new Sequence(120,20,0xe100,4,3,0xe000,0xe001);
        int[] glyphs=sequence.glyph(119).codePoints().toArray();
        assertEquals(26,glyphs.length);
        assertEquals(0xe100+119*12,glyphs[0]);
        assertEquals(0xe000,glyphs[1]);
        assertEquals(0xe001,glyphs[8]);
        assertEquals(0xe001,glyphs[17]);
        assertEquals(0xe100+119*12+11,glyphs[24]);
        assertEquals(6,sequence.duration().toSeconds());
    }
    @Test void layoutCannotOverlapControlGlyphsOrOverflowPrivateUseArea() {
        assertThrows(IllegalArgumentException.class,()->new Sequence(120,20,0xe100,4,3,0xe100,0xe001));
        assertThrows(IllegalArgumentException.class,()->new Sequence(512,20,0xe100,8,8,0xe000,0xe001));
    }
}
