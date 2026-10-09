package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import jp.morrowgear.drone.network.RecoverySnapshotPayload;
import jp.morrowgear.drone.network.RecoverySnapshotRequest;
import org.junit.jupiter.api.Test;

class RecoverySnapshotPayloadTest {
    @Test void responseRoundTripPreservesIdentityReasonDimensionAndUnknowns() {
        var row = new RecoverySnapshotPayload.Row(UUID.randomUUID().toString(), "unit", "minecraft:the_nether",
            "1, 2, 3", UUID.randomUUID().toString(), "", "SERVICE", "7, 8, 9", "ADD FUEL", "保存モード: FOLLOW", true);
        var original = new RecoverySnapshotPayload(UUID.randomUUID(), 42, 827, false, List.of(row));
        var buffer = Unpooled.buffer();
        try {
            RecoverySnapshotPayload.CODEC.encode(buffer, original);
            assertEquals(original, RecoverySnapshotPayload.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }
    @Test void requestContainsNoCallerSuppliedOwnerOrTarget() {
        var buffer = Unpooled.buffer();
        try {
            RecoverySnapshotRequest.CODEC.encode(buffer, new RecoverySnapshotRequest(9));
            assertEquals(8, buffer.readableBytes());
            assertEquals(9, RecoverySnapshotRequest.CODEC.decode(buffer).token());
        } finally { buffer.release(); }
    }
    @Test void rejectsOversizedResponseBeforeAllocatingRows() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeLong(0).writeLong(0).writeLong(1).writeLong(1).writeBoolean(false).writeInt(129);
            assertThrows(IllegalArgumentException.class, () -> RecoverySnapshotPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void stringsAreBoundedAndUnknownDestinationSurvivesTheWire() {
        var row = new RecoverySnapshotPayload.Row(UUID.randomUUID().toString(), "機".repeat(200), "dimension", "",
            "", "", "", "", "待".repeat(200), "", false);
        var buffer = Unpooled.buffer();
        try {
            RecoverySnapshotPayload.CODEC.encode(buffer, new RecoverySnapshotPayload(UUID.randomUUID(), 1, 2, false, List.of(row)));
            var decoded = RecoverySnapshotPayload.CODEC.decode(buffer).rows().getFirst();
            assertEquals(128, decoded.unit().length());
            assertEquals(128, decoded.reason().length());
            assertFalse(decoded.destinationKnown());
            assertTrue(decoded.destinationPosition().isEmpty());
        } finally { buffer.release(); }
    }
    @Test void networkRejectsBeforeScanningAndGetterDoesNotResolveOrMutateMissions() throws Exception {
        String network = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/jp/morrowgear/drone/RecoverySnapshotNetwork.java"));
        assertTrue(network.indexOf("RecoverySnapshotPolicy.mayRequest") < network.indexOf("getAllLevels()"));
        assertTrue(network.contains("RecoverySnapshotPolicy.owned(player.getUUID(), drone.ownerId())"));
        String entity = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
        String getter = entity.substring(entity.indexOf("public jp.morrowgear.drone.network.RecoverySnapshotPayload.Row recoverySnapshotFor"),
            entity.indexOf("public String salvageStatusLabel"));
        for (String forbidden : List.of("salvageTarget(level)", "assignDock(", "requestDockSlot(", "resolveTaskForResume(",
            "getChunk(", "entityData.set(", "taskStack.resume(")) assertFalse(getter.contains(forbidden), forbidden);
    }
}
