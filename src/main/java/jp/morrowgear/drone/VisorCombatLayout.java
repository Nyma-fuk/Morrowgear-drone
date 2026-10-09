package jp.morrowgear.drone;

/** Visor canvas bounds; the overlay scales this canvas and its projected markers together. */
public final class VisorCombatLayout {
    private VisorCombatLayout() {}
    public record Box(int x, int y, int width, int height) {
        public boolean intersects(Box b) {
            return x < b.x + b.width && x + width > b.x && y < b.y + b.height && y + height > b.y;
        }
    }
    public static int sideWidth(int width) { return Math.max(120, Math.min(340, (width - 72) / 2)); }
    public static Box summary(int width, int fronts) { return new Box(24, 24, sideWidth(width), 58 + Math.min(3, Math.max(0, fronts)) * 48); }
    public static Box feed(int width, int events) { return new Box(width - 24 - sideWidth(width), 24, sideWidth(width), Math.min(3, Math.max(0, events)) * 62); }
    public static Box inspection(int width, int height, int desiredHeight) {
        return new Box(width - 24 - sideWidth(width), 232, sideWidth(width), Math.max(0, Math.min(desiredHeight, height * 81 / 100 - 244)));
    }
    public static String location(double dx, double dy, double dz) {
        String[] directions = {"北", "北東", "東", "南東", "南", "南西", "西", "北西"};
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        String direction = Math.hypot(dx, dz) < 0.5 ? "直近" : directions[Math.floorMod((int)Math.round(Math.atan2(dx, -dz) / (Math.PI / 4)), 8)];
        return direction + " " + Math.round(distance) + "m";
    }
    public static Boolean life(boolean confirmedDead, boolean removed, boolean sameEntity) {
        return confirmedDead ? Boolean.FALSE : removed || !sameEntity ? null : Boolean.TRUE;
    }
}
