import java.nio.file.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.dialog.Dialog;

/** Actual vanilla codec validation; this does not render the Minecraft UI. */
public class ValidateDialog {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var dialog = Dialog.DIRECT_CODEC.parse(NbtOps.INSTANCE, TagParser.parseCompoundFully(Files.readString(Path.of(args[0])))).getOrThrow();
        int expected = args.length > 1 ? Integer.parseInt(args[1]) : 0x8f;
        net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (type.id().getPath().equals("show_dialog") && id != expected) throw new AssertionError("Unexpected dialog packet id: " + id);
        });
        System.out.println("PASS: unmodified Minecraft dialog codec accepts " + dialog.getClass().getSimpleName());
    }
}
