package jp.morrowgear.drone.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.RecoverySnapshotPolicy;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RecoverySnapshotPayload(UUID owner, long token, long tick, boolean truncated, List<Row> rows) implements CustomPacketPayload {
    public record Row(String target, String unit, String dimension, String position, String rescuer,
        String engineer, String stage, String destination, String reason, String originalMission, boolean destinationKnown) {
        public java.util.Optional<String> destinationPosition() {
            return destination.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(destination);
        }
    }
    public RecoverySnapshotPayload {
        rows = List.copyOf(rows);
        if (rows.size() > RecoverySnapshotPolicy.LIMIT) throw new IllegalArgumentException("Recovery snapshot too large");
    }
    public static final Type<RecoverySnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("morrowgear_drone", "recovery_snapshot"));
    private static final StreamCodec<ByteBuf, String> TEXT = ByteBufCodecs.stringUtf8(128);
    public static final StreamCodec<ByteBuf, RecoverySnapshotPayload> CODEC = new StreamCodec<>() {
        @Override public RecoverySnapshotPayload decode(ByteBuf b) {
            UUID owner = new UUID(b.readLong(), b.readLong());
            long token = b.readLong(), tick = b.readLong();
            boolean truncated = b.readBoolean();
            int count = b.readInt();
            if (count < 0 || count > RecoverySnapshotPolicy.LIMIT) throw new IllegalArgumentException("Invalid recovery count");
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) rows.add(new Row(TEXT.decode(b), TEXT.decode(b), TEXT.decode(b), TEXT.decode(b),
                TEXT.decode(b), TEXT.decode(b), TEXT.decode(b), TEXT.decode(b), TEXT.decode(b), TEXT.decode(b), b.readBoolean()));
            return new RecoverySnapshotPayload(owner, token, tick, truncated, rows);
        }
        @Override public void encode(ByteBuf b, RecoverySnapshotPayload p) {
            b.writeLong(p.owner.getMostSignificantBits()).writeLong(p.owner.getLeastSignificantBits());
            b.writeLong(p.token).writeLong(p.tick).writeBoolean(p.truncated).writeInt(p.rows.size());
            for (Row row : p.rows) {
                for (String text : List.of(row.target, row.unit, row.dimension, row.position,
                    row.rescuer, row.engineer, row.stage, row.destination, row.reason, row.originalMission))
                    TEXT.encode(b, text.length() > 128 ? text.substring(0, 128) : text);
                b.writeBoolean(row.destinationKnown);
            }
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
