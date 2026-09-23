package jp.morrowgear.drone;

public final class TacticalLayoutPolicy {
	private TacticalLayoutPolicy() {
	}

	public static Metrics calculate(int physicalWidth, int physicalHeight) {
		return calculateForGui(physicalWidth, physicalHeight, 1.0);
	}

	public static Metrics calculateForGui(int physicalWidth, int physicalHeight, double guiScale) {
		float scale = Math.min(1.0f, Math.min(physicalWidth / 960.0f, physicalHeight / 540.0f));
		// Glyph strokes must land on framebuffer pixels, independent of Minecraft GUI scale.
		if (scale * guiScale >= 1) scale = (float)(Math.floor(scale * guiScale + 1e-6) / guiScale);
		int width = Math.max(960, (int)(physicalWidth / scale));
		int height = Math.max(540, (int)(physicalHeight / scale));
		int header = 70;
		int bottomHeight = Math.max(138, Math.min(180, height / 4));
		int bottomTop = height - bottomHeight;
		int left = Math.max(210, Math.min(270, width / 5));
		int right = Math.max(270, Math.min(330, width / 5));
		int dockHeader = header + 56 + Math.max(112, (bottomTop - header - 56) * 60 / 100);
		int mapWidth = width - left - right;
		int mapHeight = bottomTop - header;
		int unitCapacity = Math.max(1, (dockHeader - (header + 81) - 20) / 34);
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
			return scale > 0 && scale <= 1 && width >= 960 && height >= 540
				&& header < dockHeaderY && dockHeaderY < bottomTop
				&& leftWidth >= 210 && rightWidth >= 270
				&& mapWidth > 0 && mapHeight > 0
				&& unitCapacity > 0 && dockCapacity > 0
				&& missionCapacity > 0 && cargoCapacity > 0;
		}
	}
}
