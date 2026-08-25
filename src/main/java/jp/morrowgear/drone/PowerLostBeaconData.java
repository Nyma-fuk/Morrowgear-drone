package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class PowerLostBeaconData extends SavedData {
	private static final Codec<PowerLostBeaconData> CODEC = Codec.unboundedMap(Codec.STRING, Beacon.CODEC)
		.fieldOf("beacons").xmap(PowerLostBeaconData::new, data -> data.beacons).codec();
	private static final SavedDataType<PowerLostBeaconData> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "power_lost_beacons"),
		PowerLostBeaconData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	private final Map<String, Beacon> beacons;

	public PowerLostBeaconData() {
		this(new HashMap<>());
	}

	private PowerLostBeaconData(Map<String, Beacon> beacons) {
		this.beacons = new HashMap<>(beacons);
	}

	public static PowerLostBeaconData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public void update(UUID owner, String unitId, String dimension, double x, double y, double z, long tick) {
		String key = owner + "/" + unitId;
		Beacon next = new Beacon(owner.toString(), unitId, dimension, x, y, z, tick);
		if (next.equals(beacons.put(key, next))) return;
		setDirty();
	}

	public void remove(UUID owner, String unitId) {
		if (beacons.remove(owner + "/" + unitId) != null) setDirty();
	}

	public List<Beacon> forOwner(UUID owner) {
		String ownerId = owner.toString();
		return beacons.values().stream().filter(beacon -> beacon.owner().equals(ownerId))
			.sorted(java.util.Comparator.comparing(Beacon::unitId)).toList();
	}

	public record Beacon(String owner, String unitId, String dimension, double x, double y,
		double z, long tick) {
		private static final Codec<Beacon> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("owner").forGetter(Beacon::owner),
			Codec.STRING.fieldOf("unit").forGetter(Beacon::unitId),
			Codec.STRING.fieldOf("dimension").forGetter(Beacon::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(Beacon::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Beacon::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Beacon::z),
			Codec.LONG.fieldOf("tick").forGetter(Beacon::tick)
		).apply(instance, Beacon::new));
	}
}
