package hu.jgj52.SulfurBlocks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.sulfurmc.Sulfur.Utils.Plugin;
import hu.jgj52.SulfurBlocks.Listeners.BlockBreakListener;
import net.minestom.server.instance.block.Block;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SulfurBlocks implements Plugin {
    public static final Map<Integer, JsonObject> blocks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        new BlockBreakListener();
        Gson gson = new Gson();
        for (Block block : Block.values()) {
            try (InputStream is = SulfurBlocks.class.getResourceAsStream("/blocks/" + block.name().toLowerCase().replace("minecraft:", "") + ".json")) {
                if (is == null) continue;
                try (InputStreamReader isr = new InputStreamReader(is)) {
                    JsonObject b = gson.fromJson(isr, JsonObject.class);
                    blocks.put(block.id(), b);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onDisable() {

    }
}
