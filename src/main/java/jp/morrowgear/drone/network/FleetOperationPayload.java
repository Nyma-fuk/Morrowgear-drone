package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FleetOperationPayload(UUID commandId, String operationType, int x, int z, int radius)
	implements CustomPacketPayload {
	public FleetOperationPayload(String operationType, int x, int z, int radius) {
		this(UUID.randomUUID(), operationType, x, z, radius);
	}
	public static final Type<FleetOperationPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "fleet_operation")
	);
	public static final StreamCodec<ByteBuf, FleetOperationPayload> CODEC = StreamCodec.of((buffer, value) -> {
		buffer.writeLong(value.commandId.getMostSignificantBits()); buffer.writeLong(value.commandId.getLeastSignificantBits());
		ByteBufCodecs.STRING_UTF8.encode(buffer, value.operationType);
		buffer.writeInt(value.x); buffer.writeInt(value.z); buffer.writeInt(value.radius);
	}, buffer -> new FleetOperationPayload(new UUID(buffer.readLong(), buffer.readLong()),
		ByteBufCodecs.STRING_UTF8.decode(buffer), buffer.readInt(), buffer.readInt(), buffer.readInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
