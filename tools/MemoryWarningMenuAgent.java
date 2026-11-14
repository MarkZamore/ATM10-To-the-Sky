import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/// Records the actual NeoForge menu state; does not replace screens or run the policy itself.
public class MemoryWarningMenuAgent {
    public static void premain(String args, Instrumentation instrumentation) {
        var worker = new Thread(() -> observe(instrumentation), "Sky-memory-menu-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void observe(Instrumentation instrumentation) {
        Object client = null;
        try {
            Path game = Path.of(System.getProperty("lanminecraft.steamqa.game")).toAbsolutePath().normalize();
            Path evidence = Path.of(System.getProperty("lanminecraft.steamqa.evidence")).toAbsolutePath().normalize();
            Path run = Path.of(System.getProperty("lanminecraft.steamqa.run")).toAbsolutePath().normalize();
            if (!game.startsWith(run) || !evidence.startsWith(run) || !Files.isRegularFile(run.resolve("RUN.json"))) {
                throw new IllegalArgumentException("Use an owned Sky game fixture");
            }
            Path receipt = evidence.resolve("memory-warning-menu.txt");
            boolean expectedWarning = Boolean.getBoolean("lanminecraft.sky.warningExpected");
            Class<?> minecraft = null;
            var deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (minecraft == null) {
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        if (loaded.getName().equals("net.minecraft.client.Minecraft")) {
                            minecraft = loaded;
                            break;
                        }
                    }
                }
                if (client == null && minecraft != null) {
                    client = minecraft.getMethod("getInstance").invoke(null);
                }
                if (client != null) {
                    Object gameClient = client;
                    var check = new FutureTask<>(() -> sample(gameClient, expectedWarning));
                    ((Executor) client).execute(check);
                    String result = check.get(90, TimeUnit.SECONDS);
                    if (result != null) {
                        Files.writeString(receipt, result + "\n");
                        System.out.println(result);
                        return;
                    }
                }
                Thread.sleep(500);
            }
            Files.writeString(receipt, "FAIL: Minecraft did not finish loading the first screen.\n");
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            if (client != null) {
                Object stoppingClient = client;
                ((Executor) client).execute(() -> {
                    try {
                        stoppingClient.getClass().getMethod("stop").invoke(stoppingClient);
                    } catch (ReflectiveOperationException ex) {
                        ex.printStackTrace();
                    }
                });
            }
        }
    }

    private static String sample(Object client, boolean expectedWarning) throws Exception {
        Class<?> minecraft = client.getClass();
        Object screen = minecraft.getField("screen").get(client);
        if (screen == null || minecraft.getMethod("getOverlay").invoke(client) != null) return null;
        String screenName = screen.getClass().getName();
        boolean warning = screenName.equals("com.memorysettings.MemoryErrorScreen");
        if (!warning && !Boolean.TRUE.equals(minecraft.getMethod("isGameLoadFinished").invoke(client))) return null;

        ClassLoader loader = minecraft.getClassLoader();
        Class<?> config = Class.forName("com.memorysettings.config.CommonConfiguration", true, loader);
        Object holder = config.getField("config").get(null);
        Object settings = holder.getClass().getMethod("getCommonConfig").invoke(holder);
        boolean disabled = config.getField("disableWarnings").getBoolean(settings);
        int threshold = config.getField("maximumClient").getInt(settings);
        long heapMiB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        Object languages = minecraft.getMethod("getLanguageManager").invoke(client);
        String language = (String) languages.getClass().getMethod("getSelected").invoke(languages);
        String expectedLanguage = System.getProperty("lanminecraft.sky.languageExpected", "");
        boolean pass = warning == expectedWarning && disabled != expectedWarning && heapMiB > threshold
            && (expectedLanguage.isEmpty() || expectedLanguage.equals(language));
        return (pass ? "PASS" : "FAIL") + " screen=" + screenName + " warning=" + warning
            + " disableWarnings=" + disabled + " heapMiB=" + heapMiB + " thresholdMiB=" + threshold
            + " language=" + language;
    }
}
