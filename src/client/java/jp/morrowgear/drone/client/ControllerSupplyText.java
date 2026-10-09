package jp.morrowgear.drone.client;

import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;

final class ControllerSupplyText {
    private ControllerSupplyText() {}
    static String kind(SupplyKind kind) {
        return switch (kind) {
            case FUEL -> "推進電力源"; case GUN -> "機関砲マガジン"; case LASER -> "兵装電力セル";
            case MISSILE -> "ミサイルパック"; case REPAIR -> "整備素材";
        };
    }
    static String status(Status status) {
        return switch (status) {
            case DISABLED -> "停止"; case READY -> "待機"; case IN_TRANSIT -> "配送中";
            case RETURNING -> "帰還中"; case RECHARGING -> "充電中"; case WAIT_CHUNK -> "地域の読込待ち";
            case NO_IDLE_CARGO -> "空き輸送機なし"; case SOURCE_SHORTAGE -> "補給元の在庫不足";
            case SOURCE_LOST -> "補給元を確認できません"; case DOCK_LOST -> "Dockを確認できません";
            case DOCK_FULL -> "Dock満杯"; case RESERVATION_INVALID -> "予約無効";
            case ASSIGNMENT_LOST -> "割当なし"; case CARGO_MISMATCH -> "積荷不一致";
            case CANCELLED -> "取消"; case LOAD_RETAINED -> "積荷保持中";
            case RUNTIME_UNAVAILABLE -> "配送処理を利用できません"; case ACCESS_DENIED -> "操作権限なし";
            case CAPACITY_LIMIT -> "配送数の上限";
        };
    }
}
