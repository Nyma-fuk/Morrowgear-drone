package jp.morrowgear.drone;

/** Display arithmetic only; stock has no invented maximum or percentage. */
public final class DockStockViewPolicy {
    private DockStockViewPolicy() {}

    public record PackStock(int unopened, int remaining, int unitsPerPack) {
        public long total() { return (long) unopened * unitsPerPack + remaining; }
    }

    public static PackStock packStock(int unopened, int remaining, int unitsPerPack) {
        if (unitsPerPack <= 0) throw new IllegalArgumentException("Nonpositive pack size");
        return new PackStock(Math.max(0, unopened), Math.max(0, remaining), unitsPerPack);
    }

    public static long flightReserve(int stored, int fuelCredit) {
        return (long) Math.max(0, stored) + Math.max(0, fuelCredit);
    }

    public record Reference(int amount, boolean connected) {}

    public static Reference reference(int standardCapacity, int connectedCapacity, boolean connected) {
        if (standardCapacity <= 0) throw new IllegalArgumentException("Nonpositive standard capacity");
        return connected && connectedCapacity > 0 ? new Reference(connectedCapacity, true) : new Reference(standardCapacity, false);
    }

    /** The denominator is either a real tank capacity or an explicitly named one-aircraft reference. */
    public static int gaugePixels(long amount, long capacity, int width) {
        if (capacity <= 0 || width <= 0 || amount <= 0) return 0;
        return (int) Math.round(Math.min(1.0, (double) amount / capacity) * width);
    }

    public static int rowsPerPage(int height) { return height >= 248 ? 4 : 2; }
    public static int pageCount(int height) { return 4 / rowsPerPage(height); }
    public static int rowHeight(int height) { return Math.min(46, Math.max(28, (height - 108) / rowsPerPage(height))); }
    public static int firstRow(int height, int page) { return Math.clamp(page, 0, pageCount(height) - 1) * rowsPerPage(height); }
}
