import com.google.gson.JsonObject;
import com.memorysettings.config.CommonConfiguration;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/// Exercises the shipped script in the pack's Rhino and Memory Settings dependencies.
public class MemoryWarningProbe {
    public record Binding(Context context, Scriptable scope) {
        public Object loadClass(String name) throws ClassNotFoundException {
            if (!name.equals("com.memorysettings.config.CommonConfiguration")) {
                throw new IllegalArgumentException(name);
            }
            return new NativeJavaClass(context, scope, Class.forName(name));
        }
    }

    public static void main(String[] args) throws Exception {
        Path game = Path.of("").toAbsolutePath();
        Path config = game.resolve("config/memorysettings.json");
        if (Files.exists(game.resolve("mods")) || Files.exists(config)) {
            throw new IllegalStateException("Use an empty probe directory");
        }
        Files.createDirectories(config.getParent());
        Files.copy(Path.of(args[1]), config);
        FMLPaths.loadAbsolutePaths(game);
        var memory = CommonConfiguration.config.getCommonConfig();
        require(!memory.disableWarnings && memory.maximumClient == 8500, "Expected the warning enabled at 8500 MB");
        byte[] bytes = Files.readAllBytes(config);
        JsonObject before = memory.serialize();
        Context context = new ContextFactory().enter();
        Scriptable scope = context.initStandardObjects();
        ScriptableObject.putProperty(scope, "Java", context.javaToJS(new Binding(context, scope), scope), context);

        Path script = Path.of(args[0]);
        String code = Files.readString(script);
        context.evaluateString(scope, code, script.getFileName().toString(), 1, null);
        JsonObject after = memory.serialize();
        Set<String> changed = new HashSet<>(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        require(memory.disableWarnings && changed.equals(Set.of("disableWarnings")), "Unexpected native settings change");

        context.evaluateString(scope, code, script.getFileName().toString(), 1, null);
        require(after.equals(memory.serialize()), "A second client script load changed configuration");
        require(java.util.Arrays.equals(bytes, Files.readAllBytes(config)), "Client script rewrote the config file");
        System.out.println("{\"exactNativeRhinoAndMemorySettings\":true,\"onlyDisableWarningsChanged\":true,"
            + "\"maximumClientMiB\":8500,\"unchangedConfigBytes\":true,\"idempotent\":true}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
