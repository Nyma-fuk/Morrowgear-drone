package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RecoveryDisplayTextTest {
    @Test void identifiersAreShortAndRoleLabelled() {
        assertEquals("回収機 12345678", RecoveryDisplayText.assignee("回収機", "12345678-1234-1234-1234-123456789abc"));
        assertEquals("未確認", RecoveryDisplayText.assignee("回収機", "未担当"));
        assertEquals("整備機 E01", RecoveryDisplayText.assignee("整備機", "E01 / hidden-uuid"));
    }
    @Test void savedModesAreReadableWithoutClaimingResume() {
        assertEquals("巡回航行", RecoveryDisplayText.mission("保存モード: WAYPOINT"));
        assertEquals("所有者に随伴", RecoveryDisplayText.mission("保存モード: FOLLOW"));
        assertEquals("不明", RecoveryDisplayText.mission("保存モード: UNKNOWN"));
    }
    @Test void routineReasonsNeverExposeProtocolWords() {
        assertEquals("待ち理由の報告なし", RecoveryDisplayText.reason("LOCAL"));
        assertEquals("搬送先の燃料補給待ち", RecoveryDisplayText.reason("SALVAGE SERVICE / ADD FUEL"));
        assertEquals("状況を確認中", RecoveryDisplayText.reason("NEW_INTERNAL_STATE"));
    }
    @Test void ageIsSecondsAndUnknownRemainsUnknown() {
        assertEquals("観測 3秒前", RecoveryDisplayText.age(4_500_000_000L, 1_000_000_000L));
        assertEquals("観測時刻不明", RecoveryDisplayText.age(100, -1));
    }
}
