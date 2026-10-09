package jp.morrowgear.drone.client;

/** Counts transport submissions, never server acceptance or completed actions. */
final class ControllerDispatchFeedback {
    private ControllerDispatchFeedback() {}

    static String describe(int sent, int skipped, String reason) {
        String message = sent > 0 ? sent + "機へ送信 / 受理・実行は未確認" : "未送信";
        if (skipped > 0) message += " / " + skipped + "機: " + reason;
        else if (sent == 0) message += " / 指示可能な対象なし";
        return message;
    }
}
