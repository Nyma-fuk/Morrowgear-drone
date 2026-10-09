package jp.morrowgear.drone.client;

final class RecoveryDisplayText {
    private RecoveryDisplayText() {}
    static String assignee(String role, String value) {
        if (value == null || value.isBlank() || value.startsWith("未") || value.equals("担当未確認")) return "未確認";
        try { return role + " " + java.util.UUID.fromString(value).toString().substring(0, 8); }
        catch (IllegalArgumentException ignored) { return role + " " + value.split(" / ", 2)[0]; }
    }
    static String mission(String value) {
        if (value == null || value.isBlank() || value.startsWith("保存された") || value.equals("未同期")) return "不明";
        if (!value.startsWith("保存モード: ")) return value;
        return switch (value.substring("保存モード: ".length())) {
            case "WAYPOINT" -> "巡回航行";
            case "FOLLOW" -> "所有者に随伴";
            case "STANDBY" -> "待機";
            case "DOCK" -> "Dockへ帰還";
            case "ORBIT" -> "周辺警戒";
            default -> "不明";
        };
    }
    static String reason(String value) {
        if (value.contains("ADD FUEL")) return "搬送先の燃料補給待ち";
        if (value.contains("ADD REPAIR MATERIAL")) return "搬送先の修理資材待ち";
        if (value.contains("PROTECTED")) return "保護された障害物を確認";
        if (value.contains("NO CLEAR TRANSFER DOCK")) return "安全に引き渡せるDockを待っています";
        if (value.contains("DOCK LOST")) return "搬送先を再選定中";
        if (value.contains("ENGINEER APPROACH")) return "救援機が接近中";
        if (value.contains("ENGINEER")) return "救援機の作業待ち";
        if (value.contains("CHUNK") || value.contains("UNAVAILABLE")) return "周辺情報の受信待ち";
        if (value.contains("RESTORING") || value.contains("SERVICE")) return "補給・修復中";
        if (value.contains("HOLD")) return "安全を確認して待機中";
        if (value.contains("LOCAL") || value.isBlank()) return "待ち理由の報告なし";
        return "状況を確認中";
    }
    static String age(long now, long observed) {
        return observed < 0 ? "観測時刻不明" : "観測 " + Math.max(0, (now - observed) / 1_000_000_000L) + "秒前";
    }
}
