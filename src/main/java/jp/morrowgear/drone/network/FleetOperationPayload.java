package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FleetOperationPayload(String operationType, int x, int z, int radius)
	implements CustomPacketPayload {
	public static final Type<FleetOperationPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "fleet_operation")
	);
	public static final StreamCodec<ByteBuf, FleetOperationPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, FleetOperationPayload::operationType,
		ByteBufCodecs.INT, FleetOperationPayload::x,
		ByteBufCodecs.INT, FleetOperationPayload::z,
		ByteBufCodecs.INT, FleetOperationPayload::radius,
		FleetOperationPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
