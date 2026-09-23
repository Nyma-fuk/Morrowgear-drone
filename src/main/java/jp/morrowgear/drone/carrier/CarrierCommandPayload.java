package jp.morrowgear.drone.carrier;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fixed-size, enum-only commands. No command strings or client-supplied inventory. */
public record CarrierCommandPayload(int menu, UUID ship, Action action, UUID generation, int x, int y, int z, UUID guest)
    implements CustomPacketPayload {
    public enum Action { PREVIEW_MINING, PREVIEW_COMBAT, ACTIVATE, RESUME_PREVIEW, STOP, BOARD, EXIT,
        MOVE, REFUEL, ALLOW_GUEST, REVOKE_GUEST, RECOVER_CABIN, CHARGE_WEAPON, RESERVE_BAY, RELEASE_BAY, CARGO_PAGE }
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<CarrierCommandPayload> TYPE = new Type<>(CarrierModule.id("carrier_command"));
    public static final StreamCodec<ByteBuf, CarrierCommandPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeInt(p.menu);
        writeUuid(buf, p.ship);
        buf.writeByte(p.action.ordinal());
        writeUuid(buf, p.generation);
        buf.writeInt(p.x); buf.writeInt(p.y); buf.writeInt(p.z);
        writeUuid(buf, p.guest);
    }, buf -> {
        int menu = buf.readInt();
        UUID ship = readUuid(buf);
        int action = buf.readUnsignedByte();
        if (action >= Action.values().length) throw new DecoderException("Unknown carrier action");
        return new CarrierCommandPayload(menu, ship, Action.values()[action], readUuid(buf),
            buf.readInt(), buf.readInt(), buf.readInt(), readUuid(buf));
    });
    static UUID readUuid(ByteBuf buf) { return new UUID(buf.readLong(), buf.readLong()); }
    static void writeUuid(ByteBuf buf, UUID id) { buf.writeLong(id.getMostSignificantBits()); buf.writeLong(id.getLeastSignificantBits()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
