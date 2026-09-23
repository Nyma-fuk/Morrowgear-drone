package jp.morrowgear.drone;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public record StoredDroneState(String unitId, DroneRole role, BatteryTier batteryTier,
	int flightPower, int weaponPower, String groupId, SecurityLoadout securityLoadout,
	int gunAmmo, int missiles, int laserHeat, float health, long dockPos,
	int propulsionCondition, int sensorCondition, int payloadCondition, int capacityTier) {
	private static final String ROOT = "MorrowgearStoredDrone";
	private static final int FORMAT = 3;

	public StoredDroneState(String unitId, DroneRole role, BatteryTier batteryTier,
		int flightPower, int weaponPower, String groupId, SecurityLoadout securityLoadout,
		int gunAmmo, int missiles, int laserHeat, float health, long dockPos,
		int propulsionCondition, int sensorCondition, int payloadCondition) {
		this(unitId, role, batteryTier, flightPower, weaponPower, groupId, securityLoadout,
			gunAmmo, missiles, laserHeat, health, dockPos, propulsionCondition, sensorCondition, payloadCondition, 0);
	}

	public static boolean isStoredDrone(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(ROOT);
	}

	public void write(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag tag = new CompoundTag();
			tag.putInt("Format", FORMAT);
			tag.putString("UnitId", unitId);
			tag.putString("Role", role.id());
			tag.putString("BatteryTier", batteryTier.id());
			tag.putInt("FlightPower", batteryTier.clamp(flightPower));
			tag.putInt("CapacityTier", PayloadCapacity.tier(capacityTier));
			tag.putInt("WeaponPower", PayloadCapacity.power(weaponPower, capacityTier));
			tag.putString("Group", DroneStatePolicy.group(groupId));
			tag.putString("SecurityLoadout", securityLoadout.id());
			tag.putInt("GunAmmo", Math.max(0, gunAmmo));
			tag.putInt("Missiles", Math.max(0, missiles));
			tag.putInt("LaserHeat", Math.max(0, Math.min(1000, laserHeat)));
			tag.putFloat("Health", Math.max(1.0f, health));
			tag.putLong("DockPos", dockPos);
			tag.putInt("PropulsionCondition", clampCondition(propulsionCondition));
			tag.putInt("SensorCondition", clampCondition(sensorCondition));
			tag.putInt("PayloadCondition", clampCondition(payloadCondition));
			root.put(ROOT, tag);
		});
	}

	public static StoredDroneState read(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag root = data.copyTag();
		CompoundTag tag = root.getCompound(ROOT).orElse(null);
		int format = tag == null ? 0 : tag.getIntOr("Format", 0);
		if (tag == null || format < 1 || format > FORMAT) return null;
		BatteryTier tier = BatteryTier.byId(tag.getStringOr("BatteryTier", BatteryTier.STANDARD.id()));
		return new StoredDroneState(
			tag.getStringOr("UnitId", "MG-DRN-UNSET"),
			DroneRole.byId(tag.getStringOr("Role", DroneRole.FIELD.id())),
			tier,
			tier.clamp(tag.getIntOr("FlightPower", 0)),
			PayloadCapacity.power(tag.getIntOr("WeaponPower", 0), tag.getIntOr("CapacityTier", 0)),
			DroneStatePolicy.group(tag.getStringOr("Group", "ALPHA")),
			SecurityLoadout.byId(tag.getStringOr("SecurityLoadout", SecurityLoadout.UNARMED.id())),
			Math.max(0, tag.getIntOr("GunAmmo", 0)),
			Math.max(0, tag.getIntOr("Missiles", 0)),
			Math.max(0, Math.min(1000, tag.getIntOr("LaserHeat", 0))),
			Math.max(1.0f, tag.getFloatOr("Health", 1.0f)),
			tag.getLongOr("DockPos", Long.MIN_VALUE),
			clampCondition(tag.getIntOr("PropulsionCondition", DroneSubsystemPolicy.MAX)),
			clampCondition(tag.getIntOr("SensorCondition", DroneSubsystemPolicy.MAX)),
			clampCondition(tag.getIntOr("PayloadCondition", DroneSubsystemPolicy.MAX)),
			PayloadCapacity.tier(tag.getIntOr("CapacityTier", 0)));
	}

	private static int clampCondition(int condition) {
		return Math.max(0, Math.min(DroneSubsystemPolicy.MAX, condition));
	}
}
