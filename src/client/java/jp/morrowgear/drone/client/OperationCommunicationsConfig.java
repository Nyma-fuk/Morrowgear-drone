package jp.morrowgear.drone.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import jp.morrowgear.drone.OperationRadioSettings;
import net.fabricmc.loader.api.FabricLoader;

public final class OperationCommunicationsConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("morrowgear-communications.properties");
    private static OperationRadioSettings settings;
    private OperationCommunicationsConfig() {}

    public static OperationRadioSettings current() {
        if (settings == null) settings = load();
        return settings;
    }

    public static void save(OperationRadioSettings next) {
        settings = next;
        Properties values = new Properties();
        values.setProperty("voice", next.voiceMode().name());
        values.setProperty("volume", Float.toString(next.volume()));
        values.setProperty("subtitles", Boolean.toString(next.subtitles()));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream output = Files.newOutputStream(FILE)) {
                values.store(output, "Morrowgear operation communications");
            }
        } catch (IOException ignored) {
            // A read-only config directory must not interrupt the owner's radio or HUD.
        }
    }

    private static OperationRadioSettings load() {
        if (!Files.isRegularFile(FILE)) return OperationRadioSettings.DEFAULT;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            values.load(input);
            return new OperationRadioSettings(OperationRadioSettings.VoiceMode.valueOf(values.getProperty("voice", "IMPORTANT")),
                    Float.parseFloat(values.getProperty("volume", "0.7")),
                    Boolean.parseBoolean(values.getProperty("subtitles", "true")));
        } catch (IOException | IllegalArgumentException ignored) {
            return OperationRadioSettings.DEFAULT;
        }
    }
}
