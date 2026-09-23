package jp.morrowgear.drone.client;

import com.mojang.blaze3d.platform.InputConstants;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.network.PowerLostBeaconPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

public final class MorrowgearDroneClient implements ClientModInitializer {
	private static final VisorHudOverlay VISOR_HUD = new VisorHudOverlay();
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "command")
	);
	private static final KeyMapping OPEN_COMMAND = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.morrowgear_drone.open_command",
		InputConstants.Type.KEYSYM,
		InputConstants.KEY_K,
		CATEGORY
	));
	private static final KeyMapping VISOR_TOGGLE = key("key.morrowgear_drone.visor_toggle", InputConstants.KEY_V);
	private static final KeyMapping VISOR_PIN = key("key.morrowgear_drone.visor_pin", InputConstants.KEY_G);
	private static final KeyMapping VISOR_CONTEXT = key("key.morrowgear_drone.visor_context", InputConstants.KEY_H);
	private static final KeyMapping VISOR_RANGE = key("key.morrowgear_drone.visor_range", InputConstants.KEY_J);

	@Override
	public void onInitializeClient() {
		OperationCommunicationsClient.register();
		SupplyNetworkClient.register();
		jp.morrowgear.drone.carrier.client.CarrierClientApi.register();
		net.minecraft.client.gui.screens.MenuScreens.register(
			jp.morrowgear.drone.carrier.CarrierModule.MENU, CarrierScreen::new);
		net.minecraft.client.gui.screens.MenuScreens.register(jp.morrowgear.drone.DockMenu.TYPE, DockScreen::new);
		DroneAudioController.register();
		CarrierAudioController.register();
		net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry.register(
			MorrowgearDrone.DOCK_BLOCK_ENTITY, context -> new DockRenderer());
		net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin.register(plugin ->
			plugin.modifyItemModelAfterBake().register((model, context) -> {
				if (!context.itemId().getNamespace().equals(MorrowgearDrone.MOD_ID)) return model;
				String name = context.itemId().getPath();
				if (name.equals("carrier_console")) return new EquipmentItemModel(model, "controller");
				return java.util.Set.of("controller", "tactical_visor", "recovery_tool").contains(name)
					? new EquipmentItemModel(model, name) : model;
			}));
		EntityRenderers.register(MorrowgearDrone.DRONE, DroneRenderer::new);
		EntityRenderers.register(jp.morrowgear.drone.carrier.CarrierModule.ENTITY, CarrierRenderer::new);
		EntityRenderers.register(MorrowgearDrone.MISSILE, MissileRenderer::new);
		EntityRenderers.register(MorrowgearDrone.SOLAR_SERVICE_STATION, SolarServiceStationRenderer::new);
		EntityRenderers.register(MorrowgearDrone.CHARGING_RELAY, ChargingRelayRenderer::new);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
			Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "tactical_visor"), VISOR_HUD);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
			Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "operation_subtitle"), new OperationSubtitleOverlay());
		ClientPlayNetworking.registerGlobalReceiver(PowerLostBeaconPayload.TYPE, (payload, context) ->
			context.client().execute(() -> PowerLostBeaconStore.accept(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PowerLostBeaconStore.clear());

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide() || !player.getItemInHand(hand).is(MorrowgearDrone.CONTROLLER)) return InteractionResult.PASS;
			Minecraft.getInstance().setScreenAndShow(new TacticalScreen());
			return InteractionResult.SUCCESS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			FormationTrailController.tick(client);
			CarrierTrailController.tick(client);
			ScoutScanRenderer.tick(client);
			VISOR_HUD.tick(client);
			while (OPEN_COMMAND.consumeClick()) {
				if (client.player != null) client.setScreenAndShow(new TacticalScreen());
			}
			while (VISOR_TOGGLE.consumeClick()) VISOR_HUD.toggleEnabled(client);
			while (VISOR_PIN.consumeClick()) VISOR_HUD.togglePin(client);
			while (VISOR_CONTEXT.consumeClick()) VISOR_HUD.cycleContext(client);
			while (VISOR_RANGE.consumeClick()) VISOR_HUD.cycleRange(client);
		});
	}

	private static KeyMapping key(String translationKey, int keyCode) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey,
			InputConstants.Type.KEYSYM, keyCode, CATEGORY));
	}
}
