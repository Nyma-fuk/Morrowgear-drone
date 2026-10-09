package jp.morrowgear.drone;

/** Bounded, owner-scoped tactical contact transport for controller displays. */
public final class TacticalContactPolicy {
    public static final int LIMIT = 128;
    public static final long REQUEST_INTERVAL_TICKS = 10L;

    private TacticalContactPolicy() {}

    public static boolean mayRequest(long now, Long previous) {
        return previous == null || now < previous || now - previous >= REQUEST_INTERVAL_TICKS;
    }
}
