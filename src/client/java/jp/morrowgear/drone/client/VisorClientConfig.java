package jp.morrowgear.drone.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

public final class VisorClientConfig {
	private static VisorClientConfig shared;
	public static VisorClientConfig current() { if (shared == null) shared = load(); return shared; }
	private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("morrowgear-visor.properties"); }
	public enum Density { MINIMAL, STANDARD }
	public record Presentation(Density density, int backgroundPercent, boolean large) {
		public static final Presentation DEFAULT = new Presentation(Density.STANDARD, 100, false);
		public Presentation {
			density = density == null ? Density.STANDARD : density;
			backgroundPercent = Math.max(60, Math.min(120, backgroundPercent));
		}
		public float hudScale() { return large ? 1.5f : 1.0f; }
		public float effectiveHudScale(int width, int height) {
			return large && width >= 1440 && height >= 810 ? 1.5f : 1.0f;
		}
		public int panelAlpha(int baseColor) {
			int alpha = Math.min(255, Math.round((baseColor >>> 24) * backgroundPercent / 100f));
			return (baseColor & 0x00FFFFFF) | alpha << 24;
		}
		public int ordinaryMarkerBudget(int base) {
			return Math.max(0, density == Density.MINIMAL ? base / 2 : base);
		}
		public int ordinaryLabelBudget(int base) {
			return Math.max(0, density == Density.MINIMAL ? base / 3 : base);
		}
	}
	private Presentation presentation = Presentation.DEFAULT;
	public Presentation presentation() { return presentation; }
	public Density density() { return presentation.density(); }
	public float hudScale() { return presentation.hudScale(); }
	public float hudScale(int physicalWidth, int physicalHeight) { return presentation.effectiveHudScale(physicalWidth, physicalHeight); }
	public float effectiveHudScale(int width, int height) { return presentation.effectiveHudScale(width, height); }
	public int panelAlpha(int baseColor) { return presentation.panelAlpha(baseColor); }
	public int ordinaryMarkerBudget(int base) { return presentation.ordinaryMarkerBudget(base); }
	public int ordinaryLabelBudget(int base) { return presentation.ordinaryLabelBudget(base); }
	public boolean emergencySummaryEnabled() { return true; }

	boolean enabled = true;
	int proximityRange = 48;

	static VisorClientConfig load() {
		VisorClientConfig config = new VisorClientConfig();
		Path FILE = file();
		if (!Files.isRegularFile(FILE)) return config;
		Properties values = new Properties();
		try (InputStream input = Files.newInputStream(FILE)) {
			values.load(input);
			config.enabled = Boolean.parseBoolean(values.getProperty("enabled", "true"));
			config.proximityRange = normalizeRange(Integer.parseInt(values.getProperty("proximityRange", "48")));
			config.presentation = parsePresentation(values);
		} catch (IOException | NumberFormatException ignored) {
			// Invalid local preferences fall back to unobtrusive defaults.
		}
		return config;
	}

	void save() {
		persist(file(), presentation);
	}

	public boolean savePresentation(Presentation next) {
		if (!persist(file(), next)) return false;
		presentation = next;
		return true;
	}

	static Presentation parsePresentation(Properties values) {
		Density density;
		try { density = Density.valueOf(values.getProperty("density", "STANDARD")); }
		catch (IllegalArgumentException ignored) { density = Density.STANDARD; }
		int background;
		try { background = Integer.parseInt(values.getProperty("backgroundPercent", "100")); }
		catch (NumberFormatException ignored) { background = 100; }
		return new Presentation(density, background, Boolean.parseBoolean(values.getProperty("large", "false")));
	}

	boolean persist(Path file, Presentation next) {
		Properties values = new Properties();
		values.setProperty("enabled", Boolean.toString(enabled));
		values.setProperty("proximityRange", Integer.toString(proximityRange));
		values.setProperty("density", next.density().name());
		values.setProperty("backgroundPercent", Integer.toString(next.backgroundPercent()));
		values.setProperty("large", Boolean.toString(next.large()));
		Path temporary = null;
		try {
			Files.createDirectories(file.getParent());
			temporary = Files.createTempFile(file.getParent(), "morrowgear-visor-", ".tmp");
			try (OutputStream output = Files.newOutputStream(temporary)) {
				values.store(output, "Morrowgear Tactical Visor client preferences");
			}
			try { Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
			catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException | SecurityException ignored) {
			return false;
		} finally {
			if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException ignored) { }
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
