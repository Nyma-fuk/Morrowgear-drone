package jp.morrowgear.drone.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jp.morrowgear.drone.SalvageState;

/** Screen-local observations, never a server completion ledger. */
final class DroneRecoveryModel {
    static final int LIMIT = 128;
    record Row(String key, String unit, String dimension, String position, String identity,
        String carrier, SalvageState stage, String dock, String reason, String condition, long seenTick,
        String engineer, String originalMission) {
        Row(String key, String unit, String dimension, String position, String identity,
            String carrier, SalvageState stage, String dock, String reason, String condition, long seenTick) {
            this(key, unit, dimension, position, identity, carrier, stage, dock, reason, condition, seenTick, "未同期", "未同期");
        }
    }
    private final Map<String, Row> rows = new LinkedHashMap<>();

    void observe(List<Row> incoming) {
        for (Row row : incoming) {
            rows.remove(row.key());
            rows.put(row.key(), row);
        }
        while (rows.size() > LIMIT) rows.remove(rows.keySet().iterator().next());
    }
    List<Row> rows() { return rows.values().stream().sorted(java.util.Comparator.comparing(Row::unit)
        .thenComparing(Row::key)).toList(); }
    List<Row> rows(boolean beacons) {
        return rows().stream().filter(row -> row.key().startsWith("beacon:") == beacons).toList();
    }
    static String unsyncedDestination() { return "実配送先は未同期（救助機の割当Dockとは別）"; }
    static String phase(SalvageState stage) {
        if (stage == null) return "工程未確認";
        return switch (stage) {
            case IDLE -> "担当機の工程なし / 完了未確認";
            case INTERCEPT -> "接近・救援";
            case HOOK -> "吊り荷接続";
            case HOIST -> "吊り上げ";
            case RETURN -> "搬送";
            case DELIVER -> "引渡し";
            case SERVICE -> "補給・修復";
        };
    }
    static int pageCapacity(int availableHeight, int lineHeight) {
        return Math.max(1, availableHeight / Math.max(1, lineHeight));
    }
}
