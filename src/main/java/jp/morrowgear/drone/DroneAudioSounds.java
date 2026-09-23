package jp.morrowgear.drone;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** ID/range descriptors only. The parent task owns registration in MorrowgearDrone. */
public final class DroneAudioSounds {
	public static final SoundEvent LASER_DISCHARGE = event("laser_discharge", 48);
	public static final SoundEvent MISSILE_LAUNCH = event("missile_launch", 64);
	public static final SoundEvent MISSILE_IMPACT = event("missile_impact", 48);
	public static final SoundEvent MISSILE_EXPLOSION = event("missile_explosion", 80);
	public static final SoundEvent MISSILE_DEBRIS = event("missile_debris", 48);

	private DroneAudioSounds() {}

	private static SoundEvent event(String name, float range) {
		return SoundEvent.createFixedRangeEvent(Identifier.fromNamespaceAndPath("morrowgear_drone", name), range);
	}
}
