package jp.morrowgear.drone.client;

import java.util.Comparator;
import java.util.List;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload.LogisticsNode;
import jp.morrowgear.drone.network.SupplyNodeConfigPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Explicit owner-facing node registry. Nearby containers are candidates, never implicit warehouses. */
final class SupplyNodeMapScreen extends Screen {
	private final Screen parent;
	private List<LogisticsNode> nodes = List.of();
	private Long selected;
	private int page;
	private int refreshTicks;
	private String notice = "";
	private Button action, previous, next;

	SupplyNodeMapScreen(Screen parent) {
		super(Component.literal("物流ノード管理"));
		this.parent = parent;
	}

	@Override protected void init() {
		SupplyNetworkClient.requestNow();
		refresh();
		addRenderableWidget(ControllerButton.hmi(Component.literal("再受信"), b -> {
			SupplyNetworkClient.requestNow(); notice = "物流ノードを再受信しています";
		}).bounds(12, 32, 72, 20).build());
		action = addRenderableWidget(ControllerButton.hmi(Component.literal("登録"), b -> toggle())
			.bounds(width - 216, height - 28, 88, 20).build());
		previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> page--)
			.bounds(listX(), height - 28, 54, 20).build());
		next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> page++)
			.bounds(listX() + 60, height - 28, 54, 20).build());
		addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
			.bounds(width - 66, height - 28, 54, 20).build());
	}

	@Override public void tick() {
		if (++refreshTicks >= 20) { refreshTicks = 0; SupplyNetworkClient.requestNow(); }
		refresh();
	}

	private void refresh() {
		nodes = SupplyNetworkClient.nodes().stream().sorted(Comparator
			.comparing(LogisticsNode::registered).reversed()
			.thenComparingInt(LogisticsNode::distance).thenComparingLong(LogisticsNode::position)).toList();
		if (selected != null && nodes.stream().noneMatch(node -> node.position() == selected)) selected = null;
	}

	private void toggle() {
		LogisticsNode node = selectedNode();
		if (node == null || !ClientPlayNetworking.canSend(SupplyNodeConfigPayload.TYPE)) return;
		boolean register = !node.registered();
		ClientPlayNetworking.send(new SupplyNodeConfigPayload(node.position(), register));
		notice = register ? "登録要求を送信しました" : "解除要求を送信しました / 輸送中の荷物は維持されます";
		SupplyNetworkClient.requestNow();
	}

	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, HmiArt.BACKGROUND);
		g.text(font, title, 12, 12, HmiArt.TEXT, true);
		long registered = nodes.stream().filter(LogisticsNode::registered).count();
		g.text(font, "登録 " + registered + " / 候補 " + Math.max(0, nodes.size() - registered)
			+ " / 新規候補は水平32m・上下8m", 94, 38, HmiArt.MUTED);
		drawMap(g);
		drawList(g);
		LogisticsNode node = selectedNode();
		action.active = node != null && ClientPlayNetworking.canSend(SupplyNodeConfigPayload.TYPE);
		action.setMessage(Component.literal(node != null && node.registered() ? "登録解除" : "補給元へ登録"));
		int pages = Math.max(1, (nodes.size() + capacity() - 1) / capacity());
		page = Math.clamp(page, 0, pages - 1);
		previous.active = page > 0;
		next.active = page + 1 < pages;
		if (!notice.isBlank()) g.text(font, HmiArt.fitText(notice, mapWidth() - 10, font::width), 12, height - 24, HmiArt.AMBER);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private void drawMap(GuiGraphicsExtractor g) {
		int x = 12, y = 62, w = mapWidth(), h = Math.max(100, height - 102);
		g.fill(x, y, x + w, y + h, HmiArt.PANEL);
		g.outline(x, y, w, h, HmiArt.LINE);
		for (int i = 1; i < 4; i++) {
			g.verticalLine(x + w * i / 4, y, y + h, HmiArt.LINE);
			g.horizontalLine(x, x + w, y + h * i / 4, HmiArt.LINE);
		}
		int cx = x + w / 2, cy = y + h / 2;
		g.horizontalLine(cx - 5, cx + 5, cy, HmiArt.CYAN);
		g.verticalLine(cx, cy - 5, cy + 5, HmiArt.CYAN);
		if (minecraft.player == null) return;
		BlockPos origin = minecraft.player.blockPosition();
		int range = mapRange(origin);
		for (LogisticsNode node : nodes) {
			BlockPos pos = BlockPos.of(node.position());
			double dx = Math.clamp((pos.getX() - origin.getX()) / (double)range, -1.0, 1.0);
			double dz = Math.clamp((pos.getZ() - origin.getZ()) / (double)range, -1.0, 1.0);
			int px = cx + (int)Math.round(dx * (w / 2 - 9));
			int py = cy + (int)Math.round(dz * (h / 2 - 9));
			int color = node.registered() ? HmiArt.GREEN : HmiArt.AMBER;
			g.fill(px - 3, py - 3, px + 4, py + 4, color);
			if (selected != null && selected == node.position()) g.outline(px - 6, py - 6, 13, 13, HmiArt.CYAN);
		}
		g.text(font, "表示半径 " + range + "m / 中央=現在地", x + 8, y + 7, HmiArt.MUTED);
	}

	private void drawList(GuiGraphicsExtractor g) {
		int x = listX(), y = 62, w = width - x - 12;
		int first = page * capacity();
		for (int row = 0; row < capacity() && first + row < nodes.size(); row++) {
			LogisticsNode node = nodes.get(first + row);
			int ry = y + row * 48;
			g.fill(x, ry, x + w, ry + 42, selected != null && selected == node.position() ? HmiArt.RAISED : HmiArt.PANEL);
			if (selected != null && selected == node.position()) g.fill(x, ry, x + 3, ry + 42, HmiArt.CYAN);
			BlockPos pos = BlockPos.of(node.position());
			g.text(font, HmiArt.fitText((node.registered() ? "登録済み / " : "候補 / ") + node.name(), w - 12, font::width), x + 7, ry + 5,
				node.registered() ? HmiArt.GREEN : HmiArt.TEXT);
			g.text(font, HmiArt.fitText(pos.toShortString() + " / " + node.distance() + "m / " + node.contents(), w - 12, font::width),
				x + 7, ry + 21, HmiArt.MUTED);
		}
		if (nodes.isEmpty()) g.text(font, "候補なし / 登録する容器の近くで再受信してください", x + 6, y + 8, HmiArt.MUTED);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		LogisticsNode map = mapNodeAt(event.x(), event.y());
		if (map != null) { selected = map.position(); return true; }
		if (event.x() >= listX() && event.x() < width - 12 && event.y() >= 62) {
			int row = (int)((event.y() - 62) / 48);
			int index = page * capacity() + row;
			if (row >= 0 && row < capacity() && index < nodes.size()) { selected = nodes.get(index).position(); return true; }
		}
		return super.mouseClicked(event, doubled);
	}

	private LogisticsNode mapNodeAt(double mouseX, double mouseY) {
		if (minecraft.player == null || mouseX < 12 || mouseX >= 12 + mapWidth() || mouseY < 62 || mouseY >= height - 40) return null;
		BlockPos origin = minecraft.player.blockPosition();
		int w = mapWidth(), h = Math.max(100, height - 102), cx = 12 + w / 2, cy = 62 + h / 2, range = mapRange(origin);
		return nodes.stream().min(Comparator.comparingDouble(node -> {
			BlockPos pos = BlockPos.of(node.position());
			int px = cx + (int)Math.round(Math.clamp((pos.getX() - origin.getX()) / (double)range, -1.0, 1.0) * (w / 2 - 9));
			int py = cy + (int)Math.round(Math.clamp((pos.getZ() - origin.getZ()) / (double)range, -1.0, 1.0) * (h / 2 - 9));
			return (px - mouseX) * (px - mouseX) + (py - mouseY) * (py - mouseY);
		})).filter(node -> {
			BlockPos pos = BlockPos.of(node.position());
			int px = cx + (int)Math.round(Math.clamp((pos.getX() - origin.getX()) / (double)range, -1.0, 1.0) * (w / 2 - 9));
			int py = cy + (int)Math.round(Math.clamp((pos.getZ() - origin.getZ()) / (double)range, -1.0, 1.0) * (h / 2 - 9));
			return Math.abs(px - mouseX) <= 8 && Math.abs(py - mouseY) <= 8;
		}).orElse(null);
	}

	private int mapRange(BlockPos origin) {
		int farthest = nodes.stream().map(node -> BlockPos.of(node.position())).mapToInt(pos -> Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getZ() - origin.getZ())))
			.filter(distance -> distance <= 256).max().orElse(32);
		return Math.clamp(((Math.max(32, farthest) + 15) / 16) * 16, 32, 256);
	}
	private LogisticsNode selectedNode() { return selected == null ? null : nodes.stream().filter(node -> node.position() == selected).findFirst().orElse(null); }
	private int mapWidth() { return Math.max(180, Math.min(width - 210, width * 3 / 5)); }
	private int listX() { return 24 + mapWidth(); }
	private int capacity() { return Math.max(1, (height - 112) / 48); }
	@Override public void onClose() { minecraft.setScreenAndShow(parent); }
	@Override public boolean isPauseScreen() { return false; }
}
