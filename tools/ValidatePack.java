import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.client.renderer.texture.DynamicAtlasTree;
import javax.imageio.ImageIO;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;

/** Parse our real pack with the actual, unmodified Minecraft 26.3 codecs. */
public class ValidatePack {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try (var zip = new ZipFile(args[0])) {
            JsonObject metadata = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var pack = PackMetadataSection.CLIENT_TYPE.codec().parse(JsonOps.INSTANCE, metadata.get("pack")).getOrThrow();
            if (!pack.supportedFormats().isValueInRange(PackFormat.of(97,1))) throw new AssertionError("26.3 is not in the supported pack format range");
            int count=0;
            for (var entry : zip.stream().filter(e -> e.getName().startsWith("assets/crowncinema/font/") && e.getName().endsWith(".json")).toList()) {
            JsonObject font = JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var provider : font.getAsJsonArray("providers")) {
                var definition = GlyphProviderDefinition.MAP_CODEC.codec().parse(JsonOps.INSTANCE, provider).getOrThrow();
                if (!(definition instanceof BitmapProvider.Definition bitmap)) continue;
                for (int[] row : bitmap.codepointGrid()) for (int glyph : row) if (glyph != 0) count++;
                String texture = "assets/crowncinema/textures/" + bitmap.file().getPath();
                if (zip.getEntry(texture) == null) throw new AssertionError("Missing bitmap texture " + texture);
                var image = ImageIO.read(zip.getInputStream(zip.getEntry(texture)));
                int width = image.getWidth()/bitmap.codepointGrid()[0].length;
                int height = image.getHeight()/bitmap.codepointGrid().length;
                // Use the real client allocator, without creating a GPU context.
                // Oversized whole-frame glyphs were accepted by the metadata codec
                // but could never be stitched into Minecraft's fixed 256px atlas.
                if (new DynamicAtlasTree(0,0,256,256).insert(width,height,1) == null)
                    throw new AssertionError("Glyph does not fit the real Minecraft font atlas: " + width + "x" + height);
            }
            }
            int expected = Integer.parseInt(args[1]);
            if (count != expected) throw new AssertionError("Expected " + expected + " real frame glyphs, found " + count);
            System.out.println("PASS: Minecraft 26.3 codecs and actual font-atlas allocator accept all " + count + " tile glyphs");
        }
    }
}
