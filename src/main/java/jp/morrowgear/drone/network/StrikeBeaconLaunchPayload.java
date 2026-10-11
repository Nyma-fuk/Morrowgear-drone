package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StrikeBeaconLaunchPayload(boolean offhand) implements CustomPacketPayload {
	public static final Type<StrikeBeaconLaunchPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "strike_beacon_launch"));
	public static final StreamCodec<ByteBuf, StrikeBeaconLaunchPayload> CODEC = StreamCodec.of(
		(buffer, value) -> buffer.writeBoolean(value.offhand),
		buffer -> new StrikeBeaconLaunchPayload(buffer.readBoolean()));

	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
