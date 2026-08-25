package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class MorrowgearRenderTypes {
	private static final Identifier BEAM_SHADER = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "core/morrowgear_energy_beam");
	private static final RenderPipeline ENERGY_BEAM_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID,
				"pipeline/energy_beam"))
			.withVertexShader(BEAM_SHADER)
			.withFragmentShader(BEAM_SHADER)
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.withVertexBinding(0, DefaultVertexFormat.ENTITY)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false)
			.build());
	private static final RenderPipeline VISIBLE_ENERGY_CORE_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID,
				"pipeline/visible_energy_core"))
			.withVertexShader(BEAM_SHADER)
			.withFragmentShader(BEAM_SHADER)
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.withVertexBinding(0, DefaultVertexFormat.ENTITY)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false)
			.build());
	private static final RenderType ENERGY_BEAM = RenderType.create("morrowgear_energy_beam",
		RenderSetup.builder(ENERGY_BEAM_PIPELINE).sortOnUpload().createRenderSetup());
	private static final RenderType VISIBLE_ENERGY_CORE = RenderType.create("morrowgear_visible_energy_core",
		RenderSetup.builder(VISIBLE_ENERGY_CORE_PIPELINE).sortOnUpload().createRenderSetup());

	private MorrowgearRenderTypes() {}

	public static RenderType energyBeam() {
		return ENERGY_BEAM;
	}

	public static RenderType visibleEnergyCore() {
		return VISIBLE_ENERGY_CORE;
	}
}
