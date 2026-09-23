package jp.morrowgear.drone.client;

import java.util.function.ToIntFunction;
import jp.morrowgear.drone.DroneRole;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

final class HmiArt {
	static final int BACKGROUND = 0xFF111416, PANEL = 0xFF1A1E21, RAISED = 0xFF23282B;
	static final int LINE = 0xFF394347, TEXT = 0xFFEDF3F2, MUTED = 0xFFA6B5B8;
	static final int CYAN = 0xFF62D8DF, AMBER = 0xFFFFBF69, RED = 0xFFFF7B75, GREEN = 0xFF8CDDB4;
	private HmiArt() {}
	static String fitText(String text, int width, ToIntFunction<String> measure) {
		if (width <= 0) return "";
		if (measure.applyAsInt(text) <= width) return text;
		String suffix = "...";
		if (measure.applyAsInt(suffix) > width) return "";
		int end = text.length();
		while (end > 0 && measure.applyAsInt(text.substring(0, end) + suffix) > width) {
			end = text.offsetByCodePoints(end, -1);
		}
		return text.substring(0, end) + suffix;
	}

	static int hudSideWidth(int width) {
		return Math.max(0, Math.min(width - 84, Math.min(380, Math.max(300, width * 380 / 1920))));
	}

	static boolean hudSidesFit(int width) {
		return width >= 2 * hudSideWidth(width) + 100;
	}

	static int hudOverviewWidth(int width) {
		return Math.max(0, Math.min(920, width - 2 * (hudSideWidth(width) + 58)));
	}

	static void icon(GuiGraphicsExtractor g, String name, int x, int y, int size) {
		g.blit(RenderPipelines.GUI_TEXTURED, RuntimeMesh.id("textures/gui/symbols/" + name + ".png"),
			x, y, 0, 0, size, size, 32, 32, 32, 32);
	}
	static void role(GuiGraphicsExtractor g, DroneRole role, int x, int y, int size) {
		g.blit(RenderPipelines.GUI_TEXTURED, RuntimeMesh.id("textures/gui/roles/" + role.name().toLowerCase(java.util.Locale.ROOT) + ".png"),
			x, y, 0, 0, size, size, 512, 512, 512, 512);
	}
	static void item(GuiGraphicsExtractor g, String name, int x, int y, int size) {
		g.blit(RenderPipelines.GUI_TEXTURED, RuntimeMesh.id("textures/item/" + name + ".png"),
			x, y, 0, 0, size, size, 128, 128, 128, 128);
	}
}
