package jp.morrowgear.drone.client;

/** GUI-pixel dimensions; native inventory slots keep their original relative coordinates. */
final class DockCarrierLayout {
    private DockCarrierLayout() {}
    static int dockDetailsX(int width) { return Math.max(182, Math.min(280, width / 3)); }
    static int dockInventoryX(int width) { return Math.max(0, (dockDetailsX(width) - 182) / 2); }
    static int dockInventoryY(int height) { return Math.max(0, (height - 166) / 2); }
    static int supplyTabWidth(int width, int x) { return Math.max(20, Math.min(120, (width - x - 14) / 3)); }
}
