package jp.morrowgear.drone.client;

import java.util.regex.Pattern;

final class ControllerDisplayText {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern LEGACY = Pattern.compile("ResourceKey\\[\\s*minecraft:dimension\\s*/\\s*([a-z0-9_.-]+:[a-z0-9_./-]+)\\s*\\]");
    private ControllerDisplayText() {}

    static String dimensionId(String value) {
        if (value == null) return "";
        String id = value.strip();
        var legacy = LEGACY.matcher(id);
        if (legacy.matches()) id = legacy.group(1);
        if (id.equals("overworld") || id.equals("the_nether") || id.equals("the_end")) id = "minecraft:" + id;
        return ID.matcher(id).matches() ? id : "";
    }

    static boolean sameDimension(String first, String second) {
        String id = dimensionId(first);
        return !id.isEmpty() && id.equals(dimensionId(second));
    }

    static String dimensionName(String value) {
        String id = dimensionId(value);
        return switch (id) {
            case "minecraft:overworld" -> "オーバーワールド";
            case "minecraft:the_nether" -> "ネザー";
            case "minecraft:the_end" -> "ジ・エンド";
            case "" -> "次元不明";
            default -> id.length() <= 32 ? id : id.substring(0, 29) + "...";
        };
    }

    static String shortUnitId(String id) { return id.startsWith("MG-DRN-") ? id.substring(7) : id; }
    static String unitName(String name, String id) {
        return name.equals(id) || name.startsWith("MG-DRN-") ? shortUnitId(name) : name;
    }
    static String action(String action) {
        return switch (action) {
            case "follow" -> "追従"; case "return" -> "集合"; case "dock" -> "Dock帰還";
            case "standby" -> "任務中止"; case "orbit" -> "旋回"; case "decommission" -> "格納";
            default -> "操作不明";
        };
    }
}
