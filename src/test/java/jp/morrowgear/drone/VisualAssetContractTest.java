package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class VisualAssetContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/morrowgear_drone");
	private static final List<String> EQUIPMENT_V3_ICONS = List.of("raw_morrow_composite", "morrow_alloy",
		"lightweight_frame", "basic_control_board", "flight_actuator", "standard_battery_pack",
		"reinforced_battery_pack", "high_density_battery_pack", "autocannon_module", "laser_module",
		"missile_module", "tactical_visor");
	private static final Path BEAM_RENDER_TYPE = Path.of(
		"src/client/java/net/minecraft/client/renderer/rendertype/MorrowgearRenderTypes.java");

	@Test
	void allApprovedItemIconsRemainHighResolutionAndTransparent() throws Exception {
		for (String item : List.of("field_drone_unit", "scout_module", "cargo_module", "engineer_module",
			"security_module", "dock_item", "controller", "raw_morrow_composite", "morrow_alloy",
			"lightweight_frame", "basic_control_board", "flight_actuator", "standard_battery_pack",
			"reinforced_battery_pack", "high_density_battery_pack", "autocannon_module", "laser_module",
			"missile_module", "tactical_visor", "salvage_module", "recovery_tool", "power_cell")) {
			BufferedImage image = ImageIO.read(ASSETS.resolve("textures/item/" + item + ".png").toFile());
			assertEquals(64, image.getWidth(), item + " width");
			assertEquals(64, image.getHeight(), item + " height");
			assertTrue(image.getColorModel().hasAlpha(), item + " alpha");
			assertTrue((image.getRGB(0, 0) >>> 24) == 0, item + " transparent padding");
			assertTrue(hasVisiblePixel(image), item + " visible content");
		}
	}

	@Test
	void equipmentV3ModelsUseTwelveDistinctMorrowgearTextures() throws Exception {
		Set<Integer> imageHashes = new HashSet<>();
		for (String item : EQUIPMENT_V3_ICONS) {
			String model = Files.readString(ASSETS.resolve("models/item/" + item + ".json"));
			assertTrue(model.contains("morrowgear_drone:item/" + item), item + " custom texture reference");
			byte[] png = Files.readAllBytes(ASSETS.resolve("textures/item/" + item + ".png"));
			assertTrue(imageHashes.add(java.util.Arrays.hashCode(png)), item + " duplicates another V3 icon");
		}
		assertEquals(EQUIPMENT_V3_ICONS.size(), imageHashes.size());
	}

	private static boolean hasVisiblePixel(BufferedImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) >>> 24) > 128) return true;
			}
		}
		return false;
	}

	@Test
	void controllerSeparatesTheApprovedGuiIconFromTheHeldModel() throws Exception {
		String definition = Files.readString(ASSETS.resolve("items/controller.json"));
		String gui = Files.readString(ASSETS.resolve("models/item/controller.json"));
		String held = Files.readString(ASSETS.resolve("models/item/controller_in_hand.json"));
		assertTrue(definition.contains("minecraft:display_context"));
		assertTrue(definition.contains("controller_in_hand"));
		assertTrue(gui.contains("minecraft:item/generated"));
		assertTrue(held.contains("\"elements\""));
		assertTrue(held.contains("\"firstperson_righthand\""));
		assertTrue(held.contains("\"thirdperson_righthand\""));
	}

	@Test
	void dockPartsResolveToTheApprovedNineBlockLayout() throws Exception {
		BufferedImage master = ImageIO.read(ASSETS.resolve("textures/block/dock_v2_master.png").toFile());
		assertEquals(144, master.getWidth(), "dock master width");
		assertEquals(144, master.getHeight(), "dock master height");
		for (int i = 0; i < 9; i++) {
			String state = Files.readString(ASSETS.resolve("blockstates/dock_part_" + i + ".json"));
			assertTrue(state.contains("morrowgear_drone:block/dock_surface_" + i), "dock part " + i);
			BufferedImage tile = ImageIO.read(ASSETS.resolve("textures/block/dock_top_" + i + ".png").toFile());
			assertEquals(48, tile.getWidth(), "dock tile " + i + " width");
			assertEquals(48, tile.getHeight(), "dock tile " + i + " height");
		}
		String center = Files.readString(ASSETS.resolve("models/block/dock_center.json"));
		String corner = Files.readString(ASSETS.resolve("models/block/dock_corner.json"));
		assertTrue(center.contains("4.35"), "dock center remains a low landing basin");
		assertTrue(corner.contains("5.0"), "dock corners remain below the open approach corridor");
		assertTrue(!corner.contains("\"to\":[7.2,5.35,7.2]"),
			"dock corners do not retain an orientation-dependent legacy post");
	}

	@Test
	void approvedPolygonMeshesExistForEveryVisibleDroneFamily() throws Exception {
		for (String role : List.of("scout", "engineer", "field", "guard")) {
			Path mesh = ASSETS.resolve("models/entity/family_a_" + role + ".mgm");
			assertTrue(Files.size(mesh) > 500_000, role + " mesh detail");
			byte[] magic = Files.readAllBytes(mesh);
			assertEquals((byte) 'M', magic[0]);
			assertEquals((byte) 'G', magic[1]);
			assertEquals((byte) 'M', magic[2]);
			assertEquals((byte) '2', magic[3]);
		}
	}

	@Test
	void salvageUsesTheApprovedDetailedFourTurbineMesh() throws Exception {
		Path mesh = ASSETS.resolve("models/entity/family_a_salvage.mgm");
		assertTrue(Files.size(mesh) > 500_000, "salvage mesh detail");
		byte[] magic = Files.readAllBytes(mesh);
		assertEquals((byte) 'M', magic[0]);
		assertEquals((byte) 'G', magic[1]);
		assertEquals((byte) 'M', magic[2]);
		assertEquals((byte) '3', magic[3]);
	}

	@Test
	void solarServiceFamilyUsesTheApprovedDetailedMeshes() throws Exception {
		Path station = ASSETS.resolve("models/entity/solar_service_station.mgm");
		Path relay = ASSETS.resolve("models/entity/charging_relay.mgm");
		assertTrue(Files.size(station) > 8_000_000, "station ventral cassettes and lift emitters");
		assertTrue(Files.size(relay) > 500_000, "relay monocoque, visor, fins, and charging coil");
		for (Path mesh : List.of(station, relay)) {
			byte[] magic = Files.readAllBytes(mesh);
			assertEquals((byte) 'M', magic[0]);
			assertEquals((byte) 'G', magic[1]);
			assertEquals((byte) 'M', magic[2]);
			assertEquals((byte) '2', magic[3]);
		}
		String loader = Files.readString(Path.of(
			"src/client/java/jp/morrowgear/drone/client/DroneMesh.java"));
		assertTrue(loader.contains("MAX_VERTEX_COUNT = 400_000"),
			"the approved station must remain loadable without removing the corruption guard");
	}

	@Test
	void energyBeamUsesTheDedicatedAnimatedShaderContract() throws Exception {
		Path shaders = ASSETS.resolve("shaders/core");
		String vertex = Files.readString(shaders.resolve("morrowgear_energy_beam.vsh"));
		String fragment = Files.readString(shaders.resolve("morrowgear_energy_beam.fsh"));
		String renderType = Files.readString(BEAM_RENDER_TYPE);
		assertTrue(vertex.contains("beamUv"));
		assertTrue(fragment.contains("GameTime"));
		assertTrue(fragment.contains("valueNoise"));
		assertTrue(fragment.contains("endpointFade"));
		assertTrue(fragment.contains("apply_fog"));
		assertTrue(renderType.contains("BlendFunction.LIGHTNING"));
		assertTrue(renderType.contains("CompareOp.LESS_THAN_OR_EQUAL"));
		assertTrue(renderType.contains("CompareOp.ALWAYS_PASS"));
		assertTrue(renderType.contains("visibleEnergyCore"));
		assertTrue(renderType.contains("false"));
		String effects = Files.readString(Path.of(
			"src/client/java/jp/morrowgear/drone/client/CombatEffectRenderer.java"));
		assertTrue(effects.contains("visibleBeamSpans"));
		assertTrue(effects.contains("ClipContext.Block.COLLIDER"));
		assertTrue(effects.contains("renderVisibleLaser"));
		assertTrue(effects.contains("renderVisibleTracer"));
		assertTrue(effects.contains("renderChargingBeam"));
		String relay = Files.readString(Path.of(
			"src/client/java/jp/morrowgear/drone/client/ChargingRelayRenderer.java"));
		assertTrue(relay.contains("visibleEnergyCore"));
	}
}
