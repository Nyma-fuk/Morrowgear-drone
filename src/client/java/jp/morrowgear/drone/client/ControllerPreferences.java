package jp.morrowgear.drone.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Controller-only preferences, separate from the wearable visor. */
final class ControllerPreferences {
    private static ControllerPreferences shared;
    int pixelScale;
    int markerSize = 1;

    static ControllerPreferences current() {
        if (shared == null) shared = load(file());
        return shared;
    }
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("morrowgear-controller.properties");
    }
    static ControllerPreferences load(Path file) {
        ControllerPreferences result = new ControllerPreferences();
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
            result.pixelScale = integer(values.getProperty("pixelScale"), 0, 4, 0);
            result.markerSize = integer(values.getProperty("markerSize"), 0, 2, 1);
        } catch (IOException | IllegalArgumentException ignored) {
            // Invalid or missing preferences leave the established default layout unchanged.
        }
        return result;
    }
    private static int integer(String value, int min, int max, int fallback) {
        return ControllerNumericPolicy.parse(value, min, max).orElse(fallback);
    }
    boolean save() { return save(file()); }
    boolean save(Path file) {
        Properties values = new Properties();
        values.setProperty("pixelScale", Integer.toString(pixelScale));
        values.setProperty("markerSize", Integer.toString(markerSize));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var output = Files.newOutputStream(file)) { values.store(output, "Morrowgear controller display"); }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
    int markerRadius() { return switch (markerSize) { case 0 -> 2; case 2 -> 5; default -> 3; }; }
    int markerHitRadius() { return markerRadius() + 7; }
    int effectivePixelScale(int maximum) {
        return Math.clamp(pixelScale == 0 ? 2 : pixelScale, 1, Math.clamp(maximum, 1, 4));
    }
    boolean stepPixelScale(int direction, int maximum) {
        int effective = effectivePixelScale(maximum);
        int next = Math.clamp(effective + Integer.signum(direction), 1, Math.clamp(maximum, 1, 4));
        if (next == effective) return false;
        pixelScale = next;
        return true;
    }
    boolean automaticScale(boolean enabled, int maximum) {
        int next = enabled ? 0 : effectivePixelScale(maximum);
        if (next == pixelScale) return false;
        pixelScale = next;
        return true;
    }
    boolean stepMarkerSize(int direction) {
        int next = Math.clamp(markerSize + Integer.signum(direction), 0, 2);
        if (next == markerSize) return false;
        markerSize = next;
        return true;
    }
}
