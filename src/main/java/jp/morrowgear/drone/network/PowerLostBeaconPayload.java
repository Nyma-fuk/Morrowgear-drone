package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PowerLostBeaconPayload(String unitId, String dimension, double x, double y,
	double z, boolean active, long serverTick) implements CustomPacketPayload {
	public static final Type<PowerLostBeaconPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "power_lost_beacon"));
	public static final StreamCodec<ByteBuf, PowerLostBeaconPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, PowerLostBeaconPayload::unitId,
		ByteBufCodecs.STRING_UTF8, PowerLostBeaconPayload::dimension,
		ByteBufCodecs.DOUBLE, PowerLostBeaconPayload::x,
		ByteBufCodecs.DOUBLE, PowerLostBeaconPayload::y,
		ByteBufCodecs.DOUBLE, PowerLostBeaconPayload::z,
		ByteBufCodecs.BOOL, PowerLostBeaconPayload::active,
		ByteBufCodecs.LONG, PowerLostBeaconPayload::serverTick,
		PowerLostBeaconPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
