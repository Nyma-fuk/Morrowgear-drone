package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.RuntimeMeshPassIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Immutable geometry; pose weights belong to a render submission, never to the shared mesh. */
final class RuntimeMesh {
	private static final Identifier WHITE = id("textures/entity/emissive_white.png");
	private final float[] data;
	private final int[] colors;
	private final byte[] groups;
	private final byte[] flags;
	private final float[] centers;
	private final Identifier texture;
	private final int[] signalTriangles, texturedTriangles;

	private RuntimeMesh(float[] data, int[] colors, byte[] groups, byte[] flags, float[] centers, Identifier texture) {
		this.data = data; this.colors = colors; this.groups = groups; this.flags = flags;
		this.centers = centers; this.texture = texture;
		signalTriangles = RuntimeMeshPassIndex.triangles(flags, 1);
		texturedTriangles = RuntimeMeshPassIndex.triangles(flags, 2);
	}

	static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, path); }

	static RuntimeMesh load(String name) {
		return load(name, name);
	}

	static RuntimeMesh load(String meshName, String textureName) {
		try {
			return read(Minecraft.getInstance().getResourceManager().getResourceOrThrow(
				id("models/runtime/" + meshName + ".mgm")).open(), id("textures/runtime/" + textureName + ".png"));
		} catch (IOException error) { throw new IllegalStateException("Cannot load runtime master " + meshName, error); }
	}

	static RuntimeMesh read(InputStream stream, Identifier texture) throws IOException {
		try (var in = new DataInputStream(new BufferedInputStream(stream))) {
			if (in.readInt() != 0x4D474D34) throw new IOException("Expected MGM4");
			int count = in.readInt(), rotors = in.readInt();
			if (count <= 0 || count > 400000 || count % 3 != 0 || rotors < 2 || rotors > 8)
				throw new IOException("Invalid runtime mesh bounds");
			float[] centers = new float[rotors * 3];
			for (int i = 0; i < centers.length; i++) centers[i] = finite(in.readFloat());
			float[] data = new float[count * 20];
			int[] colors = new int[count];
			byte[] groups = new byte[count], flags = new byte[count];
			for (int i = 0; i < count; i++) {
				for (int j = 0; j < 20; j++) data[i * 20 + j] = finite(in.readFloat());
				colors[i] = in.readInt(); groups[i] = in.readByte(); flags[i] = in.readByte();
				if (groups[i] < 0 || groups[i] > rotors || flags[i] < 0 || flags[i] > 2) throw new IOException("Invalid surface index");
			}
			if (in.read() != -1) throw new IOException("Trailing mesh data");
			return new RuntimeMesh(data, colors, groups, flags, centers, texture);
		}
	}

	private static float finite(float value) throws IOException {
		if (!Float.isFinite(value)) throw new IOException("Non-finite mesh value");
		return value;
	}

	void extents(Consumer<Vector3fc> output) {
		for (int i = 0; i < colors.length; i++) output.accept(new Vector3f(data[i * 20], data[i * 20 + 1], data[i * 20 + 2]));
	}

	void submit(PoseStack stack, SubmitNodeCollector collector, int light, float rotor,
		float landed, float deployed, boolean powered, boolean combat) {
		submit(stack, collector, light, rotor, landed, deployed, powered, combat, true);
	}

	void submit(PoseStack stack, SubmitNodeCollector collector, int light, float rotor,
		float landed, float deployed, boolean powered, boolean combat, boolean halo) {
		submit(stack, collector, light, rotor, landed, deployed, powered, combat, halo, true);
	}

	void submit(PoseStack stack, SubmitNodeCollector collector, int light, float rotor,
		float landed, float deployed, boolean powered, boolean combat, boolean halo, boolean texturedGlow) {
		float gear = Math.clamp(landed, 0, 1), action = Math.clamp(deployed, 0, 1) * (1 - gear);
		collector.submitCustomGeometry(stack, RenderTypes.entityCutout(texture),
			(pose, out) -> render(pose, out, light, rotor, gear, action, 0, combat));
		if (powered && signalTriangles.length > 0) collector.submitCustomGeometry(stack, RenderTypes.entityTranslucentEmissive(WHITE),
			(pose, out) -> render(pose, out, 0xF000F0, rotor, gear, action, 1, combat));
		if (powered && halo && signalTriangles.length > 0) collector.submitCustomGeometry(stack, RenderTypes.entityTranslucentEmissive(WHITE),
			(pose, out) -> render(pose, out, 0xF000F0, rotor, gear, action, 3, combat));
		if (powered && texturedGlow && texturedTriangles.length > 0) collector.submitCustomGeometry(stack, RenderTypes.entityTranslucentEmissive(texture),
			(pose, out) -> render(pose, out, 0xF000F0, rotor, gear, action, 2, false));
	}

	private void render(PoseStack.Pose pose, VertexConsumer out, int light, float rotor,
		float landed, float action, int pass, boolean combat) {
		float c = (float)Math.cos(rotor), s = (float)Math.sin(rotor);
		// PoseStack's convenience vertex methods allocate two temporary vectors per vertex.
		// These meshes contain tens of thousands of triangles, so reuse two vectors per pass.
		Vector3f transformedPosition = new Vector3f(), transformedNormal = new Vector3f();
		int[] selected = pass == 0 ? null : pass == 2 ? texturedTriangles : signalTriangles;
		int count = selected == null ? colors.length / 3 : selected.length;
		int fixedPose = RuntimeMeshPassIndex.fixedPose(landed, action);
		for (int face = 0; face < count; face++) {
			int triangle = selected == null ? face * 3 : selected[face];
			for (int corner = 0; corner < 4; corner++) {
				int index = triangle + Math.min(corner, 2), offset = index * 20;
				int source = offset + Math.max(0, fixedPose);
				float x = fixedPose >= 0 ? data[source] : value(offset, landed, action);
				float y = fixedPose >= 0 ? data[source + 1] : value(offset + 1, landed, action);
				float z = fixedPose >= 0 ? data[source + 2] : value(offset + 2, landed, action);
				float nx = fixedPose >= 0 ? data[source + 3] : value(offset + 3, landed, action);
				float ny = fixedPose >= 0 ? data[source + 4] : value(offset + 4, landed, action);
				float nz = fixedPose >= 0 ? data[source + 5] : value(offset + 5, landed, action);
				int group = groups[index];
				if (group != 0) {
					int center = (group - 1) * 3;
					float sin = group % 2 == 0 ? -s : s;
					float lx = x - centers[center], lz = z - centers[center + 2];
					x = centers[center] + lx * c + lz * sin;
					z = centers[center + 2] - lx * sin + lz * c;
					float old = nx; nx = nx * c + nz * sin; nz = -old * sin + nz * c;
				}
				boolean line = pass == 1 || pass == 3;
				if (line) {
					float offsetNormal = pass == 3 ? .018f : .003f;
					x += nx * offsetNormal; y += ny * offsetNormal; z += nz * offsetNormal;
				}
				int color = line ? combat ? 0xFFFF4824 : 0xFF55F4FF : 0xFFFFFFFF;
				if (pass == 3) color = (color & 0xFFFFFF) | 0x58000000;
				pose.pose().transformPosition(x, y, z, transformedPosition);
				pose.transformNormal(nx, ny, nz, transformedNormal);
				out.addVertex(transformedPosition.x, transformedPosition.y, transformedPosition.z).setColor(color)
					.setUv(line ? .5f : data[offset + 18], line ? .5f : data[offset + 19])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
					.setNormal(transformedNormal.x, transformedNormal.y, transformedNormal.z);
			}
		}
	}

	private float value(int offset, float landed, float action) {
		float base = data[offset];
		return base + (data[offset + 6] - base) * landed + (data[offset + 12] - base) * action;
	}
}
