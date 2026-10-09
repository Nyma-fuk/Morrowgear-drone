package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import jp.morrowgear.drone.network.DroneCommandResultPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ControllerCommandResultsTest {
    @AfterEach void clear() { ControllerCommandResults.clear(); }

    @Test void latestBatchReportsPartialSuccessInsteadOfMixingOlderCommands() {
        UUID old = UUID.randomUUID(), batch = UUID.randomUUID();
        ControllerCommandResults.accept(result(old, UUID.randomUUID(), DroneCommandResultPayload.Status.FAILED, 1));
        ControllerCommandResults.accept(result(batch, UUID.randomUUID(), DroneCommandResultPayload.Status.COMPLETED, 2));
        ControllerCommandResults.accept(result(batch, UUID.randomUUID(), DroneCommandResultPayload.Status.HELD, 3));
        ControllerCommandResults.accept(result(batch, UUID.randomUUID(), DroneCommandResultPayload.Status.REJECTED, 3));
        String summary = ControllerCommandResults.summary();
        assertTrue(summary.contains("3機"));
        assertTrue(summary.contains("完了 1"));
        assertTrue(summary.contains("保留 1"));
        assertTrue(summary.contains("要対応 1"));
        assertFalse(summary.contains("要対応 2"));
        assertTrue(ControllerCommandResults.latestReason().contains("REJECTED"));
        assertTrue(ControllerCommandResults.latestReason().contains("他1機"));
    }

    private static DroneCommandResultPayload result(UUID command, UUID unit,
            DroneCommandResultPayload.Status status, long tick) {
        return new DroneCommandResultPayload(command, unit, 1, status, "dock", status.name(), tick);
    }
}
