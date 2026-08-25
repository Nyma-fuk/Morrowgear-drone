package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DroneCommandPayload(int entityId, String action) implements CustomPacketPayload {
	public static final Type<DroneCommandPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "drone_command")
	);
	public static final StreamCodec<ByteBuf, DroneCommandPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.INT, DroneCommandPayload::entityId,
		ByteBufCodecs.STRING_UTF8, DroneCommandPayload::action,
		DroneCommandPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
