package jp.morrowgear.drone.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerDispatchFeedbackTest {
    @Test void zeroSubmissionsCannotClaimTransmission() {
        assertEquals("未送信 / 指示可能な対象なし", ControllerDispatchFeedback.describe(0, 0, ""));
    }
    @Test void duplicateSuppressionRemainsUnsent() {
        assertEquals("未送信 / 2機: 結果を確認中", ControllerDispatchFeedback.describe(0, 2, "結果を確認中"));
    }
    @Test void partialSubmissionReportsBothCountsWithoutAcceptance() {
        assertEquals("3機へ送信 / 受理・実行は未確認 / 2機: 通信なし",
            ControllerDispatchFeedback.describe(3, 2, "通信なし"));
    }
    public static void main(String[] args) {
        var test = new ControllerDispatchFeedbackTest();
        test.zeroSubmissionsCannotClaimTransmission();
        test.duplicateSuppressionRemainsUnsent();
        test.partialSubmissionReportsBothCountsWithoutAcceptance();
        System.out.println("ControllerDispatchFeedback: 3 tests passed");
    }
}
