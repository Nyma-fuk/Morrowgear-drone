package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FleetLinkPayload(UUID owner, long token, long tick, String dimension,
	boolean truncated, List<Unit> units, List<Relay> relays) implements CustomPacketPayload {
	public static final int MAX_UNITS = 256;
	public static final int MAX_RELAYS = 64;
	private static final StreamCodec<ByteBuf, String> TEXT = ByteBufCodecs.stringUtf8(128);

	public record Unit(int entityId, UUID id, String unitId, String name, String groupId,
		String role, int mode, double x, double y, double z, int batteryPercent, boolean docked,
		String status, String mission, int relayHops, int linkQuality) {}
	public record Relay(UUID id, double x, double y, double z, int energyPercent, int hops) {}

	public FleetLinkPayload {
		units = List.copyOf(units);
		relays = List.copyOf(relays);
		if (units.size() > MAX_UNITS || relays.size() > MAX_RELAYS)
			throw new IllegalArgumentException("Fleet link snapshot exceeds bounds");
	}

	public static final Type<FleetLinkPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath("morrowgear_drone", "fleet_link_snapshot"));
	public static final StreamCodec<ByteBuf, FleetLinkPayload> CODEC = new StreamCodec<>() {
		@Override public FleetLinkPayload decode(ByteBuf b) {
			UUID owner = uuid(b); long token = b.readLong(), tick = b.readLong();
			String dimension = TEXT.decode(b); boolean truncated = b.readBoolean();
			int unitCount = bounded(b.readInt(), MAX_UNITS, "unit");
			List<Unit> units = new ArrayList<>(unitCount);
			for (int i = 0; i < unitCount; i++) units.add(new Unit(b.readInt(), uuid(b), TEXT.decode(b),
				TEXT.decode(b), TEXT.decode(b), TEXT.decode(b), b.readInt(), b.readDouble(), b.readDouble(),
				b.readDouble(), b.readUnsignedByte(), b.readBoolean(), TEXT.decode(b), TEXT.decode(b),
				b.readUnsignedByte(), b.readUnsignedByte()));
			int relayCount = bounded(b.readInt(), MAX_RELAYS, "relay");
			List<Relay> relays = new ArrayList<>(relayCount);
			for (int i = 0; i < relayCount; i++) relays.add(new Relay(uuid(b), b.readDouble(), b.readDouble(),
				b.readDouble(), b.readUnsignedByte(), b.readUnsignedByte()));
			return new FleetLinkPayload(owner, token, tick, dimension, truncated, units, relays);
		}

		@Override public void encode(ByteBuf b, FleetLinkPayload p) {
			uuid(b, p.owner); b.writeLong(p.token).writeLong(p.tick); TEXT.encode(b, p.dimension);
			b.writeBoolean(p.truncated).writeInt(p.units.size());
			for (Unit unit : p.units) {
				b.writeInt(unit.entityId); uuid(b, unit.id); TEXT.encode(b, unit.unitId); TEXT.encode(b, unit.name);
				TEXT.encode(b, unit.groupId); TEXT.encode(b, unit.role); b.writeInt(unit.mode);
				b.writeDouble(unit.x).writeDouble(unit.y).writeDouble(unit.z).writeByte(unit.batteryPercent);
				b.writeBoolean(unit.docked); TEXT.encode(b, unit.status); TEXT.encode(b, unit.mission);
				b.writeByte(unit.relayHops).writeByte(unit.linkQuality);
			}
			b.writeInt(p.relays.size());
			for (Relay relay : p.relays) {
				uuid(b, relay.id); b.writeDouble(relay.x).writeDouble(relay.y).writeDouble(relay.z);
				b.writeByte(relay.energyPercent).writeByte(relay.hops);
			}
		}
	};

	private static int bounded(int count, int maximum, String label) {
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
		return count;
	}
	private static UUID uuid(ByteBuf b) { return new UUID(b.readLong(), b.readLong()); }
	private static void uuid(ByteBuf b, UUID id) {
		b.writeLong(id.getMostSignificantBits()).writeLong(id.getLeastSignificantBits());
	}
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
