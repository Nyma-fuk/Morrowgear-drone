package jp.morrowgear.drone.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TacticalScreen extends Screen {
	private TacticalDashboard dashboard;

	public TacticalScreen() {
		super(Component.literal("MORROWGEAR C2"));
	}

	@Override
	protected void init() {
		dashboard = addRenderableWidget(new TacticalDashboard(0, 0, width, height));
	}

	@Override
	public void removed() {
		if (dashboard != null) dashboard.close();
		dashboard = null;
		super.removed();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xFF081014);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
