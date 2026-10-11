package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class FieldRelayDeploymentPolicyTest {
	@Test void explicitDeploymentUsesTheCommandPositionOnlyForTheInitialAnchor() {
		Vec3 command = new Vec3(32, 70, -18);
		assertEquals(command, FieldRelayDeploymentPolicy.resolve(List.of(), List.of(), command));
	}

	@Test void persistedAnchorDoesNotFollowAPlayerWhoMovesLater() {
		Vec3 anchor = new Vec3(32, 70, -18);
		Vec3 movedPlayer = new Vec3(640, 12, 900);
		assertEquals(anchor, FieldRelayDeploymentPolicy.resolve(List.of(anchor),
			List.of(new Vec3(120, 98, 20)), movedPlayer));
	}

	@Test void legacyAuthorizedMeshRecoversItsOriginFromAircraftInsteadOfThePlayer() {
		Vec3 recovered = FieldRelayDeploymentPolicy.resolve(List.of(), List.of(
			new Vec3(-88, 100, 0), new Vec3(88, 100, 0), new Vec3(0, 100, 88),
			new Vec3(0, 100, -88)), new Vec3(1000, 64, 1000));
		assertEquals(new Vec3(0, 100, 0), recovered);
	}
}
