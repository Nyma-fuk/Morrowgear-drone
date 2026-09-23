package jp.morrowgear.drone;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Debris only. Launch, impact and the actual explosion remain owned by the missile. */
public final class MissileAudioController {
	private static final Map<ServerLevel, MissileAudioTimeline> WORLDS = new WeakHashMap<>();
	private static boolean registered;

	private MissileAudioController() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ServerTickEvents.END_LEVEL_TICK.register(MissileAudioController::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> WORLDS.clear());
	}

	public static void detonated(ServerLevel level, UUID missileId, Vec3 position, int simultaneousMissiles) {
		WORLDS.computeIfAbsent(level, ignored -> new MissileAudioTimeline()).detonated(missileId,
			level.getGameTime(), position.x, position.y, position.z, simultaneousMissiles);
	}

	private static void tick(ServerLevel level) {
		MissileAudioTimeline timeline = WORLDS.get(level);
		if (timeline == null) return;
		var cues = timeline.tick(level.getGameTime());
		for (MissileAudioTimeline.Cue cue : cues) {
			level.playSound(null, cue.x(), cue.y(), cue.z(), DroneAudioSounds.MISSILE_DEBRIS, SoundSource.PLAYERS,
				0.40f * Math.min(cue.gain(), DroneAudioPolicy.fleetGain(cues.size())), 1.0f);
		}
		if (timeline.size() == 0) WORLDS.remove(level);
	}
}
