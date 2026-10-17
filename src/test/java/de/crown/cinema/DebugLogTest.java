package de.crown.cinema;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DebugLogTest {
    @TempDir Path directory;
    @Test void debugIsOptInPersistentAndRotates() throws Exception {
        var logger = mock(Logger.class); var debug = new DebugLog(directory,logger);
        debug.record("test", "hidden"); assertFalse(Files.exists(directory.resolve("debug.log")));
        debug.set(true); assertTrue(new DebugLog(directory,logger).enabled());
        debug.record("pack.status", "status=SUCCESSFUL\nnot-a-new-event");
        assertTrue(Files.readString(directory.resolve("debug.log")).contains("status=SUCCESSFUL not-a-new-event"));
        Files.write(directory.resolve("debug.log"),new byte[2*1024*1024]);
        debug.record("bridge.message", "action=READY"); assertTrue(Files.exists(directory.resolve("debug.log.1")));
        debug.set(false); String before=Files.readString(directory.resolve("debug.log"));debug.record("test","disabled");
        assertEquals(before, Files.readString(directory.resolve("debug.log")));
    }
}
