package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

final class EquipmentItemModel implements ItemModel, SpecialModelRenderer<ItemDisplayContext> {
	private final ItemModel inventoryModel;
	private final RuntimeMesh mesh;
	private final String name;
	EquipmentItemModel(ItemModel inventoryModel, String name) {
		this.inventoryModel = inventoryModel; this.name = name; this.mesh = RuntimeMesh.load(name);
	}
	@Override public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
		ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed) {
		if (context == ItemDisplayContext.GUI) {
			inventoryModel.update(state, stack, resolver, context, level, owner, seed);
			return;
		}
		var layer = state.newLayer();
		boolean hand = context.firstPerson();
		float scale = name.equals("controller") ? hand ? .30f : .30f : hand ? .45f : .36f;
		layer.setItemTransform(new ItemTransform(new Vector3f(hand ? 40 : 10, 0, hand ? -8 : 0),
			new Vector3f(0, hand ? .08f : .12f, 0), new Vector3f(scale)));
		layer.setUsesBlockLight(true);
		layer.setExtents(() -> new Vector3fc[] {new Vector3f(-1, -.2f, -1), new Vector3f(1, .5f, 1)});
		layer.setupSpecialModel(this, context);
	}
	@Override public ItemDisplayContext extractArgument(ItemStack stack) { return ItemDisplayContext.NONE; }
	@Override public void getExtents(Consumer<Vector3fc> output) { mesh.extents(output); }
	@Override public void submit(ItemDisplayContext context, PoseStack stack, SubmitNodeCollector collector,
		int light, int overlay, boolean foil, int outline) {
		mesh.submit(stack, collector, light, 0, 0, 0, true, false);
	}
}
