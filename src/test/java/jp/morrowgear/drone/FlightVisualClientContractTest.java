package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source wiring checks supplement executable policy/history tests; they are not visual QA. */
final class FlightVisualClientContractTest {
	private static String clientSource(String name) throws IOException {
		return Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/" + name + ".java"));
	}

	@Test
	void trailRootsUseSharedHardpointsAndOnlyTheInterpolatedNewestPairFollowsTheHull() throws IOException {
		String source = clientSource("FormationTrailController");
		assertTrue(source.contains("DroneHardpoints.EXHAUST_LEFT"));
		assertTrue(source.contains("DroneHardpoints.EXHAUST_RIGHT"));
		assertTrue(source.contains("DroneHardpoints.worldPosition(drone, LEFT_EXHAUST)"));
		assertTrue(source.contains("state.heading, state.flightPitch, state.flightRoll"));
		assertTrue(source.contains("newest.at(left, right)"));
		assertTrue(source.contains("client.isPaused()) return"));
		assertFalse(source.contains("if (!active) state.points.clear()"));
	}

	@Test
	void scoutUsesCurrentOriginEveryFrameWhileOnlyEndpointsAreCached() throws IOException {
		String source = clientSource("ScoutScanRenderer");
		assertTrue(source.contains("draw(pose, out, frame, origin, camera, alpha)"));
		assertTrue(source.contains("quad(pose, out, origin, origin, ray.end, previous.end"));
		assertTrue(source.contains("line(pose, out, origin, ray.end, camera"));
		assertFalse(source.contains("Vec3 start, Vec3 end, double length"));
		assertTrue(source.contains("cached.attemptedAt != now"));
		assertTrue(source.contains("origin.distanceTo(cached.frame.origin)"));
		assertTrue(source.contains("BUDGET.reserve(now, rayCount)"));
	}

	@Test
	void scoutUsesDepthTestedEmissionAndTerrainFirstClippingWithoutGameplaySideEffects() throws IOException {
		String source = clientSource("ScoutScanRenderer");
		assertTrue(source.contains("MorrowgearRenderTypes.energyBeam()"));
		assertFalse(source.contains("RenderTypes.entityTranslucent("));
		assertTrue(source.contains(".setLight(light)"));
		assertTrue(source.contains("ClipContext.Block.COLLIDER"));
		assertTrue(source.contains("bound.clip(origin, terrainEnd)"));
		assertTrue(source.contains("ScoutScanVisualPolicy.MAX_MOB_BOUNDS"));
		assertTrue(source.contains("SCANS.size() >= ScoutScanVisualPolicy.MAX_CACHED_SCOUTS"));
		assertFalse(source.contains("FULL_BRIGHT"));
		assertFalse(source.contains("0xF000F0"));
		assertFalse(source.contains("entityTranslucentEmissive"));
		assertFalse(source.contains("visibleEnergyCore"));
		assertFalse(source.contains("ThreatNetwork"));
		assertFalse(source.contains("MissionDataLink"));
		assertFalse(source.contains("sendPacket"));
	}

	@Test
	void scoutBeamPassKeepsSoftAlphaAtNightWithoutBypassingOcclusionOrFog() throws IOException {
		String renderTypes = Files.readString(Path.of(
			"src/client/java/net/minecraft/client/renderer/rendertype/MorrowgearRenderTypes.java"));
		String pipeline = renderTypes.substring(renderTypes.indexOf("private static final RenderPipeline ENERGY_BEAM_PIPELINE"),
			renderTypes.indexOf("private static final RenderPipeline VISIBLE_ENERGY_CORE_PIPELINE"));
		assertTrue(pipeline.contains("new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)"));
		assertTrue(pipeline.contains("BlendFunction.LIGHTNING"));
		assertTrue(pipeline.contains(".withVertexShader(BEAM_SHADER)"));
		assertTrue(pipeline.contains(".withFragmentShader(BEAM_SHADER)"));
		assertFalse(pipeline.contains("CompareOp.ALWAYS_PASS"));
		Path shaders = Path.of("src/main/resources/assets/morrowgear_drone/shaders/core");
		String vertex = Files.readString(shaders.resolve("morrowgear_energy_beam.vsh"));
		String fragment = Files.readString(shaders.resolve("morrowgear_energy_beam.fsh"));
		assertTrue(vertex.contains("vertexColor = Color * ColorModulator"));
		assertFalse(vertex.contains("Sampler2"));
		assertFalse(vertex.contains("UV2"));
		assertFalse(vertex.contains("minecraft_mix_light"));
		assertTrue(fragment.contains("vertexColor.a * radial * endpointFade"));
		assertTrue(fragment.contains("apply_fog"));
		assertFalse(fragment.contains("discard"), "the 11/255 curtain must not be alpha-cut away");
		assertFalse(fragment.contains("gl_FragDepth"));
	}
}
