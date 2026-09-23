package jp.morrowgear.drone.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneMode;
import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.DockAllocationUiPolicy;
import jp.morrowgear.drone.DockAllocationUiPolicy.AircraftView;
import jp.morrowgear.drone.DockAllocationUiPolicy.DockState;
import jp.morrowgear.drone.DockAllocationUiPolicy.DockView;
import jp.morrowgear.drone.DockOperationalPolicy;
import jp.morrowgear.drone.FieldOperationType;
import jp.morrowgear.drone.MissionAssignmentPolicy;
import jp.morrowgear.drone.PatrolRoutePolicy;
import jp.morrowgear.drone.SecurityLoadout;
import jp.morrowgear.drone.TacticalLayoutPolicy;
import jp.morrowgear.drone.TacticalUiPolicy;
import jp.morrowgear.drone.WingMembershipPolicy;
import jp.morrowgear.drone.block.DockBlockEntity;
import jp.morrowgear.drone.network.DroneCommandPayload;
import jp.morrowgear.drone.network.FleetOperationPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TacticalDashboard extends AbstractWidget {
	private static final Identifier TERRAIN_TEXTURE_ID = Identifier.fromNamespaceAndPath("morrowgear_drone", "c2_terrain");
	private static final int BG = HmiArt.BACKGROUND;
	private static final int PANEL = HmiArt.PANEL;
	private static final int PANEL_ALT = HmiArt.RAISED;
	private static final int PANEL_RAISED = 0xFF303A3F;
	private static final int BORDER = HmiArt.LINE;
	private static final int GRID = 0xFF1D3139;
	private static final int TEXT = HmiArt.TEXT;
	private static final int MUTED = HmiArt.MUTED;
	private static final int CYAN = HmiArt.CYAN;
	private static final int BLUE = 0xFF5594FF;
	private static final int GREEN = HmiArt.GREEN;
	private static final int AMBER = HmiArt.AMBER;
	private static final int RED = HmiArt.RED;
	private static final String[] PAGES = {"作戦地図", "Wing", "任務", "Dock・補給", "回収", "バイザー", "機器", "設定"};
	private static final String[] PAGE_ICONS = {"map", "layers", "list-checks", "battery-charging", "wrench", "radar", "boxes", "sliders-horizontal"};
	private static final String[] ITEM_IDS = {"controller", "tactical_visor", "recovery_tool", "field_drone_unit", "dock_item", "solar_service_station",
		"scout_module", "cargo_module", "engineer_module", "security_module", "salvage_module", "power_cell", "standard_battery_pack",
		"reinforced_battery_pack", "high_density_battery_pack", "raw_morrow_composite", "morrow_alloy", "lightweight_frame",
		"basic_control_board", "flight_actuator", "autocannon_module", "laser_module", "missile_module"};
	private int page;
	private int pageScroll;
	private int roleFilter = -1;
	private int sortMode;
	private final VisorClientConfig visorConfig = VisorClientConfig.current();
	private static final String[] ACTIONS = { "follow", "standby", "return", "dock", "orbit", "decommission" };
	private static final DroneRole[] MODULE_ROLES = { DroneRole.FIELD, DroneRole.SCOUT, DroneRole.CARGO, DroneRole.ENGINEER, DroneRole.SECURITY, DroneRole.SALVAGE };
	private static final SecurityLoadout[] WEAPON_LOADOUTS = {
		SecurityLoadout.AUTOCANNON, SecurityLoadout.LASER, SecurityLoadout.MISSILE
	};
	private static final String[] WEAPON_LABELS = { "GUN", "LZR", "MSL" };
	private static final FieldOperationType[] WORK_TYPES = {
		FieldOperationType.ORE, FieldOperationType.EXCAVATE, FieldOperationType.FORESTRY
	};
	private static final String[] WORK_LABELS = { "ORE OPS", "EXCAVATE", "FORESTRY" };

	private final Minecraft client = Minecraft.getInstance();
	private final Set<Integer> selected = new LinkedHashSet<>();
	private int selectionAnchorId = -1;
	private SelectionMode selectionMode = SelectionMode.UNIT;
	private boolean wingMapMode;
	private int unitScroll;
	private int dockScroll;
	private int missionScroll;
	private int cargoScroll;
	private int wingBoardScroll;
	private FieldOperationType armedWork = FieldOperationType.NONE;
	private boolean autoOperations;
	private boolean armedGuard;
	private boolean armedRoute;
	private final List<BlockPos> routeDraft = new ArrayList<>();
	private int draggedEntity = -1;
	private int dragSourceX;
	private int dragSourceY;
	private double dragStartX;
	private double dragStartY;
	private double dragX;
	private double dragY;
	private double mapZoom = 5.0;
	private Object terrainCacheLevel;
	private int terrainCacheCenterX = Integer.MIN_VALUE;
	private int terrainCacheCenterZ = Integer.MIN_VALUE;
	private int terrainCacheZoom = Integer.MIN_VALUE;
	private Rect terrainCacheMap;
	private long terrainRefreshAt;
	private DynamicTexture terrainTexture;
	private int terrainTextureWidth;
	private int terrainTextureHeight;
	private Object entityCacheLevel;
	private long entityRefreshAt;
	private List<LivingEntity> cachedMapEntities = List.of();
	private Object dockCacheLevel;
	private long dockRefreshAt;
	private List<DockInfo> cachedDocks = List.of();
	private Object containerCacheLevel;
	private long containerRefreshAt;
	private List<ContainerInfo> cachedContainers = List.of();
	private String transientNotice = "";
	private long transientNoticeUntil;
	private long confirmStoreUntil;
	private Set<Integer> confirmStoreSelection = Set.of();

	public TacticalDashboard(int x, int y, int width, int height) {
		super(x, y, width, height, Component.literal("Morrowgear integrated tactical command dashboard"));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float scale = dashboardScale();
		int virtualMouseX = (int) (mouseX / scale);
		int virtualMouseY = (int) (mouseY / scale);
		g.pose().pushMatrix();
		g.pose().scale(scale, scale);
		List<DroneEntity> drones = drones();
		List<LivingEntity> mapEntities = mapEntities();
		List<DockInfo> docks = docks(drones);
		if (selectionMode == SelectionMode.ALL) {
			selected.clear();
			drones.forEach(drone -> selected.add(drone.getId()));
		} else {
			selected.removeIf(id -> drones.stream().noneMatch(drone -> drone.getId() == id));
		}
		if (!selected.equals(confirmStoreSelection)) confirmStoreUntil = 0;
		Layout l = layout();
		TacticalUiPolicy.CommandAvailability availability = availability(drones);

		g.fill(0, 0, virtualWidth(), virtualHeight(), BG);
		drawHeader(g, l, drones, mapEntities, availability);
		if (page >= 5) {
			drawUtilityPage(g, l, drones);
			drawInteractionStatus(g, l, availability);
			g.pose().popMatrix();
			return;
		}
		drawLeftPanel(g, l, drones, docks, virtualMouseX, virtualMouseY);
		if (page == 1) drawWingBoard(g, l, drones, virtualMouseX, virtualMouseY);
		else if (page >= 2) drawOperationsPage(g, l, drones, docks);
		else drawMap(g, l, drones, mapEntities, virtualMouseX, virtualMouseY);
		drawCommandPanel(g, l, drones, virtualMouseX, virtualMouseY);
		drawBottomPanel(g, l, drones);
		drawInteractionStatus(g, l, availability);

		if (draggedEntity >= 0) {
			DroneEntity drone = drones.stream().filter(value -> value.getId() == draggedEntity).findFirst().orElse(null);
			if (drone != null) {
				drawLine(g, dragSourceX, dragSourceY, (int) dragX, (int) dragY, CYAN);
				g.fill((int) dragX - 38, (int) dragY - 8, (int) dragX + 38, (int) dragY + 8, 0xE6121D22);
				g.outline((int) dragX - 38, (int) dragY - 8, 76, 16, CYAN);
				g.centeredText(client.font, drone.unitId(), (int) dragX, (int) dragY - 4, TEXT);
			}
		}
		g.pose().popMatrix();
	}

	private void drawHeader(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones,
		List<LivingEntity> mapEntities, TacticalUiPolicy.CommandAvailability availability) {
		g.fill(0, 0, virtualWidth(), l.header, PANEL);
		g.fill(0, 0, 5, l.header, CYAN);
		g.horizontalLine(0, virtualWidth(), l.header - 1, BORDER);
		g.text(client.font, "MORROWGEAR", 15, 10, TEXT, true);
		g.text(client.font, "COMMAND NETWORK", 15, 24, MUTED);
		g.text(client.font, "C2", 109, 10, CYAN, true);
		g.verticalLine(137, 8, 35, BORDER);
		drawHeaderMetric(g, 150, "ONLINE", Integer.toString(drones.size()), GREEN);
		drawHeaderMetric(g, 228, "SELECTED", Integer.toString(availability.selected()), CYAN);
		int threatScore = drones.stream().mapToInt(DroneEntity::threatScore).max().orElse(0);
		long hostileCount = mapEntities.stream().filter(entity -> entity instanceof Enemy).count();
		int threatColor = threatScore >= 28 ? RED : threatScore >= 14 ? AMBER : threatScore >= 6 ? 0xFFFFD866 : GREEN;
		drawHeaderMetric(g, 318, "THREAT", threatLabel(threatScore) + " " + threatScore, threatColor);
		drawHeaderMetric(g, 430, "HOSTILES", Long.toString(hostileCount), hostileCount > 0 ? AMBER : MUTED);
		String context = armedGuard ? "警戒地点を地図でダブルクリック"
			: armedWork != FieldOperationType.NONE ? WORK_LABELS[workIndex(armedWork)] + " の中心を地図でダブルクリック"
			: "OVERWORLD-01 / 指揮リンク正常";
		g.text(client.font, fitText(context, Math.max(100, virtualWidth() - 650)), 530, 17,
			armedGuard || armedWork != FieldOperationType.NONE ? AMBER : MUTED);
		String clock = LocalTime.now().withNano(0).toString();
		g.text(client.font, clock, virtualWidth() - client.font.width(clock) - 14, 17, TEXT, true);
		for (int index = 0; index < PAGES.length; index++) {
			Rect tab = pageTab(index);
			if (page == index) { g.fill(tab.x, tab.y, tab.right(), tab.bottom(), PANEL_ALT);
				g.fill(tab.x, tab.bottom() - 2, tab.right(), tab.bottom(), CYAN); }
			HmiArt.icon(g, PAGE_ICONS[index], tab.x + 8, tab.y + 4, 15);
			g.text(client.font, PAGES[index], tab.x + 29, tab.y + 8, page == index ? CYAN : MUTED);
		}
	}

	private void drawHeaderMetric(GuiGraphicsExtractor g, int x, String label, String value, int color) {
		g.text(client.font, label, x, 9, MUTED);
		g.text(client.font, value, x, 23, color, true);
	}

	private void drawLeftPanel(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, List<DockInfo> docks, int mouseX, int mouseY) {
		drones = visibleRoster(drones);
		g.fill(0, l.header, l.leftWidth, l.bottomTop, PANEL);
		g.verticalLine(l.leftWidth - 1, l.header, l.bottomTop, BORDER);
		g.text(client.font, "FLEET", 10, l.header + 9, TEXT, true);
		for (int i = 0; i < 2; i++) {
			Rect filter = rosterFilterRect(l, i);
			g.fill(filter.x, filter.y, filter.right(), filter.bottom(), PANEL_ALT);
			g.text(client.font, i == 0 ? roleFilter < 0 ? "全ロール" : DroneRole.values()[roleFilter].displayName()
				: new String[] {"ID順", "ロール順", "電力順", "任務順"}[sortMode], filter.x + 6, filter.y + 5, MUTED);
		}
		g.text(client.font, "戦力・編成", 52, l.header + 9, MUTED);
		String roster = drones.size() + " UNITS";
		g.text(client.font, roster, l.leftWidth - client.font.width(roster) - 10, l.header + 9, CYAN);

		String[] tabs = { "UNIT", "WING", "ALL" };
		for (int i = 0; i < tabs.length; i++) {
			Rect tab = tabRect(l, i);
			boolean active = selectionMode.ordinal() == i;
			g.fill(tab.x, tab.y, tab.right(), tab.bottom(), active ? PANEL_ALT : PANEL);
			if (active) g.horizontalLine(tab.x, tab.right(), tab.bottom() - 1, CYAN);
			g.centeredText(client.font, tabs[i], tab.x + tab.w / 2, tab.y + 6, active ? TEXT : MUTED);
		}

		List<OperationalWing> wings = operationalWings(drones);
		int rowCount = selectionMode == SelectionMode.GROUP && wingMapMode ? wings.size() : drones.size();
		int unitCapacity = unitCapacity(l);
		unitScroll = clampScroll(unitScroll, rowCount, unitCapacity);
		for (int visible = 0; visible < unitCapacity && unitScroll + visible < rowCount; visible++) {
			Rect row = unitRect(l, visible);
			if (selectionMode == SelectionMode.GROUP && wingMapMode) {
				OperationalWing wing = wings.get(unitScroll + visible);
				boolean active = wing.members.stream().allMatch(drone -> selected.contains(drone.getId()));
				boolean hover = row.contains(mouseX, mouseY);
				g.fill(row.x, row.y, row.right(), row.bottom(), active ? 0xFF123A40 : hover ? PANEL_ALT : PANEL);
				g.outline(row.x, row.y, row.w, row.h, active ? CYAN : BORDER);
				int stateColor = wing.averageBattery < 35 ? AMBER : GREEN;
				drawSelectionToggle(g, row, active, stateColor);
				g.text(client.font, wing.label, row.x + 18, row.y + 6, TEXT, true);
				String detail = wing.members.size() + "機 / L " + shortId(wing.leader.unitId())
					+ " / REC " + wing.recoveryCount;
				g.text(client.font, fitText(detail, row.w - 86), row.x + 18, row.y + 18, MUTED);
				String battery = wing.averageBattery + "%";
				g.text(client.font, battery, row.right() - client.font.width(battery) - 7, row.y + 10, stateColor, true);
				continue;
			}
			DroneEntity drone = drones.get(unitScroll + visible);
			boolean active = selected.contains(drone.getId());
			boolean hover = row.contains(mouseX, mouseY);
			g.fill(row.x, row.y, row.right(), row.bottom(), active ? 0xFF17343B : hover ? PANEL_RAISED : PANEL);
			g.outline(row.x, row.y, row.w, row.h, active ? CYAN : BORDER);
			int stateColor = drone.batteryPercent() < 35 ? AMBER : GREEN;
			drawSelectionToggle(g, row, active, stateColor);
			HmiArt.role(g, drone.role(), row.x + 16, row.y + 3, 26);
			g.text(client.font, shortId(drone.unitId()), row.x + 44, row.y + 6, TEXT, true);
			String assignment = selectionMode == SelectionMode.GROUP
				? (drone.groupId().startsWith("WING-") ? drone.groupId() : "UNIT POOL")
				: "W" + (drone.wingIndex() + 1) + " / " + aircraftDockLabel(drone);
			g.text(client.font, fitText(drone.role().displayName() + " / " + assignment, row.w - 112), row.x + 44, row.y + 18, MUTED);
			String battery = drone.batteryPercent() + "%";
			g.text(client.font, battery, row.right() - client.font.width(battery) - 7, row.y + 10, stateColor, true);
		}
		drawScrollCounter(g, l.leftWidth - 145, l.dockHeaderY - 10, unitScroll, rowCount, unitCapacity);
		if (rowCount > unitCapacity) drawPageButtons(g, unitFirstRect(l), unitUpRect(l), unitDownRect(l), unitLastRect(l));

		g.horizontalLine(7, l.leftWidth - 7, l.dockHeaderY - 5, BORDER);
		g.text(client.font, "DOCK NETWORK", 9, l.dockHeaderY, TEXT, true);
		String dockCount = docks.size() + " ONLINE";
		g.text(client.font, dockCount, l.leftWidth - client.font.width(dockCount) - 9, l.dockHeaderY, GREEN);
		int dockCapacity = dockCapacity(l);
		dockScroll = clampScroll(dockScroll, docks.size(), dockCapacity);
		for (int visible = 0; visible < dockCapacity && dockScroll + visible < docks.size(); visible++) {
			DockInfo dock = docks.get(dockScroll + visible);
			Rect row = dockRect(l, visible);
			boolean hover = row.contains(mouseX, mouseY);
			g.fill(row.x, row.y, row.right(), row.bottom(), hover ? 0xFF1B343A : PANEL_ALT);
			g.outline(row.x, row.y, row.w, row.h, hover ? CYAN : BORDER);
			g.text(client.font, dock.id, row.x + 7, row.y + 7, TEXT);
			String state = DockAllocationUiPolicy.dockStateLabel(dock.allocation) + "  "
				+ DockAllocationUiPolicy.queueLabel(dock.allocation) + "  "
				+ DockAllocationUiPolicy.holdingLabel(dock.allocation);
			int stateColor = allocationColor(dock.allocation.state());
			g.text(client.font, fitText(state, row.w - client.font.width(dock.id) - 22),
				row.right() - client.font.width(fitText(state, row.w - client.font.width(dock.id) - 22)) - 7,
				row.y + 7, stateColor);
		}
		drawScrollCounter(g, l.leftWidth - 145, l.bottomTop - 11, dockScroll, docks.size(), dockCapacity);
		if (docks.size() > dockCapacity) drawPageButtons(g, dockFirstRect(l), dockUpRect(l), dockDownRect(l), dockLastRect(l));
	}

	private void drawMap(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, List<LivingEntity> mapEntities, int mouseX, int mouseY) {
		Rect map = l.map;
		g.fill(map.x, map.y, map.right(), map.bottom(), 0xFF091317);
		drawTerrain(g, map);
		drawSecurityZones(g, map, drones);
		for (int x = map.x; x <= map.right(); x += 16) g.verticalLine(x, map.y, map.bottom(), GRID);
		for (int y = map.y; y <= map.bottom(); y += 16) g.horizontalLine(map.x, map.right(), y, GRID);
		g.fill(map.x, map.y, map.right(), map.y + 28, 0xE6111920);
		g.horizontalLine(map.x, map.right(), map.y + 28, BORDER);
		g.text(client.font, "TACTICAL MAP", map.x + 10, map.y + 9, TEXT, true);
		g.text(client.font, "OVERWORLD-01", map.x + 92, map.y + 9, MUTED);
		String zoom = String.format("ZOOM %.1fx", mapZoom / 5.0);
		g.text(client.font, zoom, map.right() - client.font.width(zoom) - 10, map.y + 9, CYAN);
		drawPatrolRoutes(g, map, drones);
		drawRouteControls(g, map, mouseX, mouseY);
		int cx = map.x + map.w / 2;
		int cy = map.y + map.h / 2;
		g.outline(cx - 70, cy - 70, 140, 140, 0xFF45636B);
		g.text(client.font, "OPERATOR ORIGIN", cx - 70, cy - 82, MUTED);
		g.fill(cx - 4, cy - 4, cx + 4, cy + 4, PANEL_ALT);
		g.outline(cx - 4, cy - 4, 8, 8, MUTED);

		if (client.player == null) return;
		Set<String> fieldMarkers = new LinkedHashSet<>();
		for (DroneEntity drone : drones) {
			boolean visibleOperation = selected.contains(drone.getId())
				|| drone.fieldOrderId().startsWith("AUTO-");
			if (!drone.hasFieldOperation() || !visibleOperation
				|| !fieldMarkers.add(drone.fieldOrderId())) continue;
			MapPoint point = mapPoint(map, drone.fieldAnchor().getX() + 0.5, drone.fieldAnchor().getZ() + 0.5);
			if (point == null) continue;
			int radius = Math.max(4, (int)Math.round(drone.fieldRadius() * mapZoom));
			drawOrbitRing(g, map, point.x, point.y, radius, GREEN);
			if (selected.size() <= 8) {
				String label = drone.fieldOperationType().label() + " / " + TacticalUiPolicy.radiusLabel(drone.fieldRadius()) + " / "
					+ drone.fieldProgress() + "% F" + drone.fieldFound();
				int labelWidth = client.font.width(label) + 10;
				int labelX = Math.max(map.x + 4, Math.min(point.x + 7, map.right() - labelWidth - 4));
				int labelY = Math.max(map.y + 33, point.y - 23);
				g.fill(labelX, labelY, labelX + labelWidth, labelY + 14, 0xE6081014);
				g.text(client.font, label, labelX + 5, labelY + 3, GREEN);
			}
		}
		Set<String> highlightedWings = new LinkedHashSet<>();
		for (DroneEntity drone : drones) {
			if (!selected.contains(drone.getId()) || drone.mode() != DroneMode.WAYPOINT || !drone.hasWaypoint()) continue;
			String wingKey = drone.missionId() + "#" + drone.wingIndex();
			if (!highlightedWings.add(wingKey)) continue;
			MapPoint center = mapPoint(map, drone.waypointPos().getX() + 0.5, drone.waypointPos().getZ() + 0.5);
			if (center == null) continue;
			int radius = Math.max(3, (int) Math.round(drone.orbitLayerRadius() * mapZoom));
			drawOrbitRing(g, map, center.x, center.y, radius, CYAN);
			if (selected.size() <= 8) {
				String layer = "W" + (drone.wingIndex() + 1) + " ALT +" + (int) Math.round(drone.orbitLayerHeight());
				g.fill(center.x + radius + 4, center.y - 7, center.x + radius + 10 + client.font.width(layer), center.y + 7, 0xD9081014);
				g.text(client.font, layer, center.x + radius + 7, center.y - 4, CYAN);
			}
		}
		for (LivingEntity entity : mapEntities) {
			MapPoint point = mapPoint(map, entity.getX(), entity.getZ());
			if (point == null) continue;
			int color = entity instanceof Enemy ? RED : entity instanceof Animal ? GREEN : AMBER;
			drawMobMarker(g, point.x, point.y, color, entity instanceof Enemy);
			if (Math.abs(mouseX - point.x) <= 7 && Math.abs(mouseY - point.y) <= 7) {
				String label = entity.getName().getString();
				g.fill(point.x + 8, point.y - 7, point.x + 14 + client.font.width(label), point.y + 7, 0xE6081014);
				g.text(client.font, label, point.x + 11, point.y - 4, TEXT);
			}
		}
		boolean detailedRoutes = selected.size() <= 8;
		for (DroneEntity drone : drones) {
			int dx = (int) Math.max(-map.w / 2 + 18, Math.min(map.w / 2 - 92, (drone.getX() - client.player.getX()) * mapZoom));
			int dz = (int) Math.max(-map.h / 2 + 18, Math.min(map.h / 2 - 18, (drone.getZ() - client.player.getZ()) * mapZoom));
			int px = cx + dx;
			int py = cy + dz;
			boolean wingLeader = !drone.cohortLeaderId().isBlank()
				&& drone.unitId().equals(drone.cohortLeaderId());
			boolean routeVisible = wingLeader || detailedRoutes && selected.contains(drone.getId());
			if (routeVisible) drawLine(g, cx, cy, px, py, drone.batteryPercent() < 35 ? AMBER : CYAN);
			if (drone.missionStage() == DroneEntity.MISSION_CONVERGING && drone.hasRendezvous()) {
				MapPoint join = mapPoint(map, drone.rendezvousPos().getX(), drone.rendezvousPos().getZ());
				if (join != null) {
					drawLine(g, px, py, join.x, join.y, AMBER);
					g.outline(join.x - 3, join.y - 3, 6, 6, AMBER);
				}
			}
			if (routeVisible && drone.mode() == DroneMode.WAYPOINT && drone.hasWaypoint()) {
				LivingEntity tracked = drone.hasTrackingTarget()
					? mapEntities.stream().filter(entity -> entity.getUUID().equals(drone.trackingTargetId())).findFirst().orElse(null)
					: null;
				double targetX = tracked == null ? drone.waypointPos().getX() : tracked.getX();
				double targetZ = tracked == null ? drone.waypointPos().getZ() : tracked.getZ();
				int wx = cx + (int) ((targetX - client.player.getX()) * mapZoom);
				int wz = cy + (int) ((targetZ - client.player.getZ()) * mapZoom);
				if (map.contains(wx, wz)) {
					drawLine(g, px, py, wx, wz, GREEN);
					if (tracked == null) g.outline(wx - 5, wz - 5, 10, 10, GREEN);
					else g.outline(wx - 6, wz - 6, 12, 12, AMBER);
				}
			}
			drawDiamond(g, px, py, selected.contains(drone.getId()) ? TEXT : CYAN);
			if (wingLeader) {
				g.text(client.font, "L", px - 3, py - 15, AMBER, true);
			}
			if (selectionMode == SelectionMode.UNIT && selected.contains(drone.getId())) {
				g.fill(px + 9, py - 7, px + 83, py + 7, 0xD9081014);
				g.text(client.font, shortId(drone.unitId()) + " / " + cohortLabel(drone), px + 13, py - 4, TEXT);
			}
		}
		String contacts = "LIFE " + mapEntities.size() + " / HOSTILE " + mapEntities.stream().filter(entity -> entity instanceof Enemy).count();
		g.text(client.font, contacts, map.right() - client.font.width(contacts) - 10, map.bottom() - 14, MUTED);
	}

	private void drawWingBoard(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, int mouseX, int mouseY) {
		Rect board = l.map;
		g.fill(board.x, board.y, board.right(), board.bottom(), 0xFF091317);
		for (int x = board.x; x <= board.right(); x += 16) g.verticalLine(x, board.y, board.bottom(), GRID);
		for (int y = board.y; y <= board.bottom(); y += 16) g.horizontalLine(board.x, board.right(), y, GRID);
		g.text(client.font, "WING MANAGEMENT", board.x + 12, board.y + 11, TEXT, true);
		g.text(client.font, "HARD LIMIT 8 UNITS", board.right() - 116, board.y + 11, CYAN);
		Rect create = newWingRect(board);
		boolean createHover = create.contains(mouseX, mouseY);
		g.fill(create.x, create.y, create.right(), create.bottom(), createHover ? 0xFF174139 : PANEL_ALT);
		g.outline(create.x, create.y, create.w, create.h, createHover ? GREEN : CYAN);
		g.centeredText(client.font, "+  NEW WING", create.x + create.w / 2, create.y + 6, TEXT);

		List<WingCard> cards = wingCards(drones);
		int columns = wingCardColumns(board);
		int rowsVisible = wingCardRowsVisible(board);
		int totalRows = Math.max(1, (cards.size() + columns - 1) / columns);
		wingBoardScroll = clampScroll(wingBoardScroll, totalRows, rowsVisible);
		int firstCard = wingBoardScroll * columns;
		int capacity = rowsVisible * columns;
		for (int visible = 0; visible < capacity && firstCard + visible < cards.size(); visible++) {
			int cardIndex = firstCard + visible;
			Rect card = wingCardRect(board, visible);
			boolean hover = card.contains(mouseX, mouseY);
			g.fill(card.x, card.y, card.right(), card.bottom(), hover ? 0xFF162D33 : PANEL);
			g.outline(card.x, card.y, card.w, card.h, hover ? CYAN : BORDER);

			WingCard wing = cards.get(cardIndex);
			int stateColor = wing.averageBattery < 35 ? AMBER : GREEN;
			g.fill(card.x, card.y, card.right(), card.y + 29, PANEL_ALT);
			String count = wing.members.size() + " / " + WingMembershipPolicy.MAX_MEMBERS;
			g.text(client.font, fitText(wing.groupId, card.w - client.font.width(count) - 24),
				card.x + 8, card.y + 8, TEXT, true);
			g.text(client.font, count, card.right() - client.font.width(count) - 8, card.y + 8,
				wing.members.size() >= WingMembershipPolicy.MAX_MEMBERS ? AMBER : CYAN, true);
			String condition = "BAT " + wing.averageBattery + "%  REC " + wing.recoveryCount;
			int conditionX = card.right() - client.font.width(condition) - 8;
			g.text(client.font, fitText("L " + shortId(wing.leader.unitId()) + "  " + wing.missionLabel,
				conditionX - card.x - 16), card.x + 8, card.y + 32, MUTED);
			g.text(client.font, condition, conditionX, card.y + 32, stateColor);

			for (int slot = 0; slot < WingMembershipPolicy.MAX_MEMBERS; slot++) {
				Rect slotRect = wingSlotRect(card, slot);
				boolean slotHover = slotRect.contains(mouseX, mouseY);
				g.fill(slotRect.x, slotRect.y, slotRect.right(), slotRect.bottom(), slotHover ? 0xFF1C3A40 : 0xFF0D171B);
				g.outline(slotRect.x, slotRect.y, slotRect.w, slotRect.h, slotHover ? CYAN : GRID);
				if (slot >= wing.members.size()) {
					g.centeredText(client.font, String.format("%02d", slot + 1), slotRect.x + slotRect.w / 2,
						slotRect.y + 6, 0xFF355159);
					continue;
				}
				DroneEntity member = wing.members.get(slot);
				int roleColor = moduleColor(member.role());
				if (selected.contains(member.getId())) g.outline(slotRect.x, slotRect.y, slotRect.w, slotRect.h, CYAN);
				g.fill(slotRect.x + 4, slotRect.y + 5, slotRect.x + 7, slotRect.bottom() - 5, roleColor);
				g.text(client.font, shortId(member.unitId()), slotRect.x + 11, slotRect.y + 6,
					selected.contains(member.getId()) ? CYAN : TEXT, true);
			}
		}
		if (totalRows > rowsVisible) {
			String page = (wingBoardScroll + 1) + "/" + Math.max(1, totalRows - rowsVisible + 1);
			g.text(client.font, page, board.right() - client.font.width(page) - 12, board.bottom() - 15, MUTED);
		}
	}

	private void drawSecurityZones(GuiGraphicsExtractor g, Rect map, List<DroneEntity> drones) {
		Set<String> drawn = new LinkedHashSet<>();
		for (DroneEntity drone : drones) {
			if (!drone.hasSecurityPatrol()) continue;
			BlockPos anchor = drone.securityAnchor();
			String key = anchor.asLong() + ":" + drone.securityRadius();
			if (!drawn.add(key)) continue;
			int color = selected.contains(drone.getId()) ? RED : AMBER;
			MapPoint previous = null;
			for (int segment = 0; segment <= 32; segment++) {
				double angle = segment * Math.PI * 2.0 / 32.0;
				MapPoint point = mapPoint(map, anchor.getX() + 0.5 + Math.cos(angle) * drone.securityRadius(),
					anchor.getZ() + 0.5 + Math.sin(angle) * drone.securityRadius());
				if (previous != null && point != null) drawLine(g, previous.x, previous.y, point.x, point.y, color);
				previous = point;
			}
			MapPoint center = mapPoint(map, anchor.getX() + 0.5, anchor.getZ() + 0.5);
			if (center != null && selected.size() <= 8) g.text(client.font, "SEC / " + TacticalUiPolicy.radiusLabel(drone.securityRadius()),
				center.x + 6, center.y - 5, color, true);
		}
	}

	private void drawWingModeToggle(GuiGraphicsExtractor g, Layout l, int mouseX, int mouseY) {
		Rect toggle = wingModeToggleRect(l.map);
		boolean hover = toggle.contains(mouseX, mouseY);
		g.fill(toggle.x, toggle.y, toggle.right(), toggle.bottom(), hover ? 0xFF174139 : PANEL_ALT);
		g.outline(toggle.x, toggle.y, toggle.w, toggle.h, hover ? GREEN : CYAN);
		g.centeredText(client.font, wingMapMode ? "OPEN WING BOARD" : "OPEN TACTICAL MAP",
			toggle.x + toggle.w / 2, toggle.y + 6, TEXT);
	}

	private MapPoint mapPoint(Rect map, double worldX, double worldZ) {
		if (client.player == null) return null;
		int x = map.x + map.w / 2 + (int) ((worldX - client.player.getX()) * mapZoom);
		int y = map.y + map.h / 2 + (int) ((worldZ - client.player.getZ()) * mapZoom);
		return map.contains(x, y) ? new MapPoint(x, y) : null;
	}

	private static void drawMobMarker(GuiGraphicsExtractor g, int x, int y, int color, boolean hostile) {
		if (hostile) {
			g.horizontalLine(x - 4, x + 4, y, color);
			g.verticalLine(x, y - 4, y + 4, color);
			g.fill(x - 1, y - 1, x + 2, y + 2, BG);
		} else {
			g.fill(x - 2, y - 2, x + 3, y + 3, color);
			g.fill(x - 1, y - 1, x + 2, y + 2, BG);
		}
	}

	private void drawTerrain(GuiGraphicsExtractor g, Rect map) {
		if (client.level == null || client.player == null) return;
		int centerWorldX = (int) Math.floor(client.player.getX());
		int centerWorldZ = (int) Math.floor(client.player.getZ());
		int zoomKey = (int) Math.round(mapZoom * 100.0);
		long gameTime = client.level.getGameTime();
		boolean stale = terrainCacheLevel != client.level
			|| terrainCacheCenterX != centerWorldX || terrainCacheCenterZ != centerWorldZ
			|| terrainCacheZoom != zoomKey || !map.equals(terrainCacheMap)
			|| gameTime >= terrainRefreshAt;
		if (stale) rebuildTerrainCache(map, centerWorldX, centerWorldZ, zoomKey, gameTime);
		if (terrainTexture != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, TERRAIN_TEXTURE_ID, map.x, map.y, 0, 0,
				map.w, map.h, terrainTextureWidth, terrainTextureHeight,
				terrainTextureWidth, terrainTextureHeight);
		}
	}

	private void rebuildTerrainCache(Rect map, int centerWorldX, int centerWorldZ, int zoomKey, long gameTime) {
		terrainCacheLevel = client.level;
		terrainCacheCenterX = centerWorldX;
		terrainCacheCenterZ = centerWorldZ;
		terrainCacheZoom = zoomKey;
		terrainCacheMap = map;
		terrainRefreshAt = gameTime + 40;

		final int sample = 3;
		int columns = (map.w + sample - 1) / sample;
		int rows = (map.h + sample - 1) / sample;
		ensureTerrainTexture(columns, rows);
		NativeImage pixels = terrainTexture.getPixels();
		if (pixels == null) return;
		int[] heights = new int[columns * rows];
		int[] colors = new int[columns * rows];
		boolean[] water = new boolean[columns * rows];
		int centerX = map.x + map.w / 2;
		int centerY = map.y + map.h / 2;

		for (int row = 0; row < rows; row++) {
			int screenY = map.y + row * sample;
			for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
				int screenX = map.x + columnIndex * sample;
				int worldX = (int) Math.floor(client.player.getX() + (screenX + sample / 2.0 - centerX) / mapZoom);
				int worldZ = (int) Math.floor(client.player.getZ() + (screenY + sample / 2.0 - centerY) / mapZoom);
				int index = row * columns + columnIndex;
				BlockPos column = new BlockPos(worldX, 0, worldZ);
				if (!client.level.hasChunkAt(column)) {
					heights[index] = Integer.MIN_VALUE;
					continue;
				}
				int height = client.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
				BlockPos surface = new BlockPos(worldX, height, worldZ);
				var state = client.level.getBlockState(surface);
				heights[index] = height;
				colors[index] = state.getMapColor(client.level, surface).col;
				water[index] = !state.getFluidState().isEmpty();
			}
		}

		for (int row = 0; row < rows; row++) {
			int screenY = map.y + row * sample;
			for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
				int index = row * columns + columnIndex;
				int height = heights[index];
				if (height == Integer.MIN_VALUE) {
					pixels.setPixel(columnIndex, row, 0xFF091317);
					continue;
				}
				int west = columnIndex > 0 && heights[index - 1] != Integer.MIN_VALUE ? heights[index - 1] : height;
				int north = row > 0 && heights[index - columns] != Integer.MIN_VALUE ? heights[index - columns] : height;
				int slope = height - (west + north) / 2;
				pixels.setPixel(columnIndex, row, terrainColor(colors[index], height, slope, water[index]));
			}
		}
		terrainTexture.upload();
	}

	private void ensureTerrainTexture(int width, int height) {
		if (terrainTexture != null && terrainTextureWidth == width && terrainTextureHeight == height) return;
		if (terrainTexture != null) client.getTextureManager().release(TERRAIN_TEXTURE_ID);
		terrainTextureWidth = width;
		terrainTextureHeight = height;
		terrainTexture = new DynamicTexture(() -> "Morrowgear C2 terrain", width, height, false);
		client.getTextureManager().register(TERRAIN_TEXTURE_ID, terrainTexture);
	}

	void close() {
		if (terrainTexture == null) return;
		client.getTextureManager().release(TERRAIN_TEXTURE_ID);
		terrainTexture = null;
	}

	private static int terrainColor(int raw, int height, int slope, boolean water) {
		int red = raw >> 16 & 0xff;
		int green = raw >> 8 & 0xff;
		int blue = raw & 0xff;
		int luminance = (red * 3 + green * 5 + blue * 2) / 10;
		int elevation = Math.max(-16, Math.min(24, height - 64));
		int relief = Math.max(-14, Math.min(14, slope * 4));
		int contour = Math.floorMod(height, 5) == 0 ? -4 : 0;
		red = clampColor((red * 2 + luminance) / 6 + 8 + elevation / 5 + relief + contour, 5, 104);
		green = clampColor((green * 2 + luminance) / 5 + 10 + elevation / 4 + relief + contour, 10, 122);
		blue = clampColor((blue * 2 + luminance) / 5 + 12 + elevation / 5 + relief + contour, 12, 128);
		if (water) {
			red = clampColor(red - 8, 4, 62);
			green = clampColor(green + 5, 20, 92);
			blue = clampColor(blue + 22, 42, 142);
		}
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private static int clampColor(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private void drawCommandPanel(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, int mouseX, int mouseY) {
		Rect right = l.right;
		TacticalUiPolicy.CommandAvailability availability = availability(drones);
		g.fill(right.x, right.y, right.right(), right.bottom(), PANEL);
		g.verticalLine(right.x, right.y, right.bottom(), BORDER);
		g.text(client.font, "COMMAND", right.x + 10, right.y + 9, TEXT, true);
		String count = selected.size() + " SELECTED";
		g.text(client.font, count, right.right() - client.font.width(count) - 9, right.y + 9, CYAN);
		List<DroneEntity> selection = drones.stream().filter(d -> selected.contains(d.getId())).toList();
		boolean multiple = selection.size() > 1;
		DroneEntity active = selection.size() == 1 ? selection.getFirst() : null;
		int infoY = right.y + 34;
		g.horizontalLine(right.x, right.right(), infoY - 7, BORDER);
		String identity = multiple ? selection.size() + " UNITS / " + selection.stream().map(d -> d.role().name()).distinct().collect(java.util.stream.Collectors.joining(" + "))
			: active == null ? "NO UNIT SELECTED" : active.unitId();
		g.text(client.font, fitText(identity, right.w - 20), right.x + 10, infoY, TEXT, true);
		g.text(client.font, multiple ? "稼働 " + selection.stream().filter(this::hasActiveMission).count()
			+ " / 着艦 " + selection.stream().filter(DroneEntity::isDocked).count()
			: active == null ? "左の一覧から対象を選択" : fitText(statusLabel(active), right.w - 20),
			right.x + 10, infoY + 17, active == null ? MUTED : CYAN);
		String batteryRange = selection.isEmpty() ? "--" : selection.stream().mapToInt(DroneEntity::batteryPercent).min().orElse(0)
			+ (multiple ? "-" + selection.stream().mapToInt(DroneEntity::batteryPercent).max().orElse(0) : "") + "%";
		g.text(client.font, batteryRange, right.x + 10, infoY + 39, selection.stream().anyMatch(d -> d.batteryPercent() < 35) ? AMBER : GREEN, true);
		g.text(client.font, "BATTERY", right.x + 10, infoY + 51, MUTED);
		g.text(client.font, "LINK", right.x + 96, infoY + 51, MUTED);
		g.text(client.font, fitText(multiple ? "MULTIPLE" : active == null ? "--" : active.dataLinkStatus(), right.w - 106), right.x + 96, infoY + 39,
			multiple ? MUTED : active == null ? RED : active.dataLinkStatus().contains("HAZARDOUS") ? AMBER : GREEN, true);
		int joined = active == null ? 0 : (int) drones.stream()
			.filter(drone -> (drone.missionStage() == DroneEntity.MISSION_MOVING
				|| drone.missionStage() == DroneEntity.MISSION_ORBIT_ENTRY
				|| drone.missionStage() == DroneEntity.MISSION_ORBIT)
				&& drone.cohortId().equals(active.cohortId())).count();
		int cohortSize = active == null ? 0 : (int) drones.stream()
			.filter(drone -> !active.cohortId().isBlank() && drone.cohortId().equals(active.cohortId())).count();
		int wingRecovery = active == null ? 0 : (int) drones.stream()
			.filter(drone -> drone.missionId().equals(active.missionId()) && drone.wingIndex() == active.wingIndex()
				&& drone.recoveryLevel() > 0).count();
		String wing = active == null ? "--" : (active.wingIndex() + 1) + "/" + active.wingCount();
		if (multiple) {
			String roles = selection.stream().map(DroneEntity::role).distinct().sorted()
				.map(role -> TacticalUiPolicy.moduleLabel(role) + " " + selection.stream().filter(d -> d.role() == role).count())
				.collect(java.util.stream.Collectors.joining(" / "));
			List<DroneEntity> visible = visibleRoster(drones);
			long hidden = selection.stream().filter(d -> !visible.contains(d)).count();
			g.text(client.font, fitText(roles, right.w - 20), right.x + 10, infoY + 66, MUTED);
			g.text(client.font, fitText(selectionWingSummary(drones, selection), right.w - 20), right.x + 10, infoY + 78, TEXT);
			String scope = hidden > 0 ? "HIDDEN BY FILTER " + hidden : "SELECTED " + selection.size();
			g.text(client.font, fitText(scope + " / NO DOCK " + selection.stream().filter(d -> !d.hasDock()).count(), right.w - 20),
				right.x + 10, infoY + 90, hidden > 0 ? AMBER : MUTED);
		} else {
			g.text(client.font, fitText("ROLE    " + (active == null ? "--" : active.role().displayName())
				+ "  WING " + wing, right.w - 20), right.x + 10, infoY + 66, MUTED);
			g.text(client.font, "LEADER  " + (active == null || active.cohortLeaderId().isBlank()
				? "--" : shortId(active.cohortLeaderId())), right.x + 10, infoY + 78, active != null && active.unitId().equals(active.cohortLeaderId()) ? AMBER : TEXT);
			String slot = active == null ? "--" : active.missionStage() == DroneEntity.MISSION_CONVERGING
				? "JOIN -> " + rendezvousLabel(active) : (active.cohortRank() + 1) + " / " + Math.max(joined, cohortSize);
			g.text(client.font, fitText("SLOT    " + slot + "  REC " + wingRecovery, right.w - 20), right.x + 10, infoY + 90,
				active != null && active.missionStage() == DroneEntity.MISSION_CONVERGING ? AMBER : TEXT);
		}
		g.text(client.font, "FLIGHT CONTROL", right.x + 9, infoY + 108, MUTED);
		for (int i = 0; i < ACTIONS.length; i++) {
			Rect button = commandRect(l, i);
			boolean hover = button.contains(mouseX, mouseY);
			boolean enabled = availability.hasSelection();
			int color = ACTIONS[i].equals("decommission") ? RED : ACTIONS[i].equals("dock") ? AMBER : CYAN;
			boolean confirming = ACTIONS[i].equals("decommission") && client.level != null
				&& client.level.getGameTime() < confirmStoreUntil;
			g.fill(button.x, button.y, button.right(), button.bottom(), confirming ? 0xFF492129
				: enabled && hover ? PANEL_RAISED : PANEL_ALT);
			g.outline(button.x, button.y, button.w, button.h, enabled ? color : BORDER);
			g.centeredText(client.font, fitText(confirming ? "CONFIRM STORE" : actionLabel(i, drones), button.w - 8),
				button.x + button.w / 2, button.y + 8,
				enabled ? TEXT : MUTED);
		}
		for (int i = 0; i < WORK_TYPES.length; i++) {
			Rect button = workRect(l, i);
			boolean armed = armedWork == WORK_TYPES[i];
			boolean hover = button.contains(mouseX, mouseY);
			boolean enabled = autoOperations ? !drones.isEmpty() : availability.workEnabled(WORK_TYPES[i]);
			g.fill(button.x, button.y, button.right(), button.bottom(), armed ? 0xFF174139 : enabled && hover ? PANEL_RAISED : PANEL_ALT);
			g.outline(button.x, button.y, button.w, button.h, enabled ? armed ? GREEN : BLUE : BORDER);
			g.centeredText(client.font, fitText(WORK_LABELS[i], button.w - 6), button.x + button.w / 2, button.y + 8,
				enabled ? armed ? GREEN : TEXT : MUTED);
		}
		Rect guard = workRect(l, WORK_TYPES.length);
		boolean guardHover = guard.contains(mouseX, mouseY);
		boolean guardEnabled = availability.securityEnabled();
		g.fill(guard.x, guard.y, guard.right(), guard.bottom(), armedGuard ? 0xFF4A2025 : guardEnabled && guardHover ? PANEL_RAISED : PANEL_ALT);
		g.outline(guard.x, guard.y, guard.w, guard.h, guardEnabled ? armedGuard ? RED : AMBER : BORDER);
		g.centeredText(client.font, "GUARD", guard.x + guard.w / 2, guard.y + 8,
			guardEnabled ? armedGuard ? RED : TEXT : MUTED);
		Rect auto = autoOperationsRect(l);
		g.text(client.font, autoOperations ? "FLEET TASK FORCE" : "SELECTED " + availability.selected(),
			right.x + 9, auto.y + 4, autoOperations ? GREEN : MUTED);
		g.fill(auto.x, auto.y, auto.right(), auto.bottom(), autoOperations ? 0xFF174139
			: auto.contains(mouseX, mouseY) ? PANEL_RAISED : PANEL_ALT);
		g.outline(auto.x, auto.y, auto.w, auto.h, autoOperations ? GREEN : BORDER);
		g.centeredText(client.font, autoOperations ? "AUTO OPS ON" : "AUTO OPS",
			auto.x + auto.w / 2, auto.y + 4, autoOperations ? GREEN : TEXT);

		int missionY = infoY + 214;
		if (page != 3) {
		g.text(client.font, "MISSION STATUS", right.x + 9, missionY, MUTED);
		drawAssignment(g, right.x + 9, missionY + 14, right.w - 18, "任務: " + (multiple ? "複数選択 / 任務タブで確認" : active == null ? "未選択" : statusLabel(active)), "");
		drawAssignment(g, right.x + 9, missionY + 38, right.w - 18,
			"DOCK: " + (multiple ? allocationSummary(selection) : active == null ? "未選択" : aircraftDockLabel(active)),
			active != null && active.isDocked() ? "着艦" : "--");
		}
		if (modulePanelVisible(l)) {
			boolean securitySelection = availability.hasSelection() && drones.stream()
				.filter(drone -> selected.contains(drone.getId()))
				.allMatch(drone -> drone.role() == DroneRole.SECURITY);
			boolean dualPanel = dualModulePanelVisible(l);
			boolean showRolePanel = dualPanel || !securitySelection;
			int moduleY = right.bottom() - (dualPanel ? 78 : 43);
			g.fill(right.x, moduleY - 4, right.right(), right.bottom(), PANEL);
			String bayState = selection.isEmpty() ? "NO UNIT" : selection.stream().allMatch(DroneEntity::isDocked) ? "BAY ONLINE" : "DOCK REQUIRED";
			if (showRolePanel) {
				g.text(client.font, "ROLE MODULE", right.x + 9, moduleY, MUTED);
				g.text(client.font, bayState, right.right() - client.font.width(bayState) - 9, moduleY,
					!selection.isEmpty() && selection.stream().allMatch(DroneEntity::isDocked) ? GREEN : AMBER);
				for (int i = 0; i < MODULE_ROLES.length; i++) {
					Rect button = moduleRect(l, i);
					DroneRole moduleRole = MODULE_ROLES[i];
					boolean installed = !selection.isEmpty() && selection.stream().allMatch(d -> d.role() == moduleRole);
					boolean hover = button.contains(mouseX, mouseY);
					boolean enabled = availability.hasSelection() && drones.stream()
						.filter(drone -> selected.contains(drone.getId())).allMatch(DroneEntity::isDocked);
					int color = moduleColor(MODULE_ROLES[i]);
					g.fill(button.x, button.y, button.right(), button.bottom(), installed ? 0xFF163B40 : enabled && hover ? PANEL_RAISED : PANEL_ALT);
					g.outline(button.x, button.y, button.w, button.h, installed ? color : enabled ? BORDER : 0xFF273840);
					g.centeredText(client.font, TacticalUiPolicy.moduleLabel(MODULE_ROLES[i]), button.x + button.w / 2, button.y + 7,
						enabled ? installed ? color : TEXT : MUTED);
				}
			}
			if (dualPanel || securitySelection) {
				int weaponY = right.bottom() - 37;
				g.text(client.font, "SECURITY LOADOUT", right.x + 9, weaponY, MUTED);
				Rect capacity = capacityUpgradeRect(l);
				boolean canUpgrade = securitySelection && selection.size() == 1 && active != null
					&& active.isDocked() && active.capacityTier() < jp.morrowgear.drone.PayloadCapacity.MAX_TIER;
				g.text(client.font, active == null ? "CAP --" : "CAP " + active.capacityTier() + " +",
					capacity.x, capacity.y + 2, canUpgrade ? CYAN : MUTED);
				if (capacity.contains(mouseX, mouseY) && active != null) {
					String detail = "GUN " + active.gunAmmo() + "/" + active.gunCapacity() + " MSL "
						+ active.missiles() + "/" + active.missileCapacity() + " ENERGY " + active.weaponCapacity();
					g.fill(right.x, weaponY - 32, right.right(), weaponY - 2, PANEL);
					g.text(client.font, fitText(detail, right.w - 18), right.x + 9, weaponY - 30, TEXT);
					String cost = active.capacityTier() == 0 ? "合金8 / ダイヤ1 / RSブロック2"
						: active.capacityTier() == 1 ? "合金16 / ダイヤ4 / RSブロック4 / 欠片2" : "MAX CAPACITY";
					g.text(client.font, fitText(cost, right.w - 18), right.x + 9, weaponY - 17, AMBER);
				}
				for (int i = 0; i < WEAPON_LOADOUTS.length; i++) {
					Rect button = weaponModuleRect(l, i);
					SecurityLoadout loadout = WEAPON_LOADOUTS[i];
					boolean installed = securitySelection && selection.stream().allMatch(d -> d.securityLoadout() == loadout);
					boolean hover = button.contains(mouseX, mouseY);
					g.fill(button.x, button.y, button.right(), button.bottom(),
						installed ? 0xFF4A2025 : securitySelection && hover ? PANEL_RAISED : PANEL_ALT);
					g.outline(button.x, button.y, button.w, button.h,
						installed ? RED : securitySelection ? BORDER : 0xFF273840);
					g.centeredText(client.font, WEAPON_LABELS[i], button.x + button.w / 2, button.y + 7,
						securitySelection ? installed ? RED : TEXT : MUTED);
				}
			}
		}
	}

	private void drawBottomPanel(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones) {
		g.fill(0, l.bottomTop, virtualWidth(), virtualHeight(), 0xFF0D171B);
		g.horizontalLine(0, virtualWidth(), l.bottomTop, BORDER);
		int split = (int) (virtualWidth() * 0.60);
		g.verticalLine(split, l.bottomTop, virtualHeight(), BORDER);
		g.fill(0, l.bottomTop, split, l.bottomTop + 29, PANEL);
		g.fill(split, l.bottomTop, virtualWidth(), l.bottomTop + 29, PANEL);
		g.text(client.font, "MISSION QUEUE", 10, l.bottomTop + 9, TEXT, true);
		long activeCount = drones.stream().filter(this::hasActiveMission).count();
		long queuedCount = drones.stream().filter(drone -> drone.role() == DroneRole.CARGO
			&& drone.cargoState().queued()).count();
		String activity = activeCount + " ACTIVE / " + queuedCount + " QUEUED";
		g.text(client.font, activity, split - client.font.width(activity) - 56, l.bottomTop + 9, CYAN);
		List<ContainerInfo> containers = containers();
		g.text(client.font, "LOGISTICS", split + 10, l.bottomTop + 9, TEXT, true);
		g.text(client.font, "コンテナ走査 / " + TacticalUiPolicy.radiusLabel(32), split + 80, l.bottomTop + 9, MUTED);
		String found = containers.size() + " FOUND";
		g.text(client.font, found, cargoUpRect(l).x - client.font.width(found) - 12, l.bottomTop + 9, CYAN);
		g.horizontalLine(0, virtualWidth(), l.bottomTop + 28, BORDER);
		g.text(client.font, "UNIT", 10, l.bottomTop + 37, MUTED);
		g.text(client.font, "MISSION / PROGRESS", 90, l.bottomTop + 37, MUTED);
		g.text(client.font, "STATE", split - 70, l.bottomTop + 37, MUTED);
		int capacity = Math.max(1, (virtualHeight() - (l.bottomTop + 55)) / 25);
		missionScroll = clampScroll(missionScroll, drones.size(), capacity);
		drawScrollCounter(g, split - 305, l.bottomTop + 9, missionScroll, drones.size(), capacity);
		if (drones.size() > capacity) drawPageButtons(g, missionFirstRect(l), missionUpRect(l), missionDownRect(l), missionLastRect(l));
		for (int visible = 0; visible < capacity && missionScroll + visible < drones.size(); visible++) {
			DroneEntity drone = drones.get(missionScroll + visible);
			int y = l.bottomTop + 55 + visible * 25;
			boolean activeRow = selected.contains(drone.getId());
			if (activeRow) g.fill(5, y - 3, split - 5, y + 18, 0xFF142B32);
			g.text(client.font, shortId(drone.unitId()), 9, y, activeRow ? CYAN : TEXT, true);
			g.text(client.font, fitText(statusLabel(drone), Math.max(70, split - 172)), 90, y, TEXT);
			g.fill(90, y + 12, split - 120, y + 15, 0xFF304249);
			g.fill(90, y + 12, 90 + (split - 210) * drone.batteryPercent() / 100, y + 15, CYAN);
			boolean activeMission = hasActiveMission(drone);
			g.text(client.font, activeMission ? "ACTIVE" : "STANDBY", split - 70, y,
				activeMission ? GREEN : MUTED);
		}

		DroneEntity cargoDrone = drones.stream().filter(drone -> selected.contains(drone.getId())
			&& drone.role() == DroneRole.CARGO).findFirst().orElse(null);
		int cargoCapacity = cargoCapacity(l);
		cargoScroll = clampScroll(cargoScroll, containers.size(), cargoCapacity);
		drawScrollCounter(g, cargoUpRect(l).x - 122, l.bottomTop + 9, cargoScroll, containers.size(), cargoCapacity);
		if (containers.size() > cargoCapacity) drawPageButtons(g, cargoFirstRect(l), cargoUpRect(l), cargoDownRect(l), cargoLastRect(l));
		if (containers.isEmpty()) {
			g.text(client.font, "NO COMPATIBLE CONTAINERS", split + 16, l.bottomTop + 48, MUTED);
		} else for (int visible = 0; visible < cargoCapacity && cargoScroll + visible < containers.size(); visible++) {
			ContainerInfo container = containers.get(cargoScroll + visible);
			Rect row = cargoRowRect(l, visible);
			boolean source = cargoDrone != null && cargoDrone.hasCargoSource() && cargoDrone.cargoSource().asLong() == container.pos;
			boolean target = cargoDrone != null && cargoDrone.hasCargoTarget() && cargoDrone.cargoTarget().asLong() == container.pos;
			g.fill(row.x, row.y, row.right(), row.bottom(), PANEL_ALT);
			g.outline(row.x, row.y, row.w, row.h, source ? CYAN : target ? AMBER : BORDER);
			g.text(client.font, container.label, row.x + 6, row.y + 5, TEXT, true);
			g.text(client.font, container.distance + "m  " + container.coordinate, row.x + 98, row.y + 5, MUTED);
			drawCargoButton(g, cargoSourceRect(l, visible), "OUT", source, CYAN, cargoDrone != null);
			drawCargoButton(g, cargoTargetRect(l, visible), "IN", target, AMBER, cargoDrone != null);
		}
		if (cargoDrone != null) {
			String state = cargoDrone.cargoStatusLabel() + " / LOAD " + cargoDrone.cargoItemCount();
			g.text(client.font, state, split + 9, l.bottomTop + 24, cargoDrone.cargoItemCount() > 0 ? AMBER : MUTED);
		}
	}

	private void drawCargoButton(GuiGraphicsExtractor g, Rect rect, String label, boolean active, int color, boolean enabled) {
		g.fill(rect.x, rect.y, rect.right(), rect.bottom(), active ? 0xFF173A3D : PANEL);
		g.outline(rect.x, rect.y, rect.w, rect.h, enabled ? color : BORDER);
		g.centeredText(client.font, label, rect.x + rect.w / 2, rect.y + 5, enabled ? (active ? color : TEXT) : MUTED);
	}

	private void drawInteractionStatus(GuiGraphicsExtractor g, Layout l,
		TacticalUiPolicy.CommandAvailability availability) {
		if (page != 0 && (client.level == null || client.level.getGameTime() >= transientNoticeUntil)) return;
		String message;
		int color;
		if (armedGuard) {
			message = "GUARD配置: 地図上の警戒中心をダブルクリック / " + TacticalUiPolicy.radiusLabel(24);
			color = RED;
		} else if (armedWork != FieldOperationType.NONE) {
			int radius = armedWork == FieldOperationType.ORE ? 32 : armedWork == FieldOperationType.EXCAVATE ? 8 : 20;
			String assignment = autoOperations ? "AUTO OPS" : "選択ユニット";
			message = assignment + " / " + WORK_LABELS[workIndex(armedWork)] + ": 地図上の作業中心をダブルクリック / "
				+ TacticalUiPolicy.radiusLabel(radius);
			color = GREEN;
		} else if (client.level != null && client.level.getGameTime() < transientNoticeUntil) {
			message = transientNotice;
			color = CYAN;
		} else if (!availability.hasSelection()) {
			message = "操作対象がありません。左のUNITまたはWINGを選択してください";
			color = AMBER;
		} else {
			message = availability.selected() + "機を選択中 / 地図をダブルクリック: 移動または追跡 / ホイール: ズーム";
			color = MUTED;
		}
		int width = Math.min(l.map.w - 24, client.font.width(message) + 18);
		int x = l.map.x + (l.map.w - width) / 2;
		int y = l.map.bottom() - 27;
		g.fill(x, y, x + width, y + 19, 0xEA111920);
		g.outline(x, y, width, 19, color);
		g.centeredText(client.font, fitText(message, width - 12), x + width / 2, y + 6, color);
	}

	@SuppressWarnings("unused")
	private void drawLegacyBottomPanel(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones) {
		g.fill(0, l.bottomTop, virtualWidth(), virtualHeight(), 0xFF0D171B);
		g.horizontalLine(0, virtualWidth(), l.bottomTop, BORDER);
		int split = (int) (virtualWidth() * 0.60);
		g.verticalLine(split, l.bottomTop, virtualHeight(), BORDER);
		g.text(client.font, "進行中の任務", 9, l.bottomTop + 9, TEXT, true);
		g.text(client.font, drones.size() + " ACTIVE / 0 QUEUED", split - 116, l.bottomTop + 9, CYAN);
		g.text(client.font, "周辺コンテナ / 32ブロック", split + 9, l.bottomTop + 9, TEXT, true);
		g.text(client.font, "0 FOUND", virtualWidth() - 56, l.bottomTop + 9, CYAN);
		g.horizontalLine(0, virtualWidth(), l.bottomTop + 28, BORDER);
		g.text(client.font, "UNIT", 9, l.bottomTop + 37, MUTED);
		g.text(client.font, "MISSION / PROGRESS", 90, l.bottomTop + 37, MUTED);
		g.text(client.font, "STATE", split - 70, l.bottomTop + 37, MUTED);
		int capacity = Math.max(1, (virtualHeight() - (l.bottomTop + 55)) / 25);
		missionScroll = clampScroll(missionScroll, drones.size(), capacity);
		drawScrollCounter(g, split - 205, l.bottomTop + 9, missionScroll, drones.size(), capacity);
		if (drones.size() > capacity) drawPageButtons(g, missionFirstRect(l), missionUpRect(l), missionDownRect(l), missionLastRect(l));
		for (int visible = 0; visible < capacity && missionScroll + visible < drones.size(); visible++) {
			DroneEntity drone = drones.get(missionScroll + visible);
			int y = l.bottomTop + 55 + visible * 25;
			g.text(client.font, shortId(drone.unitId()), 9, y, TEXT, true);
			g.text(client.font, statusLabel(drone), 90, y, TEXT);
			g.fill(90, y + 12, split - 120, y + 15, 0xFF304249);
			g.fill(90, y + 12, 90 + (split - 210) * drone.batteryPercent() / 100, y + 15, CYAN);
			g.text(client.font, drone.mode() == DroneMode.STANDBY ? "STANDBY" : "ACTIVE", split - 70, y, drone.mode() == DroneMode.STANDBY ? MUTED : GREEN);
		}
		int cardX = split + 9;
		g.fill(cardX, l.bottomTop + 38, cardX + 145, l.bottomTop + 88, PANEL_ALT);
		g.outline(cardX, l.bottomTop + 38, 145, 50, BORDER);
		g.text(client.font, "CHEST SCAN", cardX + 7, l.bottomTop + 47, TEXT, true);
		g.text(client.font, "No compatible containers", cardX + 7, l.bottomTop + 64, MUTED);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x() / dashboardScale();
		double mouseY = event.y() / dashboardScale();
		List<DroneEntity> drones = drones();
		List<DockInfo> docks = docks(drones);
		TacticalUiPolicy.CommandAvailability availability = availability(drones);
		Layout l = layout();
		if (handlePageClick(l, drones, docks, mouseX, mouseY, event.hasControlDown())) return;
		for (int i = 0; i < 2; i++) if (rosterFilterRect(l, i).contains(mouseX, mouseY)) {
			if (i == 0) roleFilter = (roleFilter + 2) % 7 - 1;
			else sortMode = (sortMode + 1) % 4;
			unitScroll = 0; return;
		}
		if (page == 0 && routeToggleRect(l.map).contains(mouseX, mouseY)) {
			armedRoute = !armedRoute;
			routeDraft.clear();
			armedWork = FieldOperationType.NONE;
			armedGuard = false;
			return;
		}
		if (page == 0 && armedRoute && routeStartRect(l.map).contains(mouseX, mouseY)) {
			issuePatrolRoute();
			return;
		}
		if (page == 0 && armedRoute && routeCancelRect(l.map).contains(mouseX, mouseY)) {
			armedRoute = false;
			routeDraft.clear();
			notifyAction("巡回ルート編集を取り消しました");
			return;
		}
		if (page == 0 && armedRoute && !doubleClick && l.map.contains(mouseX, mouseY) && mouseY > l.map.y + 28) {
			addPatrolPoint(l, mouseX, mouseY);
			return;
		}
		if (page == 0 && !armedRoute && doubleClick && l.map.contains(mouseX, mouseY)) {
			if (armedGuard) {
				issueSecurityPatrol(l, mouseX, mouseY);
				return;
			}
			if (armedWork != FieldOperationType.NONE) {
				issueFieldOperation(l, mouseX, mouseY);
				return;
			}
			LivingEntity target = mapEntityAt(l.map, mouseX, mouseY, mapEntities());
			if (target == null) issueWaypoint(l, mouseX, mouseY);
			else issueTracking(target);
			return;
		}
		if (unitFirstRect(l).contains(mouseX, mouseY)) { unitScroll = 0; return; }
		if (unitUpRect(l).contains(mouseX, mouseY)) { unitScroll -= unitCapacity(l); return; }
		if (unitDownRect(l).contains(mouseX, mouseY)) { unitScroll += unitCapacity(l); return; }
		if (unitLastRect(l).contains(mouseX, mouseY)) { unitScroll = Integer.MAX_VALUE; return; }
		if (dockFirstRect(l).contains(mouseX, mouseY)) { dockScroll = 0; return; }
		if (dockUpRect(l).contains(mouseX, mouseY)) { dockScroll -= dockCapacity(l); return; }
		if (dockDownRect(l).contains(mouseX, mouseY)) { dockScroll += dockCapacity(l); return; }
		if (dockLastRect(l).contains(mouseX, mouseY)) { dockScroll = Integer.MAX_VALUE; return; }
		if (missionFirstRect(l).contains(mouseX, mouseY)) { missionScroll = 0; return; }
		if (missionUpRect(l).contains(mouseX, mouseY)) { missionScroll -= missionCapacity(l); return; }
		if (missionDownRect(l).contains(mouseX, mouseY)) { missionScroll += missionCapacity(l); return; }
		if (missionLastRect(l).contains(mouseX, mouseY)) { missionScroll = Integer.MAX_VALUE; return; }
		if (cargoFirstRect(l).contains(mouseX, mouseY)) { cargoScroll = 0; return; }
		if (cargoUpRect(l).contains(mouseX, mouseY)) { cargoScroll -= cargoCapacity(l); return; }
		if (cargoDownRect(l).contains(mouseX, mouseY)) { cargoScroll += cargoCapacity(l); return; }
		if (cargoLastRect(l).contains(mouseX, mouseY)) { cargoScroll = Integer.MAX_VALUE; return; }
		for (int i = 0; i < 3; i++) {
			if (!tabRect(l, i).contains(mouseX, mouseY)) continue;
			selectionMode = SelectionMode.values()[i];
			unitScroll = 0;
			wingBoardScroll = 0;
			wingMapMode = selectionMode == SelectionMode.GROUP && page != 1;
			Set<Integer> normalized = TacticalUiPolicy.normalizeSelection(
				TacticalUiPolicy.SelectionScope.values()[selectionMode.ordinal()], selected,
				drones.stream().map(DroneEntity::getId).toList());
			selected.clear();
			selected.addAll(normalized);
			return;
		}
		if (page == 1
			&& beginWingCardInteraction(l, drones, mouseX, mouseY)) return;
		List<DroneEntity> displayed = visibleRoster(drones);
		List<OperationalWing> wings = operationalWings(drones);
		int rowCount = selectionMode == SelectionMode.GROUP && wingMapMode ? wings.size() : displayed.size();
		for (int visible = 0; visible < unitCapacity(l) && unitScroll + visible < rowCount; visible++) {
			if (!unitRect(l, visible).contains(mouseX, mouseY)) continue;
			if (selectionMode == SelectionMode.GROUP && wingMapMode) {
				OperationalWing wing = wings.get(unitScroll + visible);
				boolean additive = event.hasControlDown() || event.hasShiftDown()
					|| selectionToggleRect(unitRect(l, visible)).contains(mouseX, mouseY);
				boolean fullySelected = wing.members.stream().allMatch(member -> selected.contains(member.getId()));
				if (!additive) selected.clear();
				if (additive && fullySelected) wing.members.forEach(member -> selected.remove(member.getId()));
				else wing.members.forEach(member -> selected.add(member.getId()));
				return;
			}
			int clickedIndex = unitScroll + visible;
			DroneEntity drone = displayed.get(clickedIndex);
			if (selectionMode != SelectionMode.ALL) {
				boolean toggle = event.hasControlDown()
					|| selectionToggleRect(unitRect(l, visible)).contains(mouseX, mouseY);
				if (event.hasShiftDown() && selectionAnchorId >= 0) {
					int anchorIndex = indexOfEntity(displayed, selectionAnchorId);
					if (!event.hasControlDown()) selected.clear();
					if (anchorIndex < 0) selected.add(drone.getId());
					else for (int index = Math.min(anchorIndex, clickedIndex);
						index <= Math.max(anchorIndex, clickedIndex); index++) selected.add(displayed.get(index).getId());
				} else {
					if (!toggle) selected.clear();
					if (toggle && selected.contains(drone.getId())) selected.remove(drone.getId());
					else selected.add(drone.getId());
					selectionAnchorId = drone.getId();
				}
			}
			draggedEntity = drone.getId();
			dragSourceX = unitRect(l, visible).right() - 8;
			dragSourceY = unitRect(l, visible).y + 13;
			dragStartX = mouseX;
			dragStartY = mouseY;
			dragX = mouseX;
			dragY = mouseY;
			return;
		}
		for (int visible = 0; visible < dockCapacity(l) && dockScroll + visible < docks.size(); visible++) {
			if (!dockRect(l, visible).contains(mouseX, mouseY)) continue;
			DockInfo dock = docks.get(dockScroll + visible);
			notifyAction(dock.id + " / " + DockAllocationUiPolicy.dockStateLabel(dock.allocation)
				+ " / " + DockAllocationUiPolicy.queueLabel(dock.allocation)
				+ " / " + DockAllocationUiPolicy.holdingLabel(dock.allocation));
			return;
		}
		for (int i = 0; i < ACTIONS.length; i++) {
			if (commandRect(l, i).contains(mouseX, mouseY)) {
				if (availability.hasSelection()) execute(i);
				return;
			}
		}
		for (int i = 0; i < WORK_TYPES.length; i++) {
			if (!workRect(l, i).contains(mouseX, mouseY)) continue;
			if (!autoOperations && !availability.workEnabled(WORK_TYPES[i])) {
				notifyAction(availability.workBlockReason(WORK_TYPES[i]));
				return;
			}
			armedWork = armedWork == WORK_TYPES[i] ? FieldOperationType.NONE : WORK_TYPES[i];
			armedRoute = false;
			routeDraft.clear();
			page = 0;
			armedGuard = false;
			return;
		}
		if (autoOperationsRect(l).contains(mouseX, mouseY)) {
			autoOperations = !autoOperations;
			notifyAction(autoOperations
				? "AUTO OPS: 待機中の適格機から一時Task Forceを自動編成します"
				: "AUTO OPSを解除しました。選択中ユニットへ任務を割り当てます");
			return;
		}
		if (workRect(l, WORK_TYPES.length).contains(mouseX, mouseY)) {
			if (!availability.securityEnabled()) {
				notifyAction("GUARDにはSecurityユニットが必要です");
				return;
			}
			armedGuard = !armedGuard;
			armedRoute = false;
			routeDraft.clear();
			page = 0;
			armedWork = FieldOperationType.NONE;
			return;
		}
		if (modulePanelVisible(l)) {
			if (capacityUpgradeRect(l).contains(mouseX, mouseY) && selected.size() == 1) {
				DroneEntity unit = drones.stream().filter(d -> selected.contains(d.getId())).findFirst().orElse(null);
				if (unit != null && unit.role() == DroneRole.SECURITY && unit.isDocked()
					&& unit.capacityTier() < jp.morrowgear.drone.PayloadCapacity.MAX_TIER) {
					ClientPlayNetworking.send(new DroneCommandPayload(unit.getId(), "capacity_upgrade:" + unit.capacityTier()));
				}
				return;
			}
			boolean securitySelection = !selected.isEmpty() && drones.stream()
				.filter(drone -> selected.contains(drone.getId()))
				.allMatch(drone -> drone.role() == DroneRole.SECURITY);
			if (dualModulePanelVisible(l) || securitySelection) for (int i = 0; i < WEAPON_LOADOUTS.length; i++) {
				if (!weaponModuleRect(l, i).contains(mouseX, mouseY)) continue;
				List<DroneEntity> selection = drones.stream()
					.filter(drone -> selected.contains(drone.getId())).toList();
				if (selection.isEmpty() || selection.stream().anyMatch(drone -> drone.role() != DroneRole.SECURITY)) {
					notifyAction("武装指定にはSecurityユニットを選択してください");
					return;
				}
				for (int id : selected) ClientPlayNetworking.send(new DroneCommandPayload(id,
					"weapon_module:" + WEAPON_LOADOUTS[i].id()));
				notifyAction(WEAPON_LABELS[i] + " 武装変更を要求しました");
				return;
			}
			if (dualModulePanelVisible(l) || !securitySelection) for (int i = 0; i < MODULE_ROLES.length; i++) {
				if (!moduleRect(l, i).contains(mouseX, mouseY)) continue;
				DroneEntity active = drones.stream().filter(drone -> selected.contains(drone.getId())).findFirst().orElse(null);
				boolean allDocked = active != null && drones.stream().filter(drone -> selected.contains(drone.getId()))
					.allMatch(DroneEntity::isDocked);
				if (!allDocked) {
					notifyAction("モジュール換装には選択機すべてのDock着艦が必要です");
					return;
				}
				for (int id : selected) ClientPlayNetworking.send(new DroneCommandPayload(id, "module:" + MODULE_ROLES[i].id()));
					notifyAction(TacticalUiPolicy.moduleLabel(MODULE_ROLES[i]) + " モジュール換装を要求しました");
				return;
			}
		}
		List<ContainerInfo> containers = containers();
		for (int visible = 0; visible < cargoCapacity(l) && cargoScroll + visible < containers.size(); visible++) {
			ContainerInfo container = containers.get(cargoScroll + visible);
			String action = cargoSourceRect(l, visible).contains(mouseX, mouseY) ? "cargo_source:"
				: cargoTargetRect(l, visible).contains(mouseX, mouseY) ? "cargo_target:" : null;
			if (action == null) continue;
			for (DroneEntity drone : drones) {
				if (selected.contains(drone.getId()) && drone.role() == DroneRole.CARGO) {
					ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(), action + container.pos));
				}
			}
			notifyAction((action.startsWith("cargo_source") ? "搬出元" : "搬入先") + "を " + container.label + " に変更要求しました");
			return;
		}
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double deltaX, double deltaY) {
		if (draggedEntity < 0) return;
		dragX = event.x() / dashboardScale();
		dragY = event.y() / dashboardScale();
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		if (Math.hypot(event.x() / dashboardScale() - dragStartX, event.y() / dashboardScale() - dragStartY) < 7) {
			draggedEntity = -1; return;
		}
		if (draggedEntity >= 0) {
			Layout l = layout();
			double mouseX = event.x() / dashboardScale();
			double mouseY = event.y() / dashboardScale();
			if (page == 1) {
				if (Math.hypot(mouseX - dragStartX, mouseY - dragStartY) >= 5.0) {
					completeWingDrop(l, drones(), mouseX, mouseY);
				}
				draggedEntity = -1;
				return;
			}
			for (int i = 0; i < ACTIONS.length; i++) if (commandRect(l, i).contains(mouseX, mouseY)) execute(i);
			if (page == 0 && l.map.contains(mouseX, mouseY)) {
				LivingEntity target = mapEntityAt(l.map, mouseX, mouseY, mapEntities());
				if (target == null) issueWaypoint(l, mouseX, mouseY);
				else issueTracking(target);
			}
		}
		draggedEntity = -1;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double x = mouseX / dashboardScale();
		double y = mouseY / dashboardScale();
		Layout l = layout();
		int direction = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
		if (direction == 0) return false;
		if (page >= 2 && (page >= 5 || l.map.contains(x, y))) {
			pageScroll = Math.max(0, pageScroll + direction); return true;
		}
		if (page == 1 && l.map.contains(x, y)) {
			wingBoardScroll += direction;
		}
		else if (l.map.contains(x, y)) {
			mapZoom = Math.max(1.5, Math.min(14.0, mapZoom + (scrollY > 0 ? 0.75 : -0.75)));
		}
		else if (x < l.leftWidth && y >= l.header + 50 && y < l.dockHeaderY - 5) unitScroll += direction;
		else if (x < l.leftWidth && y >= l.dockHeaderY) dockScroll += direction;
		else if (y >= l.bottomTop && x < virtualWidth() * 0.60) missionScroll += direction;
		else if (y >= l.bottomTop) cargoScroll += direction;
		else return false;
		return true;
	}

	private void execute(int index) {
		if (selected.isEmpty()) return;
		if (ACTIONS[index].equals("decommission") && client.level != null) {
			long now = client.level.getGameTime();
			if (now >= confirmStoreUntil || !selected.equals(confirmStoreSelection)) {
				confirmStoreUntil = now + 60L;
				confirmStoreSelection = Set.copyOf(selected);
				notifyAction("格納すると機体はアイテム化されます。STOREをもう一度押してください");
				return;
			}
		}
		confirmStoreUntil = 0;
		confirmStoreSelection = Set.of();
		if (ACTIONS[index].equals("follow")) {
			issueFollowFormation();
			notifyAction(selected.size() + "機へFOLLOWを指示しました");
			return;
		}
		for (int id : selected) ClientPlayNetworking.send(new DroneCommandPayload(id, ACTIONS[index]));
		notifyAction(selected.size() + "機へ" + actionLabel(index, drones()) + "を指示しました");
	}

	private String actionLabel(int index, List<DroneEntity> drones) {
		return switch (ACTIONS[index]) {
			case "follow" -> "FOLLOW ME";
			case "return" -> "RETURN TO ME";
			case "dock" -> "TO DOCK";
			case "orbit" -> "ORBIT ME";
			default -> TacticalUiPolicy.actionLabel(ACTIONS[index]);
		};
	}

	private String selectionWingSummary(List<DroneEntity> drones, List<DroneEntity> selection) {
		List<String> groups = selection.stream().map(DroneEntity::groupId).filter(id -> !id.isBlank()).distinct().toList();
		long complete = groups.stream().filter(id -> drones.stream().filter(d -> d.groupId().equals(id))
			.allMatch(d -> selected.contains(d.getId()))).count();
		long loose = selection.stream().filter(d -> d.groupId().isBlank()).count();
		return "WING " + complete + " FULL " + (groups.size() - complete) + " PART / UNGROUPED " + loose;
	}

	private void issueFollowFormation() {
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		List<List<DroneEntity>> formations = selectedMissionFormations();
		String missionRoot = client.level.getGameTime() + "-follow-" + client.player.getId();
		for (int wing = 0; wing < formations.size(); wing++) {
			List<DroneEntity> formation = formations.get(wing).stream()
				.sorted(Comparator.comparingDouble((DroneEntity drone) -> drone.distanceToSqr(client.player))
					.thenComparing(DroneEntity::unitId)).toList();
			DroneEntity leader = formation.getFirst();
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			for (int index = 0; index < formation.size(); index++) {
				DroneEntity drone = formation.get(index);
				ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(),
					"follow:" + missionId + ":" + formation.size() + ":" + index + ":" + leader.getId()));
			}
		}
	}

	private void issueFieldOperation(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || client.level == null || (!autoOperations && selected.isEmpty())
			|| armedWork == FieldOperationType.NONE) return;
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int)Math.floor(client.player.getX() + (mouseX - centerX) / mapZoom);
		int worldZ = (int)Math.floor(client.player.getZ() + (mouseY - centerY) / mapZoom);
		int surfaceY = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
		int worldY = armedWork == FieldOperationType.ORE ? surfaceY - 16
			: surfaceY - 1;
		int radius = armedWork == FieldOperationType.ORE ? 32
			: armedWork == FieldOperationType.EXCAVATE ? 8 : 20;
		String orderId = Long.toString(client.level.getGameTime(), 36).toUpperCase()
			+ "-" + armedWork.id().toUpperCase() + "-" + worldX + "-" + worldZ;
		if (autoOperations) {
			ClientPlayNetworking.send(new FleetOperationPayload(armedWork.id(), worldX, worldZ, radius));
			notifyAction("AUTO OPS / " + armedWork.label() + " / Task Force編成を要求しました");
			armedWork = FieldOperationType.NONE;
			return;
		}
		for (DroneEntity drone : drones()) {
			if (!selected.contains(drone.getId()) || !MissionAssignmentPolicy.allows(drone.role(),
				MissionAssignmentPolicy.MissionKind.FIELD_OPERATION)) continue;
			ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(), "work:" + armedWork.id()
				+ ":" + worldX + ":" + worldY + ":" + worldZ + ":" + radius + ":" + orderId));
		}
		notifyAction(armedWork.label() + "を " + TacticalUiPolicy.radiusLabel(radius) + " で開始要求しました");
		armedWork = FieldOperationType.NONE;
	}

	private void issueSecurityPatrol(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int)Math.floor(client.player.getX() + (mouseX - centerX) / mapZoom);
		int worldZ = (int)Math.floor(client.player.getZ() + (mouseY - centerY) / mapZoom);
		int worldY = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
		int radius = 24;
		String orderId = Long.toString(client.level.getGameTime(), 36).toUpperCase()
			+ "-GUARD-" + worldX + "-" + worldZ;
		for (DroneEntity drone : drones()) {
			if (!selected.contains(drone.getId()) || !MissionAssignmentPolicy.allows(drone.role(),
				MissionAssignmentPolicy.MissionKind.SECURITY_PATROL)) continue;
			ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(), "guard:"
				+ worldX + ":" + worldY + ":" + worldZ + ":" + radius + ":" + orderId));
		}
		notifyAction("GUARDを " + TacticalUiPolicy.radiusLabel(radius) + " で開始要求しました");
		armedGuard = false;
	}

	private void issueWaypoint(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || selected.isEmpty()) return;
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int) Math.floor(client.player.getX() + (mouseX - centerX) / mapZoom);
		int worldZ = (int) Math.floor(client.player.getZ() + (mouseY - centerY) / mapZoom);
		String missionRoot = client.level.getGameTime() + "-" + client.player.getId() + "-" + worldX + "-" + worldZ;
		List<List<DroneEntity>> formations = selectedMissionFormations();
		int total = 0;
		for (int wing = 0; wing < formations.size(); wing++) {
			List<DroneEntity> formation = formations.get(wing);
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			for (int index = 0; index < formation.size(); index++) {
				DroneEntity drone = formation.get(index);
				ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(),
					"move:" + worldX + ":" + worldZ + ":" + missionId + ":" + formation.size() + ":" + index));
				total++;
			}
		}
		notifyAction(total + "機 / " + formations.size() + " Wingを地点 " + worldX + ", " + worldZ + " へ派遣しました");
	}

	private void issueTracking(LivingEntity target) {
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		String missionRoot = client.level.getGameTime() + "-track-" + target.getId();
		List<List<DroneEntity>> formations = selectedMissionFormations();
		int total = 0;
		for (int wing = 0; wing < formations.size(); wing++) {
			List<DroneEntity> formation = formations.get(wing);
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			for (int index = 0; index < formation.size(); index++) {
				DroneEntity drone = formation.get(index);
				ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(),
					"track:" + target.getId() + ":" + missionId + ":" + formation.size() + ":" + index));
				total++;
			}
		}
		notifyAction(total + "機 / " + formations.size() + " Wingで "
			+ target.getName().getString() + " を追跡します");
	}

	private void addPatrolPoint(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || routeDraft.size() >= PatrolRoutePolicy.MAX_POINTS) {
			notifyAction("巡回地点は最大" + PatrolRoutePolicy.MAX_POINTS + "点です");
			return;
		}
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int) Math.floor(client.player.getX() + (mouseX - centerX) / mapZoom);
		int worldZ = (int) Math.floor(client.player.getZ() + (mouseY - centerY) / mapZoom);
		BlockPos point = new BlockPos(worldX, 0, worldZ);
		if (!routeDraft.isEmpty() && routeDraft.getLast().distSqr(point) < 4.0) {
			notifyAction("直前の地点と近すぎます");
			return;
		}
		routeDraft.add(point);
		notifyAction("巡回地点 " + routeDraft.size() + " / " + worldX + ", " + worldZ);
	}

	private void issuePatrolRoute() {
		if (client.player == null || client.level == null || selected.isEmpty() || routeDraft.isEmpty()) {
			notifyAction("巡回地点を1点以上設定してください");
			return;
		}
		List<List<DroneEntity>> formations = selectedMissionFormations();
		String missionRoot = "R" + Long.toString(client.level.getGameTime(), 36).toUpperCase();
		String encoded = PatrolRoutePolicy.encode(routeDraft);
		int total = 0;
		for (int wing = 0; wing < formations.size(); wing++) {
			List<DroneEntity> formation = formations.get(wing);
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			Vec3 formationOrigin = PatrolRoutePolicy.formationOrigin(
				formation.stream().map(DroneEntity::position).toList());
			int startIndex = PatrolRoutePolicy.nearestIndex(routeDraft, formationOrigin);
			int originX = (int)Math.floor(formationOrigin.x);
			int originZ = (int)Math.floor(formationOrigin.z);
			for (int index = 0; index < formation.size(); index++) {
				DroneEntity drone = formation.get(index);
				ClientPlayNetworking.send(new DroneCommandPayload(drone.getId(), "patrol:" + missionId
					+ ":" + formation.size() + ":" + index + ":" + startIndex
					+ ":" + originX + ":" + originZ + ":" + encoded));
				total++;
			}
		}
		notifyAction(total + "機 / " + formations.size() + " Wingへ" + routeDraft.size() + "点の巡回ルートを割り当てました");
		armedRoute = false;
		routeDraft.clear();
	}

	private void drawPatrolRoutes(GuiGraphicsExtractor g, Rect map, List<DroneEntity> drones) {
		Set<String> drawn = new LinkedHashSet<>();
		for (DroneEntity drone : drones) {
			if (!selected.contains(drone.getId()) || !drone.hasPatrolRoute() || !drawn.add(drone.missionId())) continue;
			drawRoute(g, map, drone.patrolRoutePoints(), drone.patrolRouteIndex(), CYAN);
		}
		if (armedRoute) drawRoute(g, map, routeDraft, -1, GREEN);
	}

	private void drawRoute(GuiGraphicsExtractor g, Rect map, List<BlockPos> points, int activeIndex, int color) {
		if (points.isEmpty()) return;
		List<MapPoint> visible = points.stream().map(point -> mapPoint(map, point.getX() + 0.5, point.getZ() + 0.5)).toList();
		for (int index = 0; index < visible.size(); index++) {
			MapPoint point = visible.get(index);
			if (point == null) continue;
			MapPoint next = visible.get((index + 1) % visible.size());
			if (points.size() > 1 && next != null) drawLine(g, point.x, point.y, next.x, next.y, color);
			g.fill(point.x - 5, point.y - 5, point.x + 6, point.y + 6, 0xE6081014);
			g.outline(point.x - 5, point.y - 5, 11, 11, index == activeIndex ? AMBER : color);
			g.centeredText(client.font, Integer.toString(index + 1), point.x, point.y - 4, TEXT);
		}
	}

	private void drawRouteControls(GuiGraphicsExtractor g, Rect map, int mouseX, int mouseY) {
		Rect toggle = routeToggleRect(map);
		g.fill(toggle.x, toggle.y, toggle.right(), toggle.bottom(), armedRoute ? 0xFF174139 : PANEL_ALT);
		g.outline(toggle.x, toggle.y, toggle.w, toggle.h, armedRoute ? GREEN : CYAN);
		g.centeredText(client.font, armedRoute ? "ROUTE " + routeDraft.size() + "/" + PatrolRoutePolicy.MAX_POINTS : "ROUTE",
			toggle.x + toggle.w / 2, toggle.y + 6, armedRoute ? GREEN : TEXT);
		if (!armedRoute) return;
		Rect start = routeStartRect(map);
		Rect cancel = routeCancelRect(map);
		g.fill(start.x, start.y, start.right(), start.bottom(), start.contains(mouseX, mouseY) ? PANEL_RAISED : PANEL_ALT);
		g.outline(start.x, start.y, start.w, start.h, routeDraft.isEmpty() ? BORDER : GREEN);
		g.centeredText(client.font, "START", start.x + start.w / 2, start.y + 6, routeDraft.isEmpty() ? MUTED : TEXT);
		g.fill(cancel.x, cancel.y, cancel.right(), cancel.bottom(), cancel.contains(mouseX, mouseY) ? PANEL_RAISED : PANEL_ALT);
		g.outline(cancel.x, cancel.y, cancel.w, cancel.h, RED);
		g.centeredText(client.font, "CANCEL", cancel.x + cancel.w / 2, cancel.y + 6, TEXT);
	}

	private TacticalUiPolicy.CommandAvailability availability(List<DroneEntity> drones) {
		return TacticalUiPolicy.availability(drones.stream().filter(drone -> selected.contains(drone.getId()))
			.map(DroneEntity::role).toList());
	}

	private static int workIndex(FieldOperationType type) {
		for (int index = 0; index < WORK_TYPES.length; index++) if (WORK_TYPES[index] == type) return index;
		return 0;
	}

	private static String compatibleRoleSummary(TacticalUiPolicy.CommandAvailability availability) {
		return "FIELD " + availability.field() + " / CARGO " + availability.cargo()
			+ " / SEC " + availability.security();
	}

	private void notifyAction(String message) {
		transientNotice = message == null ? "" : message;
		transientNoticeUntil = client.level == null ? 0 : client.level.getGameTime() + 80L;
	}

	private List<DroneEntity> selectedMissionFormation() {
		return drones().stream().filter(drone -> selected.contains(drone.getId()))
			.sorted(Comparator.comparing(DroneEntity::groupId).thenComparing(DroneEntity::unitId)).toList();
	}

	private List<List<DroneEntity>> selectedMissionFormations() {
		return jp.morrowgear.drone.MissionWingPolicy.partitionByWing(
			selectedMissionFormation(), DroneEntity::groupId);
	}

	private static String wingMissionId(String root, int wing, int wingCount) {
		return wingCount <= 1 ? root : root + "-W" + (wing + 1);
	}

	private List<WingCard> wingCards(List<DroneEntity> drones) {
		Map<String, List<DroneEntity>> grouped = new LinkedHashMap<>();
		for (DroneEntity drone : drones) {
			if (drone.groupId().startsWith("WING-")) {
				grouped.computeIfAbsent(drone.groupId(), ignored -> new ArrayList<>()).add(drone);
			}
		}
		List<WingCard> result = new ArrayList<>();
		for (Map.Entry<String, List<DroneEntity>> entry : grouped.entrySet()) {
			List<DroneEntity> members = entry.getValue().stream()
				.sorted(Comparator.comparingInt(DroneEntity::missionIndex).thenComparing(DroneEntity::unitId)).toList();
			DroneEntity leader = members.stream()
				.filter(member -> member.unitId().equals(member.cohortLeaderId()))
				.findFirst().orElse(members.getFirst());
			int recovery = (int) members.stream().filter(member -> member.recoveryLevel() > 0).count();
			int battery = (int)Math.round(members.stream().mapToInt(DroneEntity::batteryPercent).average().orElse(0));
			String mission = leader.hasActiveFieldOperation() ? leader.fieldOperationType().label()
				: leader.mode() == DroneMode.STANDBY ? "STANDBY" : statusLabel(leader);
			result.add(new WingCard(entry.getKey(), members, leader, recovery, battery, mission));
		}
		result.sort(Comparator.comparing(WingCard::groupId));
		return List.copyOf(result);
	}

	private boolean beginWingCardInteraction(Layout layout, List<DroneEntity> drones, double mouseX, double mouseY) {
		if (newWingRect(layout.map).contains(mouseX, mouseY)) {
			List<DroneEntity> members = drones.stream().filter(d -> selected.contains(d.getId())).toList();
			if (members.isEmpty() || members.size() > WingMembershipPolicy.MAX_MEMBERS) {
				notifyAction("Wingの編成対象は1〜8機です");
				return true;
			}
			String suffix = java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
			members.forEach(d -> ClientPlayNetworking.send(new DroneCommandPayload(d.getId(), "wing_create:WING-" + suffix)));
			notifyAction("Wing編成を要求しました / " + members.size() + "機");
			return true;
		}
		List<WingCard> cards = wingCards(drones);
		int columns = wingCardColumns(layout.map);
		int capacity = wingCardRowsVisible(layout.map) * columns;
		int firstCard = wingBoardScroll * columns;
		for (int visible = 0; visible < capacity && firstCard + visible < cards.size(); visible++) {
			WingCard wing = cards.get(firstCard + visible);
			Rect card = wingCardRect(layout.map, visible);
			if (!card.contains(mouseX, mouseY)) continue;
			for (int slot = 0; slot < wing.members.size() && slot < WingMembershipPolicy.MAX_MEMBERS; slot++) {
				Rect slotRect = wingSlotRect(card, slot);
				if (!slotRect.contains(mouseX, mouseY)) continue;
				DroneEntity member = wing.members.get(slot);
				selected.clear();
				selected.add(member.getId());
				draggedEntity = member.getId();
				dragSourceX = slotRect.x + slotRect.w / 2;
				dragSourceY = slotRect.y + slotRect.h / 2;
				dragStartX = mouseX;
				dragStartY = mouseY;
				dragX = mouseX;
				dragY = mouseY;
				return true;
			}
			selected.clear();
			wing.members.forEach(member -> selected.add(member.getId()));
			return true;
		}
		return layout.map.contains(mouseX, mouseY);
	}

	private void completeWingDrop(Layout layout, List<DroneEntity> drones, double mouseX, double mouseY) {
		DroneEntity dragged = drones.stream().filter(drone -> drone.getId() == draggedEntity).findFirst().orElse(null);
		if (dragged == null) return;
		if (newWingRect(layout.map).contains(mouseX, mouseY)) {
			String suffix = client.level == null ? "NEW" : Long.toString(client.level.getGameTime(), 36).toUpperCase();
			ClientPlayNetworking.send(new DroneCommandPayload(draggedEntity, "wing_create:WING-" + suffix));
			return;
		}
		List<WingCard> cards = wingCards(drones);
		int columns = wingCardColumns(layout.map);
		int capacity = wingCardRowsVisible(layout.map) * columns;
		int firstCard = wingBoardScroll * columns;
		for (int visible = 0; visible < capacity && firstCard + visible < cards.size(); visible++) {
			int cardIndex = firstCard + visible;
			if (!wingCardRect(layout.map, visible).contains(mouseX, mouseY)) continue;
			WingCard target = cards.get(cardIndex);
			ClientPlayNetworking.send(new DroneCommandPayload(draggedEntity, "wing_join:" + target.groupId));
			return;
		}
		ClientPlayNetworking.send(new DroneCommandPayload(draggedEntity, "wing_leave"));
	}

	private List<OperationalWing> operationalWings(List<DroneEntity> drones) {
		return wingCards(drones).stream()
			.map(wing -> new OperationalWing("manual:" + wing.groupId, wing.groupId, wing.members,
				wing.leader, wing.recoveryCount, wing.averageBattery))
			.toList();
	}

	private LivingEntity mapEntityAt(Rect map, double mouseX, double mouseY, List<LivingEntity> entities) {
		LivingEntity closest = null;
		double closestDistance = 9.0;
		for (LivingEntity entity : entities) {
			MapPoint point = mapPoint(map, entity.getX(), entity.getZ());
			if (point == null) continue;
			double distance = Math.hypot(mouseX - point.x, mouseY - point.y);
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = entity;
			}
		}
		return closest;
	}

	private List<DroneEntity> drones() {
		if (client.level == null || client.player == null) return List.of();
		List<DroneEntity> result = new ArrayList<>(client.level.getEntitiesOfClass(
			DroneEntity.class, new AABB(client.player.blockPosition()).inflate(256),
			drone -> drone.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())));
		result.sort(Comparator
			.comparingInt((DroneEntity drone) -> TacticalUiPolicy.rosterPriority(
				drone.combatActive(), drone.emergencyInterceptActive(), drone.recoveryLevel(),
				hasActiveMission(drone), drone.isDocked(), drone.batteryPercent()))
			.thenComparing(drone -> drone.groupId().startsWith("WING-") ? drone.groupId() : "ZZZ-" + drone.groupId())
			.thenComparingInt(drone -> drone.role().ordinal())
			.thenComparing(DroneEntity::unitId));
		return result;
	}

	private List<DroneEntity> visibleRoster(List<DroneEntity> drones) {
		Comparator<DroneEntity> order = switch (sortMode) {
			case 1 -> Comparator.comparingInt(d -> d.role().ordinal());
			case 2 -> Comparator.comparingInt(DroneEntity::batteryPercent);
			case 3 -> Comparator.comparing(d -> d.operationalState().name());
			default -> Comparator.comparing(DroneEntity::unitId);
		};
		// A Wing row always represents its complete membership, even with a role filter.
		return drones.stream().filter(d -> selectionMode == SelectionMode.GROUP && wingMapMode
			|| roleFilter < 0 || d.role().ordinal() == roleFilter).sorted(order.thenComparing(DroneEntity::unitId)).toList();
	}

	private Rect pageTab(int index) { return new Rect(10 + index * 108, 43, 106, 27); }
	private Rect rosterFilterRect(Layout l, int index) { return new Rect(7 + index * (l.leftWidth - 14) / 2,
		l.header + 54, (l.leftWidth - 20) / 2, 21); }
	private Rect operationRow(Layout l, int index) { return new Rect(l.map.x + 12, l.map.y + 48 + index * 50, l.map.w - 24, 46); }
	private int operationCapacity(Layout l) { return Math.max(1, (l.map.h - 68) / 50); }

	private void drawOperationsPage(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, List<DockInfo> docks) {
		Rect area = l.map;
		g.fill(area.x, area.y, area.right(), area.bottom(), BG);
		HmiArt.icon(g, PAGE_ICONS[page], area.x + 12, area.y + 12, 19);
		g.text(client.font, PAGES[page], area.x + 40, area.y + 18, TEXT, true);
		int total = page == 3 ? docks.size() : page == 4 && client.level != null
			? PowerLostBeaconStore.snapshot(client.level.getGameTime()).size() : drones.size();
		pageScroll = clampScroll(pageScroll, total, operationCapacity(l));
		if (total == 0) g.text(client.font, page == 4 ? "救難信号なし" : "現在の情報なし", area.x + 18, area.y + 60, MUTED);
		for (int visible = 0; visible < operationCapacity(l) && pageScroll + visible < total; visible++) {
			Rect row = operationRow(l, visible);
			g.fill(row.x, row.y, row.right(), row.bottom(), PANEL);
			g.horizontalLine(row.x, row.right(), row.bottom(), BORDER);
			int index = pageScroll + visible;
			if (page == 3) {
				DockInfo dock = docks.get(index);
				HmiArt.item(g, "dock_item", row.x + 4, row.y + 4, 38);
				g.text(client.font, fitText(dock.id, row.w - 54), row.x + 48, row.y + 6, TEXT);
				g.text(client.font, fitText(DockAllocationUiPolicy.dockStateLabel(dock.allocation)
					+ " / 電力 " + dock.status.powerPercent() + "%", row.w - 54), row.x + 48, row.y + 19,
					allocationColor(dock.allocation.state()));
				String allocation = DockAllocationUiPolicy.queueLabel(dock.allocation) + "  "
					+ DockAllocationUiPolicy.holdingLabel(dock.allocation) + "  "
					+ (dock.allocation.aircraftId().isBlank() ? "AIRCRAFT --" : shortId(dock.allocation.aircraftId()));
				g.text(client.font, fitText(allocation, row.w - 54), row.x + 48, row.y + 32, MUTED);
			} else if (page == 4) {
				var beacon = PowerLostBeaconStore.snapshot(client.level.getGameTime()).get(index);
				HmiArt.icon(g, "radio-tower", row.x + 8, row.y + 10, 24);
				g.text(client.font, fitText(beacon.unitId(), row.w - 54), row.x + 44, row.y + 6, AMBER);
				String destination = Math.round(beacon.position().x) + ", " + Math.round(beacon.position().y) + ", " + Math.round(beacon.position().z);
				g.text(client.font, fitText(destination + " / " + Math.round(beacon.position().distanceTo(client.player.position())) + "m", row.w - 54), row.x + 44, row.y + 20, TEXT);
				g.text(client.font, "最終確認 " + Math.max(0, (client.level.getGameTime() - beacon.detectedTick()) / 20) + "秒前", row.x + 44, row.y + 33, MUTED);
			} else {
				DroneEntity drone = drones.get(index);
				if (selected.contains(drone.getId())) g.fill(row.x, row.y, row.x + 2, row.bottom(), CYAN);
				HmiArt.role(g, drone.role(), row.x + 4, row.y + 5, 36);
				g.text(client.font, fitText(shortId(drone.unitId()) + " / " + drone.groupId(), row.w - 54), row.x + 46, row.y + 6, TEXT);
				g.text(client.font, fitText(statusLabel(drone), row.w - 54), row.x + 46, row.y + 19, CYAN);
				g.text(client.font, "FLT " + drone.batteryPercent() + "%  WPN " + drone.weaponPowerPercent() + "%  CARGO " + drone.cargoItemCount(), row.x + 46, row.y + 32, MUTED);
			}
		}
		g.text(client.font, (total == 0 ? 0 : pageScroll + 1) + " / " + total, area.x + 14, area.bottom() - 14, MUTED);
	}

	private void drawUtilityPage(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones) {
		g.text(client.font, PAGES[page], 24, l.header + 18, TEXT, true);
		if (page == 6) {
			int columns = Math.max(3, (virtualWidth() - 48) / 180), rows = Math.max(1, (virtualHeight() - l.header - 100) / 115);
			pageScroll = clampScroll(pageScroll, (ITEM_IDS.length + columns - 1) / columns, rows);
			for (int i = 0; i < rows * columns && pageScroll * columns + i < ITEM_IDS.length; i++) {
				String id = ITEM_IDS[pageScroll * columns + i];
				int x = 24 + i % columns * ((virtualWidth() - 48) / columns), y = l.header + 44 + i / columns * 115;
				int w = (virtualWidth() - 48) / columns - 8;
				g.fill(x, y, x + w, y + 105, PANEL);
				HmiArt.item(g, id, x + (w - 72) / 2, y + 2, 72);
				String label = Component.translatable("item.morrowgear_drone." + id).getString();
				g.text(client.font, fitText(label, w - 12), x + 6, y + 76, TEXT);
				g.text(client.font, fitText(id, w - 12), x + 6, y + 91, MUTED);
			}
			return;
		}
		if (page == 7) {
			String[] labels = {"バイザー表示", "近接マーカー距離", "通信履歴・字幕"};
			for (int i = 0; i < labels.length; i++) {
				Rect row = settingRect(l, i);
				g.horizontalLine(row.x, row.right(), row.bottom(), BORDER);
				g.text(client.font, labels[i], row.x, row.y + 16, TEXT);
				String value = i == 2 ? "開く" : i == 0 ? visorConfig.enabled ? "ON" : "OFF" : visorConfig.proximityRange == 0 ? "マーカー非表示" : visorConfig.proximityRange + " m";
				g.text(client.font, value, row.right() - 135, row.y + 16, CYAN);
			}
			return;
		}
		HmiArt.item(g, "tactical_visor", 26, l.header + 50, 128);
		g.text(client.font, "LIVE TELEMETRY / " + drones.size() + " UNITS", 174, l.header + 60, CYAN);
		int y = l.header + 90;
		for (DroneEntity drone : drones.stream().filter(d -> selected.isEmpty() || selected.contains(d.getId())).limit(6).toList()) {
			HmiArt.role(g, drone.role(), 174, y, 28);
			g.text(client.font, shortId(drone.unitId()) + " / " + drone.operationalState().name(), 210, y + 2, TEXT);
			g.text(client.font, fitText(drone.combatActive() ? drone.combatStatusLabel() : drone.dataLinkStatus(), virtualWidth() - 250), 210, y + 17, MUTED);
			y += 40;
		}
	}

	private Rect settingRect(Layout l, int index) { return new Rect(26, l.header + 50 + index * 58, Math.min(640, virtualWidth() - 52), 50); }

	private boolean handlePageClick(Layout l, List<DroneEntity> drones, List<DockInfo> docks, double x, double y, boolean additive) {
		for (int i = 0; i < PAGES.length; i++) if (pageTab(i).contains(x, y)) {
			page = i; pageScroll = 0; draggedEntity = -1;
			if (i == 1) { selectionMode = SelectionMode.GROUP; wingMapMode = false; }
			else wingMapMode = selectionMode == SelectionMode.GROUP;
			return true;
		}
		if (page >= 5) {
			if (page == 7) for (int i = 0; i < 3; i++) if (settingRect(l, i).contains(x, y)) {
				if (i == 0) { visorConfig.enabled = !visorConfig.enabled; visorConfig.save(); }
				else if (i == 1) visorConfig.cycleRange();
				else client.setScreenAndShow(new OperationHistoryScreen(client.gui.screen()));
			}
			return true;
		}
		if (page < 2 || !l.map.contains(x, y)) return false;
		for (int i = 0; i < operationCapacity(l); i++) if (operationRow(l, i).contains(x, y)) {
			int index = pageScroll + i;
			if (page == 2 && index < drones.size()) {
				selectionMode = SelectionMode.UNIT;
				if (!additive) selected.clear();
				selected.add(drones.get(index).getId());
			} else if (page == 3 && index < docks.size()) {
				DockInfo dock = docks.get(index);
				notifyAction(dock.id + " / " + DockAllocationUiPolicy.dockStateLabel(dock.allocation)
					+ " / " + DockAllocationUiPolicy.queueLabel(dock.allocation)
					+ " / " + DockAllocationUiPolicy.holdingLabel(dock.allocation));
			} else if (page == 4) {
				selected.clear(); selectionMode = SelectionMode.UNIT;
				drones.stream().filter(d -> d.role() == DroneRole.SALVAGE).forEach(d -> selected.add(d.getId()));
				notifyAction(selected.isEmpty() ? "回収機なし" : "回収機 " + selected.size() + "機 / 自動回収状況");
			}
			return true;
		}
		return true;
	}

	private List<LivingEntity> mapEntities() {
		if (client.level == null || client.player == null) return List.of();
		long gameTime = client.level.getGameTime();
		if (entityCacheLevel == client.level && gameTime < entityRefreshAt) return cachedMapEntities;
		entityCacheLevel = client.level;
		entityRefreshAt = gameTime + 5L;
		cachedMapEntities = client.level.getEntitiesOfClass(LivingEntity.class,
			new AABB(client.player.blockPosition()).inflate(128, 64, 128),
			entity -> entity.isAlive() && entity != client.player && !(entity instanceof DroneEntity)
				&& !(entity instanceof ArmorStand));
		return cachedMapEntities;
	}

	private List<DockInfo> docks(List<DroneEntity> drones) {
		if (client.level == null || client.player == null) return List.of();
		long gameTime = client.level.getGameTime();
		if (dockCacheLevel == client.level && gameTime < dockRefreshAt) return cachedDocks;
		dockCacheLevel = client.level;
		dockRefreshAt = gameTime + 20L;
		List<DockInfo> result = new ArrayList<>();
		int cx = client.player.chunkPosition().x();
		int cz = client.player.chunkPosition().z();
		for (int x = cx - 4; x <= cx + 4; x++) {
			for (int z = cz - 4; z <= cz + 4; z++) {
				if (!client.level.hasChunk(x, z)) continue;
				for (BlockEntity blockEntity : client.level.getChunk(x, z).getBlockEntities().values()) {
					if (!(blockEntity instanceof DockBlockEntity dock)
						|| !dock.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())) continue;
					DroneEntity occupant = drones.stream().filter(drone -> drone.hasDock()
						&& drone.dockPos().equals(dock.getBlockPos())).findFirst().orElse(null);
					DockOperationalPolicy.Status status = DockOperationalPolicy.status(occupant != null,
						occupant == null ? 0 : occupant.batteryPercent(),
						occupant == null ? "" : occupant.dataLinkStatus(), dock.storedPower(), dock.powerCapacity(),
						dock.hasPowerSupply(), dock.hasRepairMaterial());
					boolean servicing = occupant != null && status.severity() != DockOperationalPolicy.Severity.READY;
					int queueCount = (int)drones.stream().filter(DroneEntity::dockHolding).count();
					DockView allocation = DockAllocationClientStore.dock(dock.getBlockPos().asLong()).orElseGet(() ->
						DockAllocationUiPolicy.synchronizedDock(dock.dockId(), occupant != null,
							occupant != null && occupant.isDocked(), servicing,
							occupant == null ? "" : occupant.unitId(), queueCount, queueCount));
					result.add(new DockInfo(dock.dockId(), dock.getBlockPos().asLong(), occupant, status, allocation));
				}
			}
		}
		result.sort(Comparator.comparing(DockInfo::id));
		cachedDocks = List.copyOf(result);
		return cachedDocks;
	}

	private List<ContainerInfo> containers() {
		if (client.level == null || client.player == null) return List.of();
		long gameTime = client.level.getGameTime();
		if (containerCacheLevel == client.level && gameTime < containerRefreshAt) return cachedContainers;
		containerCacheLevel = client.level;
		containerRefreshAt = gameTime + 20L;
		List<ContainerInfo> result = new ArrayList<>();
		BlockPos playerPos = client.player.blockPosition();
		int cx = client.player.chunkPosition().x();
		int cz = client.player.chunkPosition().z();
		for (int x = cx - 2; x <= cx + 2; x++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				if (!client.level.hasChunk(x, z)) continue;
				for (BlockEntity blockEntity : client.level.getChunk(x, z).getBlockEntities().values()) {
					BlockPos pos = blockEntity.getBlockPos();
					if (!(blockEntity instanceof Container) || pos.distSqr(playerPos) > 32 * 32) continue;
					String blockName = blockEntity.getBlockState().getBlock().getName().getString().toUpperCase();
					String label = blockName.length() > 12 ? blockName.substring(0, 12) : blockName;
					int distance = (int) Math.round(Math.sqrt(pos.distSqr(playerPos)));
					result.add(new ContainerInfo(label, pos.asLong(), distance,
						pos.getX() + "," + pos.getY() + "," + pos.getZ()));
				}
			}
		}
		result.sort(Comparator.comparingInt(ContainerInfo::distance).thenComparingLong(ContainerInfo::pos));
		cachedContainers = List.copyOf(result);
		return cachedContainers;
	}

	private Layout layout() {
		TacticalLayoutPolicy.Metrics metrics = metrics();
		Rect right = new Rect(metrics.width() - metrics.rightWidth(), metrics.header(),
			metrics.rightWidth(), metrics.mapHeight());
		Rect map = new Rect(metrics.leftWidth(), metrics.header(), metrics.mapWidth(), metrics.mapHeight());
		return new Layout(metrics.header(), metrics.bottomTop(), metrics.leftWidth(), metrics.dockHeaderY(), map, right);
	}

	private TacticalLayoutPolicy.Metrics metrics() { return TacticalLayoutPolicy.calculateForGui(width, height, client.getWindow().getGuiScale()); }
	private float dashboardScale() { return metrics().scale(); }
	private int virtualWidth() { return metrics().width(); }
	private int virtualHeight() { return metrics().height(); }
	private int unitCapacity(Layout l) { return Math.max(1, (l.dockHeaderY - (l.header + 81) - 20) / 34); }
	private int dockCapacity(Layout l) { return Math.max(1, (l.bottomTop - (l.dockHeaderY + 17) - 8) / 26); }
	private int missionCapacity(Layout l) { return Math.max(1, (virtualHeight() - (l.bottomTop + 55)) / 25); }
	private Rect tabRect(Layout l, int index) { int w = l.leftWidth / 3; return new Rect(index * w, l.header + 27, index == 2 ? l.leftWidth - index * w : w, 23); }
	private Rect unitRect(Layout l, int visible) { return new Rect(7, l.header + 81 + visible * 34, l.leftWidth - 14, 30); }
	private Rect selectionToggleRect(Rect row) { return new Rect(row.x + 6, row.y + 10, 8, 8); }
	private void drawSelectionToggle(GuiGraphicsExtractor g, Rect row, boolean active, int color) {
		Rect toggle = selectionToggleRect(row);
		g.outline(toggle.x, toggle.y, toggle.w, toggle.h, active ? CYAN : color);
		if (active) g.fill(toggle.x + 2, toggle.y + 2, toggle.right() - 2, toggle.bottom() - 2, CYAN);
	}
	private Rect dockRect(Layout l, int visible) { return new Rect(7, l.dockHeaderY + 17 + visible * 26, l.leftWidth - 14, 22); }
	private Rect unitFirstRect(Layout l) { return new Rect(l.leftWidth - 83, l.dockHeaderY - 16, 16, 12); }
	private Rect unitUpRect(Layout l) { return new Rect(l.leftWidth - 63, l.dockHeaderY - 16, 16, 12); }
	private Rect unitDownRect(Layout l) { return new Rect(l.leftWidth - 43, l.dockHeaderY - 16, 16, 12); }
	private Rect unitLastRect(Layout l) { return new Rect(l.leftWidth - 23, l.dockHeaderY - 16, 16, 12); }
	private Rect dockFirstRect(Layout l) { return new Rect(l.leftWidth - 83, l.bottomTop - 17, 16, 12); }
	private Rect dockUpRect(Layout l) { return new Rect(l.leftWidth - 63, l.bottomTop - 17, 16, 12); }
	private Rect dockDownRect(Layout l) { return new Rect(l.leftWidth - 43, l.bottomTop - 17, 16, 12); }
	private Rect dockLastRect(Layout l) { return new Rect(l.leftWidth - 23, l.bottomTop - 17, 16, 12); }
	private Rect missionFirstRect(Layout l) { int split = (int) (virtualWidth() * 0.60); return new Rect(split - 83, l.bottomTop + 5, 16, 12); }
	private Rect missionUpRect(Layout l) { int split = (int) (virtualWidth() * 0.60); return new Rect(split - 63, l.bottomTop + 5, 16, 12); }
	private Rect missionDownRect(Layout l) { int split = (int) (virtualWidth() * 0.60); return new Rect(split - 43, l.bottomTop + 5, 16, 12); }
	private Rect missionLastRect(Layout l) { int split = (int) (virtualWidth() * 0.60); return new Rect(split - 23, l.bottomTop + 5, 16, 12); }
	private int cargoCapacity(Layout l) { return Math.max(1, (virtualHeight() - (l.bottomTop + 38)) / 27); }
	private Rect cargoRowRect(Layout l, int visible) {
		int split = (int) (virtualWidth() * 0.60);
		return new Rect(split + 9, l.bottomTop + 38 + visible * 27, virtualWidth() - split - 18, 23);
	}
	private Rect cargoSourceRect(Layout l, int visible) { Rect row = cargoRowRect(l, visible); return new Rect(row.right() - 76, row.y + 2, 34, 19); }
	private Rect cargoTargetRect(Layout l, int visible) { Rect row = cargoRowRect(l, visible); return new Rect(row.right() - 38, row.y + 2, 34, 19); }
	private Rect cargoFirstRect(Layout l) { return new Rect(virtualWidth() - 83, l.bottomTop + 5, 16, 12); }
	private Rect cargoUpRect(Layout l) { return new Rect(virtualWidth() - 63, l.bottomTop + 5, 16, 12); }
	private Rect cargoDownRect(Layout l) { return new Rect(virtualWidth() - 43, l.bottomTop + 5, 16, 12); }
	private Rect cargoLastRect(Layout l) { return new Rect(virtualWidth() - 23, l.bottomTop + 5, 16, 12); }
	private Rect wingModeToggleRect(Rect board) {
		return new Rect(wingMapMode ? board.right() - 235 : board.x + 140, board.y + 5, 105, 22);
	}
	private Rect newWingRect(Rect board) { return new Rect(board.right() - 235, board.y + 5, 105, 22); }
	private int wingCardColumns(Rect board) { return board.w >= 470 ? 2 : 1; }
	private int wingCardRowsVisible(Rect board) { return Math.max(1, (board.h - 48) / 154); }
	private Rect wingCardRect(Rect board, int visible) {
		int columns = wingCardColumns(board);
		int gap = 10;
		int cardWidth = (board.w - 20 - gap * (columns - 1)) / columns;
		int column = visible % columns;
		int row = visible / columns;
		return new Rect(board.x + 10 + column * (cardWidth + gap), board.y + 37 + row * 154, cardWidth, 144);
	}
	private Rect wingSlotRect(Rect card, int slot) {
		int gap = 5;
		int slotWidth = (card.w - 16 - gap) / 2;
		int column = slot % 2;
		int row = slot / 2;
		return new Rect(card.x + 8 + column * (slotWidth + gap), card.y + 49 + row * 22, slotWidth, 18);
	}

	private Rect commandRect(Layout l, int index) {
		int gap = 6;
		int columns = 3;
		int available = l.right.w - 18 - gap * (columns - 1);
		int w = available / columns;
		int column = index % columns;
		int x = l.right.x + 9 + column * (w + gap);
		int y = l.right.y + 151 + (index / columns) * 27;
		return new Rect(x, y, column == columns - 1 ? l.right.right() - 9 - x : w, 23);
	}
	private Rect routeToggleRect(Rect map) { return new Rect(map.x + 10, map.y + 34, 76, 22); }
	private Rect routeStartRect(Rect map) { return new Rect(map.x + 92, map.y + 34, 62, 22); }
	private Rect routeCancelRect(Rect map) { return new Rect(map.x + 160, map.y + 34, 68, 22); }

	private Rect workRect(Layout l, int index) {
		int gap = 6;
		int columns = WORK_TYPES.length + 1;
		int available = l.right.w - 18 - gap * (columns - 1);
		int w = available / columns;
		int x = l.right.x + 9 + index * (w + gap);
		return new Rect(x, l.right.y + 220, index == columns - 1 ? l.right.right() - 9 - x : w, 23);
	}
	private Rect autoOperationsRect(Layout l) {
		return new Rect(l.right.right() - 94, l.right.y + 202, 85, 16);
	}

	private boolean modulePanelVisible(Layout l) { return page == 3 || l.right.h >= 370; }
	private boolean dualModulePanelVisible(Layout l) { return page == 3 || l.right.h >= 420; }
	private Rect moduleRect(Layout l, int index) {
		int gap = 3;
		int available = l.right.w - 18 - gap * (MODULE_ROLES.length - 1);
		int w = available / MODULE_ROLES.length;
		int x = l.right.x + 9 + index * (w + gap);
		int y = l.right.bottom() - (dualModulePanelVisible(l) ? 64 : 29);
		return new Rect(x, y, index == MODULE_ROLES.length - 1 ? l.right.right() - 9 - x : w, 22);
	}

	private Rect capacityUpgradeRect(Layout l) {
		return new Rect(l.right.right() - 57, l.right.bottom() - 39, 48, 13);
	}

	private Rect weaponModuleRect(Layout l, int index) {
		int gap = 4;
		int available = l.right.w - 18 - gap * (WEAPON_LOADOUTS.length - 1);
		int w = available / WEAPON_LOADOUTS.length;
		int x = l.right.x + 9 + index * (w + gap);
		int y = l.right.bottom() - (dualModulePanelVisible(l) ? 23 : 29);
		return new Rect(x, y, index == WEAPON_LOADOUTS.length - 1 ? l.right.right() - 9 - x : w, 20);
	}

	private static int moduleColor(DroneRole role) {
		return switch (role) {
			case FIELD -> MUTED;
			case SCOUT -> CYAN;
			case CARGO -> AMBER;
			case ENGINEER -> GREEN;
			case SECURITY -> RED;
			case SALVAGE -> AMBER;
		};
	}

	private void drawAssignment(GuiGraphicsExtractor g, int x, int y, int w, String label, String state) {
		g.fill(x, y, x + w, y + 22, 0xFF0D171B);
		g.outline(x, y, w, 22, BORDER);
		g.text(client.font, fitText(label, Math.max(40, w - client.font.width(state) - 18)), x + 6, y + 7, TEXT);
		g.text(client.font, state, x + w - client.font.width(state) - 6, y + 7, MUTED);
	}

	private boolean hasActiveMission(DroneEntity drone) {
		return drone.mode() != DroneMode.STANDBY || drone.hasActiveFieldOperation()
			|| drone.hasSecurityPatrol() || drone.role() == DroneRole.ENGINEER
				&& drone.engineerState() != jp.morrowgear.drone.EngineerState.IDLE;
	}

	private String fitText(String text, int maxWidth) {
		return HmiArt.fitText(text, maxWidth, client.font::width);
	}

	private void drawScrollCounter(GuiGraphicsExtractor g, int x, int y, int offset, int total, int capacity) {
		if (total <= capacity) return;
		String text = (offset + 1) + "-" + Math.min(total, offset + capacity) + "/" + total;
		g.text(client.font, text, x, y, MUTED);
	}

	private void drawPageButtons(GuiGraphicsExtractor g, Rect first, Rect previous, Rect next, Rect last) {
		drawPageButton(g, first, "|<");
		drawPageButton(g, previous, "<");
		drawPageButton(g, next, ">");
		drawPageButton(g, last, ">|");
	}

	private void drawPageButton(GuiGraphicsExtractor g, Rect rect, String label) {
		g.fill(rect.x, rect.y, rect.right(), rect.bottom(), PANEL_ALT);
		g.outline(rect.x, rect.y, rect.w, rect.h, BORDER);
		g.centeredText(client.font, label, rect.x + rect.w / 2, rect.y + 2, TEXT);
	}

	private static int indexOfEntity(List<DroneEntity> drones, int entityId) {
		for (int index = 0; index < drones.size(); index++) if (drones.get(index).getId() == entityId) return index;
		return -1;
	}

	private static int clampScroll(int value, int total, int capacity) { return TacticalLayoutPolicy.clampScroll(value, total, capacity); }
	private static String dockLabel(DroneEntity drone) { return DockBlockEntity.idFor(drone.dockPos()); }
	private static AircraftView aircraftAllocation(DroneEntity drone) {
		return DockAllocationClientStore.aircraft(drone.getId()).orElseGet(() ->
			DockAllocationUiPolicy.synchronizedAircraft(drone.hasDock(), drone.isDocked(), drone.dockHolding(),
				drone.hasDock() ? dockLabel(drone) : "", drone.dockQueuePosition(),
				drone.dockHolding() ? DockAllocationUiPolicy.UNKNOWN_COUNT : 0));
	}
	private static String aircraftDockLabel(DroneEntity drone) {
		return DockAllocationUiPolicy.aircraftLabel(aircraftAllocation(drone));
	}
	private static String allocationSummary(List<DroneEntity> drones) {
		long holding = drones.stream().map(TacticalDashboard::aircraftAllocation)
			.filter(view -> view.state() == DockAllocationUiPolicy.AircraftState.HOLDING).count();
		long reserved = drones.stream().map(TacticalDashboard::aircraftAllocation)
			.filter(view -> view.state() == DockAllocationUiPolicy.AircraftState.RESERVED).count();
		long assigned = drones.stream().map(TacticalDashboard::aircraftAllocation)
			.filter(view -> view.state() == DockAllocationUiPolicy.AircraftState.ASSIGNED).count();
		return "A " + assigned + " / R " + reserved + " / H " + holding;
	}
	private static int allocationColor(DockState state) {
		return switch (state) {
			case FREE -> GREEN;
			case RESERVED -> AMBER;
			case OCCUPIED -> TEXT;
			case SERVICE -> CYAN;
		};
	}
	private static String cohortLabel(DroneEntity drone) {
		if (drone.cohortId().isBlank()) return "UNASSIGNED";
		int separator = drone.cohortId().lastIndexOf('#');
		String id = separator >= 0 ? drone.cohortId().substring(separator + 1) : drone.cohortId();
		return shortId(id);
	}
	private static String rendezvousLabel(DroneEntity drone) {
		if (!drone.hasRendezvous()) return "CALC";
		return drone.rendezvousPos().getX() + "," + drone.rendezvousPos().getY() + "," + drone.rendezvousPos().getZ();
	}

	private static String modeLabel(DroneMode mode) {
		return switch (mode) {
			case FOLLOW -> "追従";
			case RETURN -> "帰還中";
			case ORBIT -> "周回警戒";
			case DOCK -> "Dock帰投中";
			case WAYPOINT -> "地点移動";
			case STANDBY -> "待機";
		};
	}

	private static String statusLabel(DroneEntity drone) {
		if (drone.combatActive()) return drone.combatStatusLabel()
			+ " / FLT " + drone.batteryPercent() + "% " + drone.batteryTier().displayName()
			+ " / WPN " + drone.weaponPowerPercent() + "%"
			+ " / GUN " + drone.gunAmmo() + " / MSL " + drone.missiles()
			+ " / HEAT " + drone.laserHeat() / 10 + "%"
			+ " / SYS " + drone.lowestSubsystemCondition() / 10 + "%";
		if (drone.emergencyInterceptActive()) return "EMERGENCY INTERCEPT";
		if (drone.hasPatrolRoute()) return "ROUTE PATROL " + (drone.patrolRouteIndex() + 1)
			+ "/" + drone.patrolRouteSize();
		if (drone.hasSecurityPatrol()) return drone.securityStatusLabel();
		if (drone.hasFieldOperation()) return drone.fieldStatusLabel();
		if (drone.role() == DroneRole.CARGO && drone.cargoState() != jp.morrowgear.drone.CargoState.UNASSIGNED) {
			return "CARGO / " + drone.cargoStatusLabel();
		}
		if (drone.role() == DroneRole.ENGINEER) return "ENGINEER / " + drone.engineerStatusLabel();
		String defense = drone.defenseSlot() >= 0 ? "DEF-" + (drone.defenseSlot() + 1) + " / " : "";
		String recovery = drone.recoveryLevel() > 0 ? "RECOVERY-" + drone.recoveryLevel() + " / " : "";
		defense += recovery;
		if (drone.formationCatchUp()) defense += "CATCH-UP / ";
		if (drone.mode() != DroneMode.WAYPOINT) return defense + modeLabel(drone.mode());
		if (drone.hasTrackingTarget()) {
			return defense + switch (drone.missionStage()) {
				case DroneEntity.MISSION_MUSTER -> "TARGET MUSTER";
				case DroneEntity.MISSION_MOVING -> "TARGET TRACK";
				case DroneEntity.MISSION_ORBIT -> "TARGET WATCH";
				case DroneEntity.MISSION_CONVERGING -> "RENDEZVOUS";
				case DroneEntity.MISSION_ORBIT_ENTRY -> "ORBIT ENTRY";
				default -> "TARGET MISSION";
			};
		}
		return defense + switch (drone.missionStage()) {
			case DroneEntity.MISSION_MUSTER -> "集合旋回";
			case DroneEntity.MISSION_MOVING -> "隊形移動";
			case DroneEntity.MISSION_ORBIT -> "地点哨戒";
			case DroneEntity.MISSION_CONVERGING -> "分散収束";
			case DroneEntity.MISSION_ORBIT_ENTRY -> "旋回進入";
			default -> "地点任務";
		};
	}

	private static String shortId(String id) { return id.startsWith("MG-DRN-") ? id.substring(7) : id; }
	private static String threatLabel(int score) {
		if (score >= 28) return "CRITICAL";
		if (score >= 14) return "HIGH";
		if (score >= 6) return "GUARDED";
		return "LOW";
	}

	private static void drawDiamond(GuiGraphicsExtractor g, int x, int y, int color) {
		for (int i = 0; i < 7; i++) { int half = i <= 3 ? i : 6 - i; g.horizontalLine(x - half, x + half, y - 3 + i, color); }
		g.fill(x - 1, y - 1, x + 2, y + 2, BG);
	}

	private static void drawLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		int dx = x2 - x1;
		int dy = y2 - y1;
		int steps = Math.max(Math.abs(dx), Math.abs(dy));
		if (steps == 0) return;
		for (int i = 0; i <= steps; i++) g.fill(x1 + dx * i / steps, y1 + dy * i / steps, x1 + dx * i / steps + 1, y1 + dy * i / steps + 1, color);
	}

	private static void drawOrbitRing(GuiGraphicsExtractor g, Rect clip, int centerX, int centerY, int radius, int color) {
		int previousX = centerX + radius;
		int previousY = centerY;
		for (int step = 1; step <= 64; step++) {
			double angle = step * Math.PI * 2.0 / 64.0;
			int x = centerX + (int) Math.round(Math.cos(angle) * radius);
			int y = centerY + (int) Math.round(Math.sin(angle) * radius);
			if (clip.contains(previousX, previousY) && clip.contains(x, y)) drawLine(g, previousX, previousY, x, y, color);
			previousX = x;
			previousY = y;
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

	private enum SelectionMode { UNIT, GROUP, ALL }
	private record Rect(int x, int y, int w, int h) { int right() { return x + w; } int bottom() { return y + h; } boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); } }
	private record Layout(int header, int bottomTop, int leftWidth, int dockHeaderY, Rect map, Rect right) {}
	private record DockInfo(String id, long pos, DroneEntity occupant, DockOperationalPolicy.Status status, DockView allocation) {}
	private record ContainerInfo(String label, long pos, int distance, String coordinate) {}
	private record MapPoint(int x, int y) {}
	private record WingCard(String groupId, List<DroneEntity> members, DroneEntity leader,
		int recoveryCount, int averageBattery, String missionLabel) {}
	private record OperationalWing(String id, String label, List<DroneEntity> members,
		DroneEntity leader, int recoveryCount, int averageBattery) {}
}
