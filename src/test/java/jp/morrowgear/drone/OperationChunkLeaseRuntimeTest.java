package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OperationChunkLeaseRuntimeTest {
	@Test void dronesAndStationsShareOneChunkTicketRefreshPath() throws Exception {
		String drone = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String station = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/SolarServiceStationEntity.java"));
		assertTrue(drone.contains("OperationChunkLeaseRuntime.refresh"));
		assertTrue(station.contains("OperationChunkLeaseRuntime.refresh"));
		assertFalse(drone.contains("getChunkSource().addTicketWithRadius"));
		assertFalse(station.contains("getChunkSource().addTicketWithRadius"));
	}

	@Test void runtimeIsWorldScopedAndClearedOnServerStop() throws Exception {
		String runtime = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/OperationChunkLeaseRuntime.java"));
		assertTrue(runtime.contains("level == key.level"));
		assertTrue(runtime.contains("previous == tick"));
		assertTrue(runtime.contains("SERVER_STOPPED"));
	}
}
