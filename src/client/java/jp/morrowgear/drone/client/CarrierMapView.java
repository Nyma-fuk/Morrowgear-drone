package jp.morrowgear.drone.client;

import jp.morrowgear.drone.CarrierUiPolicy.Rect;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import jp.morrowgear.drone.carrier.CarrierViewPayload;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierPolicy;

/** A bounded terrain cache using the live exterior level or its server snapshot, never the cabin floor. */
final class CarrierMapView implements AutoCloseable {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("morrowgear_drone", "carrier_ui_terrain");
    private final Minecraft client = Minecraft.getInstance();
    private DynamicTexture texture;
    private ClientLevel level;
    private CarrierViewPayload.Terrain snapshot = CarrierViewPayload.Terrain.EMPTY;
    private int cx, cz, key, columns, rows;
    private long refreshAt;
    private boolean working;
    void working(boolean value) { working = value; }
    boolean draw(GuiGraphicsExtractor g, Rect map, String dimension, double centerX, double centerZ, double zoom,
                 CarrierViewPayload.Terrain exteriorSnapshot) {
        g.fill(map.x(), map.y(), map.right(), map.bottom(), HmiArt.PANEL);
        boolean local = client.level != null && client.level.dimension().identifier().toString().equals(dimension);
        boolean remote = exteriorSnapshot != null && exteriorSnapshot.available()
            && exteriorSnapshot.dimension().equals(dimension);
        if (!local && !remote) return false;
        ClientLevel nextLevel = local ? client.level : null;
        CarrierViewPayload.Terrain nextSnapshot = local ? CarrierViewPayload.Terrain.EMPTY : exteriorSnapshot;
        int x = (int) Math.floor(centerX), z = (int) Math.floor(centerZ);
        int nextKey = java.util.Objects.hash(map.width(), map.height(), zoom);
        boolean expired = local && client.level.getGameTime() >= refreshAt;
        if (texture == null || level != nextLevel || !snapshot.equals(nextSnapshot)
            || cx != x || cz != z || key != nextKey || expired) {
            level = nextLevel; snapshot = nextSnapshot; cx = x; cz = z; key = nextKey;
            refreshAt = local ? level.getGameTime() + (working ? 10 : 40) : Long.MAX_VALUE;
            int nextColumns = Math.max(1, Math.min(160, map.width() / 3)), nextRows = Math.max(1, Math.min(100, map.height() / 3));
            if (texture == null || columns != nextColumns || rows != nextRows) {
                close(); columns = nextColumns; rows = nextRows;
                texture = new DynamicTexture(() -> "Morrowgear carrier terrain", columns, rows, false);
                client.getTextureManager().register(TEXTURE, texture);
            }
            NativeImage pixels = texture.getPixels();
            if (pixels != null) for (int row = 0; row < rows; row++) for (int col = 0; col < columns; col++) {
                int wx = (int) Math.floor(centerX + ((col + .5) * map.width() / columns - map.width() / 2.0) / zoom);
                int wz = (int) Math.floor(centerZ + ((row + .5) * map.height() / rows - map.height() / 2.0) / zoom);
                int color = HmiArt.PANEL;
                if (local && level.hasChunkAt(new BlockPos(wx, 0, wz))) {
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                    BlockPos p = new BlockPos(wx, y, wz);
                    color = 0xFF000000 | level.getBlockState(p).getMapColor(level, p).col;
                } else if (!local) {
                    int packed = snapshot.packedAt(wx, wz);
                    if (packed >= 0) color = MapColor.getColorFromPackedId(packed);
                }
                pixels.setPixel(col, row, color);
            }
            texture.upload();
        }
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, map.x(), map.y(), 0, 0, map.width(), map.height(), columns, rows, columns, rows);
        return true;
    }
    void drawEffects(GuiGraphicsExtractor g, Rect map, double centerX, double centerZ, double zoom,
                     CarrierAnchor exterior, CarrierViewPayload view, boolean fresh) {
        var effect = view.navigation().effects();
        double ox = map.x() + map.width() / 2.0, oz = map.y() + map.height() / 2.0;
        if (effect.combatRadius() > 0) {
            double x = ox + (view.chunkX() * 16 + 8 - centerX) * zoom;
            double z = oz + (view.chunkZ() * 16 + 8 - centerZ) * zoom;
            double radius = effect.combatRadius() * zoom;
            // Dashed search perimeter, not a filled area-damage overlay.
            for (int i = 0; i < 72; i += 2) {
                double a = i * Math.PI / 36, b = (i + 1) * Math.PI / 36;
                line(g, map, x + radius * Math.cos(a), z + radius * Math.sin(a),
                    x + radius * Math.cos(b), z + radius * Math.sin(b), HmiArt.AMBER);
            }
        }
        if (!fresh || effect.phase() != CarrierPolicy.WorkPhase.FIRE) return;
        int color = view.mode() == CarrierPolicy.Mode.COMBAT.ordinal() ? HmiArt.AMBER : HmiArt.CYAN;
        for (var target : effect.beamAims().stream().limit(CarrierEffectGeometry.MAX_BEAMS).toList()) {
            double x = ox + (target.x - centerX) * zoom, z = oz + (target.z - centerZ) * zoom;
            line(g, map, ox + (exterior.x() - centerX) * zoom, oz + (exterior.z() - centerZ) * zoom, x, z, color);
            g.outline((int)x - 3, (int)z - 3, 7, 7, color);
        }
    }
    private static void line(GuiGraphicsExtractor g, Rect r, double ax, double ay, double bx, double by, int color) {
        double[] clipped = CarrierMapLine.clip(ax, ay, bx, by, r.x(), r.y(), r.right() - 1, r.bottom() - 1);
        if (clipped == null) return;
        int steps = Math.max(1, (int)Math.ceil(Math.max(Math.abs(clipped[2] - clipped[0]), Math.abs(clipped[3] - clipped[1]))));
        for (int i = 0; i <= steps; i++) {
            int x = (int)Math.round(clipped[0] + (clipped[2] - clipped[0]) * i / steps);
            int y = (int)Math.round(clipped[1] + (clipped[3] - clipped[1]) * i / steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
    @Override public void close() {
        if (texture != null) client.getTextureManager().release(TEXTURE);
        texture = null;
    }
}

final class CarrierMapLine {
    private CarrierMapLine() {}
    static double[] clip(double ax, double ay, double bx, double by, double minX, double minY, double maxX, double maxY) {
        if (!Double.isFinite(ax + ay + bx + by) || minX > maxX || minY > maxY) return null;
        double from = 0, to = 1;
        double[] p = {bx - ax, by - ay}, a = {ax, ay}, lo = {minX, minY}, hi = {maxX, maxY};
        for (int i = 0; i < 2; i++) {
            if (p[i] == 0) { if (a[i] < lo[i] || a[i] > hi[i]) return null; }
            else {
                double x = (lo[i] - a[i]) / p[i], y = (hi[i] - a[i]) / p[i];
                from = Math.max(from, Math.min(x, y)); to = Math.min(to, Math.max(x, y));
                if (from > to) return null;
            }
        }
        return new double[]{ax + p[0] * from, ay + p[1] * from, ax + p[0] * to, ay + p[1] * to};
    }
}
