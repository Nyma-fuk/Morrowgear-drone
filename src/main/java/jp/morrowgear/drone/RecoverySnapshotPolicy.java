package jp.morrowgear.drone;

import java.util.UUID;

public final class RecoverySnapshotPolicy {
    public static final int LIMIT = 128, REQUEST_INTERVAL = 20, FRESH_TICKS = 60;
    private RecoverySnapshotPolicy() {}
    public static boolean owned(UUID requester, UUID owner) { return requester != null && requester.equals(owner); }
    public static boolean mayRequest(long now, Long previous) {
        return previous == null || now >= previous && now - previous >= REQUEST_INTERVAL;
    }
    public static boolean fresh(long now, long observed) { return now >= observed && now - observed <= FRESH_TICKS; }
    public static boolean accepts(UUID player, UUID owner, long expectedToken, long receivedToken) {
        return owned(player, owner) && expectedToken == receivedToken;
    }
    public static String destinationLabel(boolean known, java.util.Optional<String> position) {
        if (!known) return "不明 / 配送先の現況未取得";
        return position.orElse("未割当 / 実配送先未確定");
    }
    public static String destination(SalvageState stage, String carrierDock, String targetDock) {
        return switch (stage) {
            case RETURN, DELIVER -> carrierDock;
            case SERVICE -> targetDock;
            default -> "";
        };
    }
    public static String reception(boolean received, boolean fresh, String currentDimension, String rowDimension) {
        if (!received) return "未受信";
        if (!fresh) return "期限切れ / 最終確認のみ";
        return currentDimension.equals(rowDimension) ? "受信済" : "別次元 / 受信済";
    }
}
