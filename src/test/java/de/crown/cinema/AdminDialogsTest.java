package de.crown.cinema;

import java.nio.file.*;
import java.util.List;
import net.kyori.adventure.nbt.TagStringIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminDialogsTest {
    @Test void exportsTheActualVanillaDialogForOfficialCodecValidation() throws Exception {
        var dialog = AdminDialogs.dialog("Crown Cinema", "Choose a video", List.of(new AdminDialogs.Button("Intro", "/crowncinema video use intro.mp4")));
        assertEquals("minecraft:multi_action", dialog.getString("type"));
        assertFalse(dialog.getBoolean("pause")); assertTrue(dialog.getBoolean("can_close_with_escape"));
        Path output = Path.of("build/dialog-validation.snbt"); Files.createDirectories(output.getParent());
        Files.writeString(output, TagStringIO.tagStringIO().asString(dialog));
    }
}
