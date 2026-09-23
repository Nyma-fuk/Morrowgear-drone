package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source wiring checks complement executable policy tests; they do not replace listening. */
final class DroneAudioClientContractTest {
	private static String source(String path) throws IOException {
		return Files.readString(Path.of("src/" + path));
	}

	@Test void weaponLoopsAreSpatialBoundedAndRemovedOnDisconnect() throws IOException {
		String code = source("client/java/jp/morrowgear/drone/client/DroneAudioController.java");
		assertTrue(code.contains("ClientPlayConnectionEvents.DISCONNECT.register"));
		assertTrue(code.contains("SoundInstance.Attenuation.LINEAR"));
		assertTrue(code.contains("relative = false"));
		assertTrue(code.contains("x = entity.getX()"));
		assertTrue(code.contains("MAX_CANNON_EMITTERS"));
		assertTrue(code.contains("MAX_MOTOR_EMITTERS"));
		assertTrue(code.contains("CANNONS.clear()"));
		assertTrue(code.contains("MOTORS.clear()"));
		assertTrue(code.contains("!audible(entity)"));
		assertTrue(code.contains("drone.combatShotTick()"));
	}

	@Test void serverDoesNotDuplicateRotaryLoopOrReuseLaunchForIgnition() throws IOException {
		String drone = source("main/java/jp/morrowgear/drone/DroneEntity.java");
		String missile = source("main/java/jp/morrowgear/drone/MorrowgearMissileEntity.java");
		assertFalse(drone.contains("AUTOCANNON_BURST_SOUND"));
		assertFalse(drone.contains("AUTOCANNON_FIRE_SOUND"));
		assertTrue(drone.contains("AUTOCANNON_IMPACT_SOUND"));
		assertTrue(missile.contains("MISSILE_IGNITION_SOUND"));
		assertTrue(missile.contains("MISSILE_EXPLOSION_SOUND"));
		assertTrue(missile.contains("MissileAudioController.detonated"));
	}
}
