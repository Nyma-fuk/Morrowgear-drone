package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CargoLiftCommandPayload(UUID commandId, Action action, long source,
        long destination, String missionId) implements CustomPacketPayload {
    public enum Action { START, CANCEL }

    public static CargoLiftCommandPayload start(long source, long destination) {
        return new CargoLiftCommandPayload(UUID.randomUUID(), Action.START, source, destination, "");
    }

    public static CargoLiftCommandPayload cancel(String missionId) {
        return new CargoLiftCommandPayload(UUID.randomUUID(), Action.CANCEL, 0L, 0L, missionId == null ? "" : missionId);
    }

    public static final Type<CargoLiftCommandPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "cargo_lift_command"));
    public static final StreamCodec<ByteBuf, CargoLiftCommandPayload> CODEC = StreamCodec.of((buffer, value) -> {
        buffer.writeLong(value.commandId.getMostSignificantBits());
        buffer.writeLong(value.commandId.getLeastSignificantBits());
        ByteBufCodecs.VAR_INT.encode(buffer, value.action.ordinal());
        buffer.writeLong(value.source);
        buffer.writeLong(value.destination);
        ByteBufCodecs.STRING_UTF8.encode(buffer, value.missionId);
    }, buffer -> new CargoLiftCommandPayload(new UUID(buffer.readLong(), buffer.readLong()),
        Action.values()[Math.clamp(ByteBufCodecs.VAR_INT.decode(buffer), 0, Action.values().length - 1)],
        buffer.readLong(), buffer.readLong(), ByteBufCodecs.STRING_UTF8.decode(buffer)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
