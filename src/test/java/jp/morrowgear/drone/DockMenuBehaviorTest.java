package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Wiring contract only. Real menu behavior is checked explicitly by the runtime command. */
final class DockMenuBehaviorTest {
	@Test void runtimeVerificationIsOptInGuardedAndRestoresItsSnapshotsBeforeReporting() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DockMenuRuntimeVerification.java"));
		assertTrue(source.contains("public static void register()"));
		assertTrue(source.contains("Commands.literal(\"morrowgear_dock_verify\")"));
		assertTrue(source.contains("public static int run(ServerPlayer player, DockBlockEntity dock)"));
		assertTrue(source.contains("player.isCreative() && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)"));
		assertTrue(source.contains("dock.getLevel() == player.level()"));
		assertTrue(source.contains("dock.isOwnedBy(player.getUUID()) && dock.stillValid(player)"));
		assertTrue(source.contains("player.containerMenu == player.inventoryMenu"));
		assertTrue(source.contains("if (player.containerMenu == menu) player.closeContainer()"));
		assertTrue(source.contains("menu.clicked(slot, button, input, player)"));
		assertTrue(source.contains("ContainerInput.QUICK_CRAFT"));
		assertTrue(source.contains("} finally {"));
		assertTrue(source.contains("dock.restoreServiceSnapshotForVerification(savedDock.copy(), level.registryAccess())"));
		assertTrue(source.contains("savedPlayer.restore(player)"));
		assertTrue(source.indexOf("savedPlayer.verify(player)") < source.indexOf("[MORROWGEAR DOCK VERIFY] PASS"));
		assertFalse(source.contains("addFreshEntity"));
		assertFalse(source.contains("setGameMode("));
		assertFalse(source.contains("Bootstrap.bootStrap"));
	}
}
