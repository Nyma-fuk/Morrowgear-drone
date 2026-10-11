package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FieldRelayHudContractTest {
	@Test void visorUsesTheAuthoritativeMeshSnapshotAndShowsSpatialCoverage() throws IOException {
		String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/VisorHudOverlay.java"));
		String client = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/MorrowgearDroneClient.java"));
		assertTrue(source.contains("FleetLinkClient.mesh(client)"));
		assertTrue(source.contains("通信メッシュ / "));
		assertTrue(!source.contains("drawMeshCoverageRing"));
		assertTrue(source.contains("relay.parentId()"));
		assertTrue(source.contains("FIELD不足 +"));
		assertTrue(source.contains("drawAirspaceMesh"));
		assertTrue(source.contains("3D AIRSPACE / NODE "));
		assertTrue(source.contains("/ LINKED "));
		assertTrue(source.contains("TERRAIN_REFRESH_TICKS"));
		assertTrue(source.contains("drawAirspaceMeshDome"));
		assertTrue(source.contains("VisorAirspaceMapPolicy.domeBaseY"));
		assertTrue(!source.contains("drawAirspaceCoverageSphere"));
		assertTrue(source.contains("drawAirspaceTerrainSlab"));
		assertTrue(source.contains("VisorAirspaceMapPolicy.panel(width, height)"));
		assertTrue(source.contains("◇ MESH DOME   ◈ FIELD   △ UNIT"));
		assertTrue(!source.contains("RELAY LINKS / 3D"));
		assertTrue(!source.contains("AIR TRAFFIC "));
		assertTrue(source.contains("getHeight(Heightmap.Types.WORLD_SURFACE, exactX, exactZ)"));
		assertTrue(!source.contains("var ground = VisorAirspaceMapPolicy.project"));
		assertTrue(source.contains("else drawFieldMesh(g, client, width, height)"));
		assertTrue(source.indexOf("drawAirspaceMesh(g, client") < source.indexOf("drawFieldMesh(g, client"));
		assertTrue(source.contains("LINK PENDING"));
		assertTrue(source.contains("relay.linkQuality() < 55"));
		assertTrue(!source.contains("String type = relay.duty()"));
		assertTrue(!source.contains("drawAirspaceAccessLink"));
		assertTrue(!source.contains("accessRelay"));
		assertTrue(source.contains("drawAirspaceNode(g, target.x(), target.y(), 3"));
		assertTrue(source.contains("config.airspaceMapEnabled"));
		assertTrue(client.contains("InputConstants.KEY_M"));
		assertTrue(client.contains("VISOR_HUD.toggleAirspaceMap(client)"));
		assertTrue(source.contains("reserveOperationSubtitle"));
	}
}
