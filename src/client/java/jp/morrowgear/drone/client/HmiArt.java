package jp.morrowgear.drone.client;

import java.util.function.ToIntFunction;
import jp.morrowgear.drone.DroneRole;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

final class HmiArt {
	static final int BACKGROUND = 0xFF121514, PANEL = 0xFF1A1E1C, RAISED = 0xFF29362F;
	static final int LINE = 0xFF343C36, TEXT = 0xFFE1E7E0, MUTED = 0xFF99A59D;
	static final int CYAN = 0xFF7CDEC8, AMBER = 0xFFE7BD79, RED = 0xFFF28D83, GREEN = 0xFFA5D391;
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
