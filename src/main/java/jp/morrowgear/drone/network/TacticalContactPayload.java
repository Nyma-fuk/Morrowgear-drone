package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.TacticalContactPolicy;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record TacticalContactPayload(UUID owner, long token, long tick, String dimension,
                                     boolean truncated, List<Row> rows) implements CustomPacketPayload {
    public record Row(int entityId, double x, double y, double z, int score, int enemyThreat,
                      int playerDanger, String sourceWing, String sourceMission, long reportTick) {}

    public TacticalContactPayload {
        rows = List.copyOf(rows);
        if (rows.size() > TacticalContactPolicy.LIMIT) throw new IllegalArgumentException("Too many tactical contacts");
    }

    public static final Type<TacticalContactPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("morrowgear_drone", "tactical_contacts"));
    private static final StreamCodec<ByteBuf, String> TEXT = ByteBufCodecs.stringUtf8(128);
    public static final StreamCodec<ByteBuf, TacticalContactPayload> CODEC = new StreamCodec<>() {
        @Override public TacticalContactPayload decode(ByteBuf b) {
            UUID owner = new UUID(b.readLong(), b.readLong());
            long token = b.readLong(), tick = b.readLong();
            String dimension = TEXT.decode(b);
            boolean truncated = b.readBoolean();
            int count = b.readInt();
            if (count < 0 || count > TacticalContactPolicy.LIMIT) throw new IllegalArgumentException("Invalid tactical contact count");
            List<Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) rows.add(new Row(b.readInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), TEXT.decode(b), TEXT.decode(b), b.readLong()));
            return new TacticalContactPayload(owner, token, tick, dimension, truncated, rows);
        }
        @Override public void encode(ByteBuf b, TacticalContactPayload p) {
            b.writeLong(p.owner.getMostSignificantBits()).writeLong(p.owner.getLeastSignificantBits());
            b.writeLong(p.token).writeLong(p.tick);
            TEXT.encode(b, p.dimension);
            b.writeBoolean(p.truncated).writeInt(p.rows.size());
            for (Row row : p.rows) {
                b.writeInt(row.entityId).writeDouble(row.x).writeDouble(row.y).writeDouble(row.z);
                b.writeByte(row.score).writeByte(row.enemyThreat).writeByte(row.playerDanger);
                TEXT.encode(b, row.sourceWing == null ? "" : row.sourceWing);
                TEXT.encode(b, row.sourceMission == null ? "" : row.sourceMission);
                b.writeLong(row.reportTick);
            }
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
