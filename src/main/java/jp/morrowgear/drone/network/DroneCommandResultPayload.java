package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-authoritative per-aircraft command lifecycle update. */
public record DroneCommandResultPayload(UUID commandId, UUID unitId, int entityId, Status status,
        String action, String reason, long serverTick) implements CustomPacketPayload {
    public static final int TEXT_LIMIT = 160;
    public enum Status { ACCEPTED, HELD, REJECTED, EXECUTING, COMPLETED, FAILED }
    public static final Type<DroneCommandResultPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "drone_command_result"));
    public static final StreamCodec<ByteBuf, DroneCommandResultPayload> CODEC = StreamCodec.of((buffer, value) -> {
        uuid(buffer, value.commandId); uuid(buffer, value.unitId);
        buffer.writeInt(value.entityId); buffer.writeByte(value.status.ordinal());
        ByteBufCodecs.stringUtf8(TEXT_LIMIT).encode(buffer, bounded(value.action));
        ByteBufCodecs.stringUtf8(TEXT_LIMIT).encode(buffer, bounded(value.reason));
        buffer.writeLong(value.serverTick);
    }, buffer -> {
        UUID command = uuid(buffer), unit = uuid(buffer);
        int entity = buffer.readInt(), ordinal = buffer.readUnsignedByte();
        if (ordinal >= Status.values().length) throw new DecoderException("Unknown command result");
        String action = ByteBufCodecs.stringUtf8(TEXT_LIMIT).decode(buffer);
        String reason = ByteBufCodecs.stringUtf8(TEXT_LIMIT).decode(buffer);
        long tick = buffer.readLong();
        if (tick < 0) throw new DecoderException("Negative command result time");
        return new DroneCommandResultPayload(command, unit, entity, Status.values()[ordinal], action, reason, tick);
    });

    public DroneCommandResultPayload {
        commandId = commandId == null ? new UUID(0, 0) : commandId;
        unitId = unitId == null ? new UUID(0, 0) : unitId;
        action = bounded(action); reason = bounded(reason);
    }
    private static String bounded(String value) {
        String text = value == null ? "" : value;
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT);
    }
    private static UUID uuid(ByteBuf buffer) { return new UUID(buffer.readLong(), buffer.readLong()); }
    private static void uuid(ByteBuf buffer, UUID value) {
        buffer.writeLong(value.getMostSignificantBits()); buffer.writeLong(value.getLeastSignificantBits());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
