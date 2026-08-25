package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.renderer.texture.OverlayTexture;

final class DroneMesh {
	private static final int MAGIC = 0x4D474D32;
	private static final int MAGIC_VARIABLE_ROTORS = 0x4D474D33;
	private static final int MAX_VERTEX_COUNT = 400_000;
	private static final int FLOATS_PER_VERTEX = 6;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private final float[] geometry;
	private final int[] colors;
	private final byte[] rotorGroups;
	private final float[] rotorCenters;

	private DroneMesh(float[] geometry, int[] colors, byte[] rotorGroups, float[] rotorCenters) {
		this.geometry = geometry;
		this.colors = colors;
		this.rotorGroups = rotorGroups;
		this.rotorCenters = rotorCenters;
	}

	static DroneMesh read(InputStream stream) throws IOException {
		try (DataInputStream input = new DataInputStream(new BufferedInputStream(stream))) {
			int magic = input.readInt();
			if (magic != MAGIC && magic != MAGIC_VARIABLE_ROTORS) throw new IOException("Unsupported Morrowgear mesh format");
			int vertexCount = input.readInt();
			if (vertexCount <= 0 || vertexCount % 3 != 0 || vertexCount > MAX_VERTEX_COUNT) {
				throw new IOException("Invalid Morrowgear vertex count: " + vertexCount);
			}
			int rotorCount = magic == MAGIC_VARIABLE_ROTORS ? input.readInt() : 2;
			if (rotorCount < 2 || rotorCount > 8) throw new IOException("Invalid Morrowgear rotor count: " + rotorCount);
			float[] rotorCenters = new float[rotorCount * 3];
			for (int component = 0; component < rotorCenters.length; component++) rotorCenters[component] = input.readFloat();
			float[] geometry = new float[vertexCount * FLOATS_PER_VERTEX];
			int[] colors = new int[vertexCount];
			byte[] rotorGroups = new byte[vertexCount];
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				int offset = vertex * FLOATS_PER_VERTEX;
				for (int component = 0; component < FLOATS_PER_VERTEX; component++) geometry[offset + component] = input.readFloat();
				int alpha = input.readUnsignedByte();
				int red = input.readUnsignedByte();
				int green = input.readUnsignedByte();
				int blue = input.readUnsignedByte();
				colors[vertex] = alpha << 24 | red << 16 | green << 8 | blue;
				rotorGroups[vertex] = input.readByte();
			}
			return new DroneMesh(geometry, colors, rotorGroups, rotorCenters);
		}
	}

	void render(PoseStack.Pose pose, VertexConsumer consumer, int light, float rotorAngle) {
		for (int triangle = 0; triangle < colors.length; triangle += 3) {
			emitTriangleAsQuad(pose, consumer, light, triangle, rotorAngle, 1.0f, false, 255);
		}
	}

	void renderEmissive(PoseStack.Pose pose, VertexConsumer consumer, float rotorAngle, float glowScale, int alpha) {
		for (int triangle = 0; triangle < colors.length; triangle += 3) {
			if (isCyan(colors[triangle]) || rotorGroups[triangle] != 0) {
				emitTriangleAsQuad(pose, consumer, FULL_BRIGHT, triangle, rotorAngle, glowScale, true, alpha);
			}
		}
	}

	void renderCombatEmissive(PoseStack.Pose pose, VertexConsumer consumer,
		float rotorAngle, float glowScale, int alpha) {
		int color = alpha << 24 | 255 << 16 | 54 << 8 | 8;
		for (int triangle = 0; triangle < colors.length; triangle += 3) {
			if (isCyan(colors[triangle]) || rotorGroups[triangle] != 0) {
				emitTriangleTinted(pose, consumer, triangle, rotorAngle, glowScale, color);
			}
		}
	}

	private void emitTriangleTinted(PoseStack.Pose pose, VertexConsumer consumer, int triangle,
		float rotorAngle, float glowScale, int color) {
		for (int corner : new int[] {triangle, triangle + 1, triangle + 2, triangle + 2}) {
			int offset = corner * FLOATS_PER_VERTEX;
			float x = geometry[offset];
			float y = geometry[offset + 1];
			float z = geometry[offset + 2];
			float normalX = geometry[offset + 3];
			float normalY = geometry[offset + 4];
			float normalZ = geometry[offset + 5];
			int rotorGroup = rotorGroups[corner];
			if (rotorGroup != 0) {
				int centerOffset = (rotorGroup - 1) * 3;
				float angle = rotorGroup == 1 ? rotorAngle : -rotorAngle;
				float cosine = (float)Math.cos(angle);
				float sine = (float)Math.sin(angle);
				float localX = x - rotorCenters[centerOffset];
				float localZ = z - rotorCenters[centerOffset + 2];
				x = rotorCenters[centerOffset] + localX * cosine + localZ * sine;
				z = rotorCenters[centerOffset + 2] - localX * sine + localZ * cosine;
			}
			if (glowScale != 1.0f) {
				x *= glowScale;
				y = 0.3f + (y - 0.3f) * glowScale;
				z *= glowScale;
			}
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(0.5f, 0.5f)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT)
				.setNormal(pose, normalX, normalY, normalZ);
		}
	}

	void renderTurbines(PoseStack.Pose pose, VertexConsumer consumer, float rotorAngle, float glowScale, int alpha) {
		renderTurbinesColor(pose, consumer, rotorAngle, glowScale, emissiveColor(alpha));
	}

	void renderCombatTurbines(PoseStack.Pose pose, VertexConsumer consumer,
		float rotorAngle, float glowScale, int alpha) {
		renderTurbinesColor(pose, consumer, rotorAngle, glowScale,
			alpha << 24 | 255 << 16 | 54 << 8 | 8);
	}

	private void renderTurbinesColor(PoseStack.Pose pose, VertexConsumer consumer,
		float rotorAngle, float glowScale, int color) {
		for (int rotorGroup = 1; rotorGroup <= rotorCenters.length / 3; rotorGroup++) {
			int centerOffset = (rotorGroup - 1) * 3;
			float centerX = rotorCenters[centerOffset];
			float centerY = rotorCenters[centerOffset + 1];
			float centerZ = rotorCenters[centerOffset + 2];
			float direction = rotorGroup % 2 == 1 ? rotorAngle : -rotorAngle;
			emitTurbineRing(pose, consumer, centerX, centerY, centerZ, glowScale, color);
			for (int blade = 0; blade < 6; blade++) {
				float angle = direction + blade * ((float) Math.PI * 2.0f / 6.0f);
				emitTurbineBlade(pose, consumer, centerX, centerY, centerZ, angle, glowScale, color);
			}
		}
	}

	private void emitTurbineBlade(PoseStack.Pose pose, VertexConsumer consumer, float centerX, float centerY,
		float centerZ, float angle, float glowScale, int color) {
		float inner = 0.045f;
		float outer = 0.205f * glowScale;
		float[] radii = {inner, outer, outer, inner};
		float[] offsets = {-0.11f, 0.06f, 0.34f, 0.15f};
		float[] xs = new float[4];
		float[] zs = new float[4];
		for (int corner = 0; corner < 4; corner++) {
			xs[corner] = centerX + (float) Math.cos(angle + offsets[corner]) * radii[corner];
			zs[corner] = centerZ + (float) Math.sin(angle + offsets[corner]) * radii[corner];
		}
		float top = centerY + 0.012f;
		for (int corner = 0; corner < 4; corner++) {
			emitRawVertex(pose, consumer, xs[corner], top, zs[corner], 0, 1, 0, color);
		}
		float bottom = centerY - 0.085f;
		for (int corner = 3; corner >= 0; corner--) {
			emitRawVertex(pose, consumer, xs[corner], bottom, zs[corner], 0, -1, 0, color);
		}
	}

	private void emitTurbineRing(PoseStack.Pose pose, VertexConsumer consumer, float centerX, float centerY,
		float centerZ, float glowScale, int color) {
		float inner = 0.213f * glowScale;
		float outer = 0.226f * glowScale;
		float top = centerY + 0.013f;
		float bottom = centerY - 0.086f;
		for (int segment = 0; segment < 24; segment++) {
			float angle0 = segment * ((float) Math.PI * 2.0f / 24.0f);
			float angle1 = (segment + 1) * ((float) Math.PI * 2.0f / 24.0f);
			float innerX0 = centerX + (float) Math.cos(angle0) * inner;
			float innerZ0 = centerZ + (float) Math.sin(angle0) * inner;
			float outerX0 = centerX + (float) Math.cos(angle0) * outer;
			float outerZ0 = centerZ + (float) Math.sin(angle0) * outer;
			float innerX1 = centerX + (float) Math.cos(angle1) * inner;
			float innerZ1 = centerZ + (float) Math.sin(angle1) * inner;
			float outerX1 = centerX + (float) Math.cos(angle1) * outer;
			float outerZ1 = centerZ + (float) Math.sin(angle1) * outer;
			emitRawVertex(pose, consumer, innerX0, top, innerZ0, 0, 1, 0, color);
			emitRawVertex(pose, consumer, outerX0, top, outerZ0, 0, 1, 0, color);
			emitRawVertex(pose, consumer, outerX1, top, outerZ1, 0, 1, 0, color);
			emitRawVertex(pose, consumer, innerX1, top, innerZ1, 0, 1, 0, color);
			emitRawVertex(pose, consumer, innerX1, bottom, innerZ1, 0, -1, 0, color);
			emitRawVertex(pose, consumer, outerX1, bottom, outerZ1, 0, -1, 0, color);
			emitRawVertex(pose, consumer, outerX0, bottom, outerZ0, 0, -1, 0, color);
			emitRawVertex(pose, consumer, innerX0, bottom, innerZ0, 0, -1, 0, color);
		}
	}

	private static void emitRawVertex(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float z,
		float normalX, float normalY, float normalZ, int color) {
		consumer.addVertex(pose, x, y, z).setColor(color).setUv(0.5f, 0.5f)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, normalX, normalY, normalZ);
	}

	private void emitTriangleAsQuad(PoseStack.Pose pose, VertexConsumer consumer, int light, int triangle,
		float rotorAngle, float glowScale, boolean emissive, int alpha) {
		emitVertex(pose, consumer, light, triangle, rotorAngle, glowScale, emissive, alpha);
		emitVertex(pose, consumer, light, triangle + 1, rotorAngle, glowScale, emissive, alpha);
		emitVertex(pose, consumer, light, triangle + 2, rotorAngle, glowScale, emissive, alpha);
		// Entity render types consume quads. Repeating the last corner preserves the source triangle.
		emitVertex(pose, consumer, light, triangle + 2, rotorAngle, glowScale, emissive, alpha);
	}

	private void emitVertex(PoseStack.Pose pose, VertexConsumer consumer, int light, int vertex,
		float rotorAngle, float glowScale, boolean emissive, int alpha) {
		int offset = vertex * FLOATS_PER_VERTEX;
		float x = geometry[offset];
		float y = geometry[offset + 1];
		float z = geometry[offset + 2];
		float normalX = geometry[offset + 3];
		float normalY = geometry[offset + 4];
		float normalZ = geometry[offset + 5];
		int rotorGroup = rotorGroups[vertex];
		if (rotorGroup != 0) {
			int centerOffset = (rotorGroup - 1) * 3;
			float angle = rotorGroup == 1 ? rotorAngle : -rotorAngle;
			float cosine = (float) Math.cos(angle);
			float sine = (float) Math.sin(angle);
			float localX = x - rotorCenters[centerOffset];
			float localZ = z - rotorCenters[centerOffset + 2];
			x = rotorCenters[centerOffset] + localX * cosine + localZ * sine;
			z = rotorCenters[centerOffset + 2] - localX * sine + localZ * cosine;
			float rotatedNormalX = normalX * cosine + normalZ * sine;
			normalZ = -normalX * sine + normalZ * cosine;
			normalX = rotatedNormalX;
		}
		if (glowScale != 1.0f) {
			x *= glowScale;
			y = 0.3f + (y - 0.3f) * glowScale;
			z *= glowScale;
		}
		int color = emissive ? emissiveColor(alpha) : colors[vertex];
		consumer.addVertex(pose, x, y, z).setColor(color).setUv(0.5f, 0.5f)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normalX, normalY, normalZ);
	}

	private static boolean isCyan(int color) {
		int red = color >> 16 & 0xFF;
		int green = color >> 8 & 0xFF;
		int blue = color & 0xFF;
		return red < 80 && green >= 80 && blue >= 110 && blue > red * 2;
	}

	private static int emissiveColor(int alpha) {
		return alpha << 24 | 18 << 16 | 224 << 8 | 255;
	}
}
