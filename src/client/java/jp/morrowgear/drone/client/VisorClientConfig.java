package jp.morrowgear.drone.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

final class VisorClientConfig {
	private static VisorClientConfig shared;
	static VisorClientConfig current() { if (shared == null) shared = load(); return shared; }
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("morrowgear-visor.properties");

	boolean enabled = true;
	int proximityRange = 48;

	static VisorClientConfig load() {
		VisorClientConfig config = new VisorClientConfig();
		if (!Files.isRegularFile(FILE)) return config;
		Properties values = new Properties();
		try (InputStream input = Files.newInputStream(FILE)) {
			values.load(input);
			config.enabled = Boolean.parseBoolean(values.getProperty("enabled", "true"));
			config.proximityRange = normalizeRange(Integer.parseInt(values.getProperty("proximityRange", "48")));
		} catch (IOException | NumberFormatException ignored) {
			// Invalid local preferences fall back to unobtrusive defaults.
		}
		return config;
	}

	void save() {
		Properties values = new Properties();
		values.setProperty("enabled", Boolean.toString(enabled));
		values.setProperty("proximityRange", Integer.toString(proximityRange));
		try {
			Files.createDirectories(FILE.getParent());
			try (OutputStream output = Files.newOutputStream(FILE)) {
				values.store(output, "Morrowgear Tactical Visor client preferences");
			}
		} catch (IOException ignored) {
			// HUD operation is not allowed to fail because preferences cannot be persisted.
		}
	}

	void cycleRange() {
		proximityRange = switch (proximityRange) {
			case 24 -> 48;
			case 48 -> 96;
			case 96 -> 0;
			default -> 24;
		};
		save();
	}

	private static int normalizeRange(int value) {
		return value == 24 || value == 48 || value == 96 ? value : value == 0 ? 0 : 48;
	}
}
