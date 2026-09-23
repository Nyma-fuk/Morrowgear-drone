package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DockAllocationRuntimeTest {
	@Test void cacheAgeMustBeNonnegativeAndInsideRefreshWindow() {
		assertTrue(DockAllocationRuntime.fresh(100, 100));
		assertTrue(DockAllocationRuntime.fresh(119, 100));
		assertFalse(DockAllocationRuntime.fresh(120, 100));
		assertFalse(DockAllocationRuntime.fresh(99, 100));
	}

	@Test void cacheIsWorldScopedBoundedAndClearedWithTheServer() throws Exception {
		String runtime = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DockAllocationRuntime.java"));
		assertTrue(runtime.contains("level == key.level"));
		assertTrue(runtime.contains("System.identityHashCode(level)"));
		assertTrue(runtime.contains("return size() > CACHE_LIMIT"));
		assertTrue(runtime.contains("ServerLifecycleEvents.SERVER_STOPPED.register(server -> CACHE.clear())"));
		assertFalse(runtime.contains("level.dimension().toString()"));
		String initializer = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
		assertTrue(initializer.contains("DockAllocationRuntime.register()"));
	}
}
