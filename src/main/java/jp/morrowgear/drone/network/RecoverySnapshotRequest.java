package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RecoverySnapshotRequest(long token) implements CustomPacketPayload {
    public static final Type<RecoverySnapshotRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("morrowgear_drone", "recovery_snapshot_request"));
    public static final StreamCodec<ByteBuf, RecoverySnapshotRequest> CODEC = ByteBufCodecs.LONG.map(RecoverySnapshotRequest::new, RecoverySnapshotRequest::token);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
