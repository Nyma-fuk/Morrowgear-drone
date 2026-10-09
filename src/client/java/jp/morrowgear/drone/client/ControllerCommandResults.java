package jp.morrowgear.drone.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.network.DroneCommandResultPayload;

/** Short-lived client projection of authoritative command results. */
final class ControllerCommandResults {
    private static final int LIMIT = 512;
    private static final Map<Key, DroneCommandResultPayload> RESULTS = new LinkedHashMap<>();
    private ControllerCommandResults() {}

    static synchronized void accept(DroneCommandResultPayload result) {
        Key key = new Key(result.commandId(), result.unitId());
        RESULTS.put(key, result);
        while (RESULTS.size() > LIMIT) RESULTS.remove(RESULTS.keySet().iterator().next());
    }
    static synchronized DroneCommandResultPayload get(UUID command, UUID unit) {
        return RESULTS.get(new Key(command, unit));
    }
    static synchronized String summary() {
        int accepted = 0, held = 0, rejected = 0, executing = 0, completed = 0, failed = 0;
        UUID latestCommand = latestCommand();
        for (DroneCommandResultPayload result : RESULTS.values()) {
            if (!result.commandId().equals(latestCommand)) continue;
            switch (result.status()) {
            case ACCEPTED -> accepted++; case HELD -> held++; case REJECTED -> rejected++;
            case EXECUTING -> executing++; case COMPLETED -> completed++; case FAILED -> failed++;
            }
        }
        if (RESULTS.isEmpty()) return "命令履歴なし";
        int total = accepted + held + rejected + executing + completed + failed;
        String value = "直近命令 " + total + "機 / 実行 " + executing + " / 完了 " + completed;
        if (held > 0) value += " / 保留 " + held;
        if (rejected + failed > 0) value += " / 要対応 " + (rejected + failed);
        if (accepted > 0) value += " / 受付 " + accepted;
        return value;
    }
    static synchronized String latestReason() {
        UUID command = latestCommand();
        DroneCommandResultPayload important = null, latest = null;
        int issues = 0;
        for (DroneCommandResultPayload result : RESULTS.values()) {
            if (!result.commandId().equals(command)) continue;
            if (latest == null || result.serverTick() >= latest.serverTick()) latest = result;
            if (result.status() == DroneCommandResultPayload.Status.HELD
                || result.status() == DroneCommandResultPayload.Status.REJECTED
                || result.status() == DroneCommandResultPayload.Status.FAILED) {
                issues++;
                if (important == null || severity(result.status()) > severity(important.status())) important = result;
            }
        }
        DroneCommandResultPayload shown = important == null ? latest : important;
        if (shown == null) return "命令を選択してください";
        String unit = shown.unitId().equals(new UUID(0, 0)) ? "対象不明"
            : shown.unitId().toString().substring(0, 6).toUpperCase();
        return unit + " / " + shown.reason() + (issues > 1 ? " / 他" + (issues - 1) + "機" : "");
    }
    private static int severity(DroneCommandResultPayload.Status status) {
        return switch (status) { case FAILED -> 3; case REJECTED -> 2; case HELD -> 1; default -> 0; };
    }
    private static UUID latestCommand() {
        DroneCommandResultPayload latest = null;
        for (DroneCommandResultPayload result : RESULTS.values())
            if (latest == null || result.serverTick() >= latest.serverTick()) latest = result;
        return latest == null ? new UUID(0, 0) : latest.commandId();
    }
    static synchronized void clear() { RESULTS.clear(); }
    private record Key(UUID command, UUID unit) {}
}
