package jp.morrowgear.drone;

public final class TacticalLayoutPolicy {
	private TacticalLayoutPolicy() {
	}

	public static Metrics calculate(int physicalWidth, int physicalHeight) {
		return calculateForGui(physicalWidth, physicalHeight, 1.0);
	}

	public static Metrics calculateForGui(int physicalWidth, int physicalHeight, double guiScale) {
		return calculateForGui(physicalWidth, physicalHeight, guiScale, 0);
	}

	public static Metrics calculateForGui(int physicalWidth, int physicalHeight, double guiScale, int requestedPixelScale) {
		// Keep glyph pixels integral on desktop, independently of Minecraft GUI scale.
		double gui = Math.max(1, guiScale);
		double fit = Math.min(physicalWidth * gui / 800.0, physicalHeight * gui / 450.0);
		int requested = requestedPixelScale <= 0 ? 2 : Math.clamp(requestedPixelScale, 1, 4);
		double framebufferScale = fit >= 1 ? Math.min(requested, Math.floor(fit)) : fit;
		float scale = (float)(framebufferScale / gui);
		int width = Math.max(800, (int)(physicalWidth / scale));
		int height = Math.max(450, (int)(physicalHeight / scale));
		int header = 40;
		int bottomHeight = 72;
		int bottomTop = height - bottomHeight;
		// The controller is organized as navigation rail + wing roster + map + inspector.
		// Left includes the 100 px rail and the full-height aircraft roster.
		int left = 276;
		int right = 160;
		int dockHeader = bottomTop - 8;
		int mapWidth = width - left - right;
		int mapHeight = bottomTop - header;
		int unitCapacity = Math.max(1, (dockHeader - (header + 105) - 20) / 38);
		int dockCapacity = Math.max(1, (bottomTop - (dockHeader + 17) - 8) / 26);
		int missionCapacity = Math.max(1, (height - (bottomTop + 55)) / 25);
		int cargoCapacity = Math.max(1, (height - (bottomTop + 38)) / 27);
		return new Metrics(scale, width, height, header, bottomTop, left, right, dockHeader,
			mapWidth, mapHeight, unitCapacity, dockCapacity, missionCapacity, cargoCapacity);
	}

	public static int clampScroll(int value, int total, int capacity) {
		return Math.max(0, Math.min(value, Math.max(0, total - capacity)));
	}

	public record Metrics(float scale, int width, int height, int header, int bottomTop,
		int leftWidth, int rightWidth, int dockHeaderY, int mapWidth, int mapHeight,
		int unitCapacity, int dockCapacity, int missionCapacity, int cargoCapacity) {
		public boolean structurallyValid() {
			return scale > 0 && scale <= 4 && width >= 800 && height >= 450
				&& header < dockHeaderY && dockHeaderY < bottomTop
				&& leftWidth == 276 && rightWidth == 160
				&& mapWidth > 0 && mapHeight > 0
				&& unitCapacity > 0 && dockCapacity > 0
				&& missionCapacity > 0 && cargoCapacity > 0;
		}
	}
}
