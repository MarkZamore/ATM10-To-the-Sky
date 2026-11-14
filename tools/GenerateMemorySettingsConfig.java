import com.google.gson.GsonBuilder;
import com.memorysettings.Memory;
import com.memorysettings.config.CommonConfiguration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/// Generates and verifies the stock client config with the early recommendation suppressed.
public class GenerateMemorySettingsConfig {
    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[0]).toAbsolutePath();
        Path config = game.resolve("config/memorysettings.json");
        if (Files.exists(config) || Files.exists(game.resolve("mods"))) {
            throw new IllegalStateException("Use an empty probe directory");
        }
        Files.createDirectories(config.getParent());
        // A standalone JVM has no launch target. Select the same distribution
        // that NeoForge would set when starting the actual game.
        var distribution = FMLLoader.class.getDeclaredField("dist");
        distribution.setAccessible(true);
        distribution.set(null, Dist.CLIENT);
        if (FMLEnvironment.dist != Dist.CLIENT || !"64".equals(System.getProperty("sun.arch.data.model"))) {
            throw new AssertionError("Expected the normal 64-bit client path");
        }
        FMLPaths.loadAbsolutePaths(game);

        var nativeDefaults = new CommonConfiguration();
        var stock = nativeDefaults.serialize();
        nativeDefaults.minimumClient = 0;
        var seed = nativeDefaults.serialize();
        var restoreMinimum = seed.deepCopy();
        restoreMinimum.add("minimumClient", stock.get("minimumClient"));
        if (!stock.equals(restoreMinimum) || nativeDefaults.disableWarnings) {
            throw new AssertionError("Expected to change only the recommendation floor");
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(seed) + "\n";
        Files.writeString(config, json);
        var loaded = CommonConfiguration.config.getCommonConfig();
        if (loaded.minimumClient != 0 || loaded.disableWarnings || !loaded.serialize().equals(seed)) {
            throw new AssertionError("Cupboard did not load the exact pack config");
        }
        Memory.systemMemory = 16384;
        Memory.heapSetting = 1024;
        // A regression reaching the native dialog requires LWJGL; it is omitted
        // from the isolated probe classpath so the call fails without a window.
        Memory.doEarlyWarnings();
        loaded.minimumClient = stock.getAsJsonObject("minimumClient").get("minimumClient").getAsInt();
        expectDialog("stock recommendation");
        loaded.minimumClient = 0;
        System.setProperty("sun.arch.data.model", "32");
        try { expectDialog("32-bit Java warning"); }
        finally { System.setProperty("sun.arch.data.model", "64"); }
        Files.writeString(Path.of(args[1]), json);
        System.out.println("PASS: early recommendation skipped at 1024 MiB and 32-bit Java warning preserved");
    }

    private static void expectDialog(String reason) {
        try {
            Memory.doEarlyWarnings();
        } catch (NoClassDefFoundError expected) {
            if ("org/lwjgl/util/tinyfd/TinyFileDialogs".equals(expected.getMessage())) return;
            throw expected;
        }
        throw new AssertionError("Expected to reach the native dialog for " + reason);
    }
}
