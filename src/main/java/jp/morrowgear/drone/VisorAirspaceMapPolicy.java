package jp.morrowgear.drone;

/** Bounded isometric projection contract for the visor's live airspace view. */
public final class VisorAirspaceMapPolicy {
	public static final int TERRAIN_GRID = 11;
	public static final int TERRAIN_REFRESH_TICKS = 40;
	public static final double MIN_RANGE = 160.0;
	public static final double MAX_RANGE = 384.0;
	public static final double FIELD_COVERAGE = 128.0;
	public static final double TERRAIN_SLAB_DEPTH = 10.0;
	public static final int DOME_SEGMENTS = 20;
	public static final int MAX_AIR_TRAFFIC = 48;
	public static final int TERRAIN_SCAN_STEP = 4;
	public static final double DOME_GROUND_CLEARANCE = 1.5;
	public static final double MAX_DISPLAY_RELIEF = 40.0;

	private VisorAirspaceMapPolicy() {}

	public static double range(double centerX, double centerZ, double minX, double maxX,
		double minZ, double maxZ) {
		double extent = Math.max(Math.max(Math.abs(minX - centerX), Math.abs(maxX - centerX)),
			Math.max(Math.abs(minZ - centerZ), Math.abs(maxZ - centerZ)));
		if (!Double.isFinite(extent)) extent = MIN_RANGE;
		return Math.max(MIN_RANGE, Math.min(MAX_RANGE, extent + 24.0));
	}

	/**
	 * Keeps the bird-view instrument anchored above the hotbar. Other adaptive HUD panels may
	 * change their contents, but they must never make this instrument jump around the screen.
	 */
	public static Panel panel(int screenWidth, int screenHeight) {
		int safeWidth = Math.max(320, screenWidth);
		int safeHeight = Math.max(240, screenHeight);
		int width = Math.min(safeWidth - 48, Math.max(420, Math.min(720, safeWidth * 44 / 100)));
		int availableHeight = Math.max(160, safeHeight - 188);
		int height = Math.min(availableHeight, Math.max(210, Math.min(280, safeHeight * 27 / 100)));
		int bottomClearance = Math.max(92, Math.min(128, safeHeight * 11 / 100));
		int x = (safeWidth - width) / 2;
		int y = Math.max(96, safeHeight - bottomClearance - height);
		return new Panel(x, y, width, height);
	}

	public static Point project(Frame frame, double worldX, double worldY, double worldZ) {
		double nx = (worldX - frame.centerX()) / frame.range();
		double nz = (worldZ - frame.centerZ()) / frame.range();
		double verticalRange = Math.max(MIN_RANGE, Math.min(192.0, frame.range()));
		double ny = (worldY - frame.centerY()) / verticalRange;
		int x = (int)Math.round(frame.x() + frame.width() * 0.5 + (nx - nz) * frame.width() * 0.23);
		int y = (int)Math.round(frame.y() + frame.height() * 0.62
			+ (nx + nz) * frame.height() * 0.15 - ny * frame.height() * 0.48);
		return new Point(x, y);
	}

	public static double sampleOffset(int index, double range) {
		if (TERRAIN_GRID <= 1) return 0.0;
		return -range + index * range * 2.0 / (TERRAIN_GRID - 1);
	}

	public static double domeBaseY(double localTerrainHeight) {
		return localTerrainHeight + DOME_GROUND_CLEARANCE;
	}

	/**
	 * Compresses exceptional towers and shafts without flattening ordinary terrain. The airspace
	 * instrument is an overview, so one extreme column must not turn the whole terrain slab into
	 * a screen-height spike.
	 */
	public static double terrainDisplayY(double terrainY, double datumY) {
		double relief = terrainY - datumY;
		return datumY + Math.tanh(relief / MAX_DISPLAY_RELIEF) * MAX_DISPLAY_RELIEF;
	}

	public static Point3 spherePoint(double centerX, double centerY, double centerZ,
		SphereRing ring, double angle) {
		double cosine = Math.cos(angle) * FIELD_COVERAGE;
		double sine = Math.sin(angle) * FIELD_COVERAGE;
		return switch (ring) {
			case HORIZONTAL -> new Point3(centerX + cosine, centerY, centerZ + sine);
			case NORTH_SOUTH -> new Point3(centerX, centerY + sine, centerZ + cosine);
			case EAST_WEST -> new Point3(centerX + cosine, centerY + sine, centerZ);
		};
	}

	/**
	 * Produces one aggregate upper hemisphere for the mesh overview. Individual relay coverage
	 * remains {@link #FIELD_COVERAGE}; the aggregate dome is a readability aid, not a radio model.
	 */
	public static Point3 domePoint(double centerX, double baseY, double centerZ,
		double radius, double height, DomeArc arc, double angle) {
		if (arc == DomeArc.BASE) {
			return new Point3(centerX + Math.cos(angle) * radius, baseY,
				centerZ + Math.sin(angle) * radius);
		}
		double horizontal = Math.cos(angle) * radius;
		double y = baseY + Math.max(0.0, Math.sin(angle)) * height;
		return switch (arc) {
			case NORTH_SOUTH -> new Point3(centerX, y, centerZ + horizontal);
			case EAST_WEST -> new Point3(centerX + horizontal, y, centerZ);
			case DIAGONAL -> new Point3(centerX + horizontal * Math.sqrt(0.5), y,
				centerZ + horizontal * Math.sqrt(0.5));
			case BASE -> throw new IllegalStateException("BASE handled above");
		};
	}

	public record Frame(int x, int y, int width, int height, double centerX, double centerY,
		double centerZ, double range) {}
	public record Panel(int x, int y, int width, int height) {}
	public record Point(int x, int y) {}
	public record Point3(double x, double y, double z) {}
	public enum SphereRing { HORIZONTAL, NORTH_SOUTH, EAST_WEST }
	public enum DomeArc { BASE, NORTH_SOUTH, EAST_WEST, DIAGONAL }
}
