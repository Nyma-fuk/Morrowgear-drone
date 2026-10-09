package jp.morrowgear.drone.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TacticalScreen extends Screen {
	public record AutomationResult(boolean passed, String detail) {}
	private static TacticalDashboard lastDashboard;
	private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
	private TacticalDashboard dashboard;

	public TacticalScreen() {
		super(Component.literal("Morrowgear 作戦コントローラー"));
	}

	@Override
	protected void init() {
		if (dashboard == null) {
			if (lastLevel != minecraft.level) lastDashboard = null;
			dashboard = lastDashboard == null ? new TacticalDashboard(0, 0, width, height) : lastDashboard;
			lastDashboard = dashboard;
			lastLevel = minecraft.level;
		}
		dashboard.setWidth(width);
		dashboard.setHeight(height);
		addRenderableWidget(dashboard);
	}

	@Override
	public void removed() {
		if (dashboard != null) dashboard.close();
		super.removed();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xFF121514);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
		if (event.button() != 0 && dashboard != null && dashboard.isPanning())
			return dashboard.mouseDragged(event, dx, dy);
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (event.button() != 0 && dashboard != null && dashboard.isPanning())
			return dashboard.mouseReleased(event);
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.isEscape() && dashboard != null && dashboard.keyPressed(event)) return true;
		return super.keyPressed(event);
	}

	boolean focusReport(java.util.UUID identity, String dimension) {
		return dashboard != null && dashboard.focusReport(identity, dimension);
	}

	public AutomationResult runAutomationSmoke() {
		if (dashboard == null) return new AutomationResult(false, "dashboard was not initialized");
		String failure = dashboard.runAutomationSmoke();
		return failure.isEmpty()
			? new AutomationResult(true, "11 controller navigation actions completed without OS mouse input")
			: new AutomationResult(false, failure);
	}

	public AutomationResult selectAutomationPage(int page) {
		if (dashboard == null) return new AutomationResult(false, "dashboard was not initialized");
		String failure = dashboard.selectAutomationPage(page);
		return new AutomationResult(failure.isEmpty(), failure);
	}
}
