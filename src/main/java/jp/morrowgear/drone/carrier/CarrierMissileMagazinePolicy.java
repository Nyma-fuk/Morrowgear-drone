package jp.morrowgear.drone.carrier;

/** Twenty-cell 10 x 2 carrier missile container and its ground-only replenishment rules. */
public final class CarrierMissileMagazinePolicy {
    public static final int ROWS = 2;
    public static final int COLUMNS = 10;
    public static final int CAPACITY = ROWS * COLUMNS;
    public static final String RELOAD_ITEM = "morrowgear_drone:carrier_bunker_missile";
    private CarrierMissileMagazinePolicy() {}

    public static int insertable(int missiles, int suppliedItems, boolean placed, boolean leased) {
        if (!placed || leased || suppliedItems <= 0) return 0;
        return Math.min(Math.max(0, CAPACITY - Math.clamp(missiles, 0, CAPACITY)), suppliedItems);
    }

    public static boolean mayLaunch(int missiles, boolean attached, boolean hatchClosed,
                                    boolean transferActive, boolean freshContact) {
        return missiles > 0 && attached && hatchClosed && !transferActive && freshContact;
    }

    public static boolean undergroundContact(boolean hostile, boolean scanned, boolean ownerDataLink,
                                             long contactAgeTicks, int y) {
        return scanned && CarrierMissileModulePolicy.targetable(hostile, ownerDataLink, contactAgeTicks, y);
    }
}
