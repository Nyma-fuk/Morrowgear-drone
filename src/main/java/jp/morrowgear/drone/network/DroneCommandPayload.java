package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DroneCommandPayload(UUID commandId, int entityId, String action) implements CustomPacketPayload {
	public DroneCommandPayload(int entityId, String action) {
		this(UUID.randomUUID(), entityId, action);
	}

	public static final Type<DroneCommandPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "drone_command")
	);
	public static final StreamCodec<ByteBuf, DroneCommandPayload> CODEC = StreamCodec.of((buffer, value) -> {
		buffer.writeLong(value.commandId.getMostSignificantBits());
		buffer.writeLong(value.commandId.getLeastSignificantBits());
		ByteBufCodecs.INT.encode(buffer, value.entityId);
		ByteBufCodecs.STRING_UTF8.encode(buffer, value.action);
	}, buffer -> new DroneCommandPayload(new UUID(buffer.readLong(), buffer.readLong()),
		ByteBufCodecs.INT.decode(buffer), ByteBufCodecs.STRING_UTF8.decode(buffer)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
