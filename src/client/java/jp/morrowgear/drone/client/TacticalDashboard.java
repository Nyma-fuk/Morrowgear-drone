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
import java.util.UUID;

import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneMode;
import jp.morrowgear.drone.DroneResourceUiPolicy;
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
import jp.morrowgear.drone.network.FleetLinkPayload;
import jp.morrowgear.drone.network.FleetOperationPayload;
import jp.morrowgear.drone.network.TacticalContactPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TacticalDashboard extends AbstractWidget {
	private static final int NAV_RAIL_WIDTH = ControllerLayoutPolicy.NAV_WIDTH;
	private static final Identifier TERRAIN_TEXTURE_ID = Identifier.fromNamespaceAndPath("morrowgear_drone", "c2_terrain");
	private static final int BG = 0xFF121514;
	private static final int PANEL = 0xFF1A1E1C;
	private static final int PANEL_ALT = 0xFF242A27;
	private static final int PANEL_RAISED = 0xFF303A32;
	private static final int BORDER = 0xFF343C36;
	private static final int GRID = 0xFF29332C;
	private static final int TEXT = 0xFFE1E7E0;
	private static final int MUTED = 0xFF99A59D;
	private static final int CYAN = 0xFF7CDEC8;
	private static final int BLUE = 0xFF5594FF;
	private static final int GREEN = 0xFFA5D391;
	private static final int AMBER = 0xFFE7BD79;
	private static final int RED = 0xFFF28D83;
	private static final String[] PAGES = {"作戦地図", "Wing編成", "任務", "Dock・補給", "機体回収", "バイザー", "機器", "設定", "補給網", "母艦", "報告履歴"};
	private static final String[] PAGE_ICONS = {"map", "layers", "list-checks", "battery-charging", "wrench", "radar", "boxes", "sliders-horizontal", "boxes", "container", "clock"};
	private static final int[] NAV_ORDER = {0, 1, 2, 3, 8, 4, 9, 5, 6, 10, 7};
	private final int[] pageOffsets = new int[PAGES.length];
	private static final String[] ITEM_IDS = {"controller", "tactical_visor", "recovery_tool", "operation_marker", "field_drone_unit", "dock_item", "solar_service_station",
		"scout_module", "cargo_module", "engineer_module", "security_module", "salvage_module", "power_cell", "standard_battery_pack",
		"reinforced_battery_pack", "high_density_battery_pack", "raw_morrow_composite", "morrow_alloy", "lightweight_frame",
		"basic_control_board", "flight_actuator", "autocannon_module", "laser_module", "missile_module",
		"autocannon_magazine", "laser_cell", "micro_missile_pack"};
	private final ControllerPreferences preferences = ControllerPreferences.current();
	private int page;
	private int pageScroll;
	private int roleFilter = -1;
	private int sortMode;
	private int inspectedEquipment = -1;
	private int hoverX, hoverY;
	private final VisorClientConfig visorConfig = VisorClientConfig.current();
	private static final String[] ACTIONS = { "follow", "standby", "return", "dock", "orbit", "decommission" };
	private static final DroneRole[] MODULE_ROLES = { DroneRole.FIELD, DroneRole.SCOUT, DroneRole.CARGO, DroneRole.ENGINEER, DroneRole.SECURITY, DroneRole.SALVAGE };
	private static final SecurityLoadout[] WEAPON_LOADOUTS = {
		SecurityLoadout.AUTOCANNON, SecurityLoadout.LASER, SecurityLoadout.MISSILE
	};
	private static final String[] WEAPON_LABELS = { "機関砲", "レーザー", "ミサイル" };
	private static final FieldOperationType[] WORK_TYPES = {
		FieldOperationType.ORE, FieldOperationType.EXCAVATE, FieldOperationType.FORESTRY
	};
	private static final String[] WORK_LABELS = { "鉱脈採掘", "範囲掘削", "伐採" };

	private final Minecraft client = Minecraft.getInstance();
	private final net.minecraft.client.gui.components.EditBox search = new net.minecraft.client.gui.components.EditBox(client.font, 0, 0, 100, 20, Component.literal("機体名・識別番号・Wingを検索"));
	private int inspectorScroll;
	private double mapCenterX = Double.NaN, mapCenterZ = Double.NaN;
	private boolean panning;
	private int transmitted, suppressed;
	private String transmitProblem = "";
	private final ControllerSelection selected = new ControllerSelection();
	private java.util.UUID selectionAnchor;
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
	private boolean armedWaypoint;
	private boolean armedAttack;
	private boolean forceOverride;
	private int routePointIndex = -1;
	private int routeStartIndex = -1;
	private final int[] workRadii = {32, 8, 20};
	private int guardRadius = 24;
	private boolean relocateRoutePoint;
	private long inspectedSupplyDock = Long.MIN_VALUE;
	private BlockPos pendingWorldPoint;
	private BlockPos pendingTaskPoint;
	private Set<java.util.UUID> draftTargets = Set.of();
	private Map<java.util.UUID, String> draftMissions = Map.of();
	private final ControllerCommandWatch commandWatch = new ControllerCommandWatch();
	private long menuRequestAt;
	private double pendingMapX = Double.NaN;
	private double pendingMapY = Double.NaN;
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
	private Object dockCacheLevel;
	private long dockRefreshAt;
	private List<DockInfo> cachedDocks = List.of();
	private Object foreignCacheLevel;
	private long foreignRefreshAt;
	private List<DroneEntity> cachedForeignDrones = List.of();
	private List<ForeignDock> cachedForeignDocks = List.of();
	private Object containerCacheLevel;
	private long containerRefreshAt;
	private List<ContainerInfo> cachedContainers = List.of();
	private String transientNotice = "";
	private long transientNoticeUntil;
	private long confirmStoreUntil;
	private Set<Integer> confirmStoreSelection = Set.of();

	public TacticalDashboard(int x, int y, int width, int height) {
		super(x, y, width, height, Component.literal("Morrowgear integrated tactical command dashboard"));
		search.setHint(Component.literal("機体名・識別番号・Wing"));
		search.setMaxLength(80);
		search.setResponder(value -> unitScroll = 0);
	}

	boolean focusReport(java.util.UUID identity, String dimension) {
		if (client.level == null || !client.level.dimension().identifier().toString().equals(ControllerDisplayText.dimensionId(dimension))) return false;
		DroneEntity drone = drones().stream().filter(d -> d.getUUID().equals(identity)).findFirst().orElse(null);
		if (drone == null) return false;
		selected.clear();
		selected.add(drone.getId());
		selectionMode = SelectionMode.UNIT;
		mapCenterX = drone.getX(); mapCenterZ = drone.getZ();
		wingMapMode = false;
		page = 0;
		return true;
	}

	String runAutomationSmoke() {
		int original = page;
		var originalScreen = client.gui.screen();
		try {
			for (int candidate = 0; candidate < ControllerLayoutPolicy.NAV_COUNT; candidate++) {
				String failure = selectAutomationPage(candidate);
				if (!failure.isEmpty()) return failure;
				if (candidate == 10) client.setScreenAndShow(originalScreen);
			}
			return "";
		} catch (RuntimeException failure) {
			return "controller page initialization failed: " + failure;
		} finally {
			page = original;
			if (client.gui.screen() != originalScreen) client.setScreenAndShow(originalScreen);
		}
	}

	String selectAutomationPage(int candidate) {
		if (candidate < 0 || candidate >= ControllerLayoutPolicy.NAV_COUNT) return "invalid page " + candidate;
		Rect requested = pageTab(candidate);
		boolean handled = handlePageClick(layout(), drones(), docks(drones()),
			requested.x + requested.w / 2.0, requested.y + requested.h / 2.0, false);
		if (!handled) return "navigation action was not handled on page " + candidate;
		if (candidate == 10) return client.gui.screen() instanceof OperationHistoryScreen
			? "" : "history screen did not open through its normal navigation action";
		if (page != candidate) return "navigation action selected the wrong page " + candidate;
		Layout current = layout();
		Rect tab = pageTab(candidate);
		if (tab.w <= 0 || tab.h <= 0 || current.bottomTop <= current.header)
			return "invalid controller layout on page " + candidate;
		if (candidate < 5 && (current.map.w <= 0 || current.leftWidth <= NAV_RAIL_WIDTH || current.right.w <= 0))
			return "operational workspace collapsed on page " + candidate;
		return "";
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float scale = dashboardScale();
		int virtualMouseX = (int) (mouseX / scale);
		int virtualMouseY = (int) (mouseY / scale);
		hoverX = virtualMouseX; hoverY = virtualMouseY;
		g.pose().pushMatrix();
		g.pose().scale(scale, scale);
		List<DroneEntity> drones = drones();
		if (client.player != null && Double.isNaN(mapCenterX)) recenterMap(false);
		List<TacticalContactPayload.Row> mapEntities = mapEntities();
		List<DockInfo> docks = docks(drones);
		if (!selected.equals(confirmStoreSelection)) confirmStoreUntil = 0;
		Layout l = layout();
		TacticalUiPolicy.CommandAvailability availability = availability(drones);

		g.fill(0, 0, virtualWidth(), virtualHeight(), BG);
		drawHeader(g, l, drones, mapEntities, availability);
		drawNavigationRail(g, l);
		if (page >= 5) {
			drawUtilityPage(g, l, drones);
			drawBottomPanel(g, l, drones);
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
		List<TacticalContactPayload.Row> mapEntities, TacticalUiPolicy.CommandAvailability availability) {
		g.fill(0, 0, virtualWidth(), l.header, PANEL);
		g.horizontalLine(0, virtualWidth(), l.header - 1, BORDER);
		g.fill(10, 10, 13, 29, CYAN);
		g.text(client.font, "MORROWGEAR", 21, 14, TEXT);
		int linkedUnits = FleetLinkClient.units(client).size();
		int relayCount = FleetLinkClient.relays(client).size();
		g.text(client.font, "受信 " + linkedUnits + "機   中継 " + relayCount + "局   選択 " + selected.size() + "機   敵性 "
			+ mapEntities.size(), NAV_RAIL_WIDTH + 14, 14, CYAN);
		String dimension = client.level == null ? "未接続" : client.level.dimension().identifier().toString();
		g.text(client.font, fitText(ControllerDisplayText.dimensionName(dimension) + " / データリンク網", 230), virtualWidth() - 240, 14, MUTED);
		Map<java.util.UUID, ControllerCommandWatch.Observation> received = new LinkedHashMap<>();
		drones.forEach(d -> received.put(d.getUUID(), commandObservation(d)));
		FleetLinkClient.units(client).forEach(unit -> received.putIfAbsent(unit.id(),
			new ControllerCommandWatch.Observation(unit.status() + ":" + unit.groupId()
				+ ":" + unit.role() + ":" + unit.mode() + ":" + unit.docked(), unit.mission())));
		var counts = commandWatch.observe(received, System.currentTimeMillis());
		String status = menuRequestAt > 0 ? System.currentTimeMillis() - menuRequestAt < 5000
			? "操作要求: サーバー応答待ち" : "操作要求: 結果未確認 / 自動再送なし"
			: ControllerCommandResults.summary() + " / 応答待ち " + counts.waiting();
		g.text(client.font, fitText(status, virtualWidth() - NAV_RAIL_WIDTH - 24), NAV_RAIL_WIDTH + 14, 28, MUTED);
	}

	private void drawNavigationRail(GuiGraphicsExtractor g, Layout l) {
		g.fill(0, l.header, NAV_RAIL_WIDTH, virtualHeight(), PANEL);
		g.verticalLine(NAV_RAIL_WIDTH - 1, l.header, virtualHeight(), BORDER);
		for (int index = 0; index < PAGES.length; index++) {
			Rect tab = pageTab(index);
			boolean active = page == index;
			g.fill(tab.x, tab.y, tab.right(), tab.bottom(), active ? PANEL_RAISED : PANEL);
			if (active) g.fill(tab.x, tab.y, tab.x + 3, tab.bottom(), CYAN);
			g.outline(tab.x, tab.y, tab.w, tab.h, active ? CYAN : BORDER);
			HmiArt.icon(g, PAGE_ICONS[index], tab.x + 7, tab.y + 7, 15);
			g.text(client.font, fitText(PAGES[index], tab.w - 29),
				tab.x + 26, tab.y + 10, active ? TEXT : MUTED);
		}
	}

	private void drawHeaderMetric(GuiGraphicsExtractor g, int x, String label, String value, int color) {
		g.text(client.font, label, x, 9, MUTED);
		g.text(client.font, value, x, 23, color, true);
	}

	private void drawLeftPanel(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, List<DockInfo> docks, int mouseX, int mouseY) {
		drones = visibleRoster(drones);
		List<FleetLinkPayload.Unit> remote = visibleRemoteRoster(drones);
		g.fill(NAV_RAIL_WIDTH, l.header, l.leftWidth, l.bottomTop, PANEL);
		g.verticalLine(l.leftWidth - 1, l.header, l.bottomTop, BORDER);
		g.text(client.font, "機体一覧", NAV_RAIL_WIDTH + 10, l.header + 9, TEXT, true);
		for (int i = 0; i < 2; i++) {
			Rect filter = rosterFilterRect(l, i);
			g.fill(filter.x, filter.y, filter.right(), filter.bottom(), PANEL_ALT);
			g.text(client.font, i == 0 ? roleFilter < 0 ? "全役割" : roleLabel(DroneRole.values()[roleFilter])
				: new String[] {"ID順", "役割順", "電力順", "任務順"}[sortMode], filter.x + 6, filter.y + 5, MUTED);
		}
		String roster = (drones.size() + remote.size()) + "機";
		g.text(client.font, roster, l.leftWidth - client.font.width(roster) - 10, l.header + 9, CYAN);
		Rect searchBounds = searchRect(l);
		search.setX(searchBounds.x); search.setY(searchBounds.y); search.setWidth(searchBounds.w); search.setHeight(searchBounds.h);
		search.extractRenderState(g, mouseX, mouseY, 0);

		String[] tabs = { "個体", "Wing", "表示", "全受信", "解除" };
		for (int i = 0; i < tabs.length; i++) {
			Rect tab = tabRect(l, i);
			boolean active = selectionMode.ordinal() == i;
			g.fill(tab.x, tab.y, tab.right(), tab.bottom(), active ? PANEL_ALT : PANEL);
			if (active) g.horizontalLine(tab.x, tab.right(), tab.bottom() - 1, CYAN);
			g.centeredText(client.font, tabs[i], tab.x + tab.w / 2, tab.y + 6, active ? TEXT : MUTED);
		}

		List<OperationalWing> wings = operationalWings(drones);
		int rowCount = selectionMode == SelectionMode.GROUP && wingMapMode ? wings.size() : drones.size() + remote.size();
		int unitCapacity = unitCapacity(l);
		unitScroll = clampScroll(unitScroll, rowCount, unitCapacity);
		for (int visible = 0; visible < unitCapacity && unitScroll + visible < rowCount; visible++) {
			Rect row = unitRect(l, visible);
			if (selectionMode == SelectionMode.GROUP && wingMapMode) {
				OperationalWing wing = wings.get(unitScroll + visible);
				boolean active = wing.members.stream().allMatch(drone -> selected.contains(drone.getId()));
				boolean hover = row.contains(mouseX, mouseY);
				g.fill(row.x, row.y, row.right(), row.bottom(), active ? PANEL_RAISED : hover ? PANEL_ALT : PANEL);
				g.outline(row.x, row.y, row.w, row.h, active ? CYAN : BORDER);
				int stateColor = wing.averageBattery < 35 ? AMBER : GREEN;
				drawSelectionToggle(g, row, active, stateColor);
				g.text(client.font, wing.label, row.x + 18, row.y + 6, TEXT, true);
				String coordinate = (int)Math.round(wing.leader.getX()) + "," + (int)Math.round(wing.leader.getZ());
				String detail = wing.members.size() + "機 / " + wing.missionLabel + " / " + coordinate;
				g.text(client.font, fitText(detail, row.w - 86), row.x + 18, row.y + 18, MUTED);
				String battery = wing.averageBattery + "%";
				g.text(client.font, battery, row.right() - client.font.width(battery) - 7, row.y + 10, stateColor, true);
				continue;
			}
			int rowIndex = unitScroll + visible;
			if (rowIndex >= drones.size()) {
				FleetLinkPayload.Unit unit = remote.get(rowIndex - drones.size());
				boolean active = selected.contains(unit.entityId());
				boolean hover = row.contains(mouseX, mouseY);
				g.fill(row.x, row.y, row.right(), row.bottom(), active ? 0xFF17343B : hover ? PANEL_RAISED : PANEL);
				g.outline(row.x, row.y, row.w, row.h, active ? CYAN : BORDER);
				int stateColor = unit.batteryPercent() < 35 ? AMBER : GREEN;
				drawSelectionToggle(g, row, active, stateColor);
				DroneRole role = DroneRole.byId(unit.role());
				HmiArt.role(g, role, row.x + 16, row.y + 3, 26);
				g.text(client.font, fitText(ControllerDisplayText.unitName(unit.name(), unit.unitId()), row.w - 52), row.x + 44, row.y + 3, TEXT, true);
				g.text(client.font, roleLabel(role) + " / RELAY H" + unit.relayHops(), row.x + 44, row.y + 14, MUTED);
				g.text(client.font, fitText(unit.status(), row.w - 72), row.x + 44, row.y + 25, CYAN);
				String battery = unit.batteryPercent() + "% / Q" + unit.linkQuality();
				g.text(client.font, battery, row.right() - client.font.width(battery) - 7, row.y + 14, stateColor, true);
				continue;
			}
			DroneEntity drone = drones.get(rowIndex);
			boolean active = selected.contains(drone.getId());
			boolean hover = row.contains(mouseX, mouseY);
			g.fill(row.x, row.y, row.right(), row.bottom(), active ? 0xFF17343B : hover ? PANEL_RAISED : PANEL);
			g.outline(row.x, row.y, row.w, row.h, active ? CYAN : BORDER);
			int stateColor = drone.batteryPercent() < 35 ? AMBER : GREEN;
			drawSelectionToggle(g, row, active, stateColor);
			HmiArt.role(g, drone.role(), row.x + 16, row.y + 3, 26);
			g.text(client.font, fitText(ControllerDisplayText.unitName(drone.getName().getString(), drone.unitId()), row.w - 52), row.x + 44, row.y + 3, TEXT, true);
			g.text(client.font, roleLabel(drone.role()), row.x + 44, row.y + 14, MUTED);
			g.text(client.font, fitText(rosterStatusLabel(drone), row.w - 52), row.x + 44, row.y + 25, CYAN);
			String battery = drone.batteryPercent() + "%";
			g.text(client.font, battery, row.right() - client.font.width(battery) - 7, row.y + 14, stateColor, true);
		}
		drawScrollCounter(g, l.leftWidth - 145, l.dockHeaderY - 10, unitScroll, rowCount, unitCapacity);
		if (rowCount > unitCapacity) drawPageButtons(g, unitFirstRect(l), unitUpRect(l), unitDownRect(l), unitLastRect(l));

	}

	private void drawMap(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones,
		List<TacticalContactPayload.Row> entities, int mouseX, int mouseY) {
		Rect map = l.map;
		g.enableScissor(map.x, map.y, map.right(), map.bottom());
		g.fill(map.x, map.y, map.right(), map.bottom(), BG);
		drawTerrain(g, map);
		drawSecurityZones(g, map, drones);
		drawPatrolRoutes(g, map, drones);
		for (ForeignDock dock : foreignDocks()) {
			BlockPos pos = BlockPos.of(dock.pos());
			MapPoint point = mapPoint(map, pos.getX(), pos.getZ());
			if (point == null) continue;
			g.outline(point.x - 3, point.y - 3, 7, 7, MUTED);
			g.text(client.font, fitText(dock.owner() + " / DOCK", 92),
				Math.min(point.x + 7, map.right() - 94), point.y - 4, MUTED);
		}
		for (TacticalContactPayload.Row entity : entities) {
			MapPoint point = mapPoint(map, entity.x(), entity.z());
			if (point != null) drawMobMarker(g, point.x, point.y, RED, true);
		}
		for (DroneEntity drone : drones) {
			MapPoint point = mapPoint(map, drone.getX(), drone.getZ());
			if (point == null) continue;
			boolean active = selected.contains(drone.getId());
			drawDiamond(g, point.x, point.y, active ? CYAN : GREEN);
			if (active) {
				int radius = preferences.markerRadius() + 3;
				g.outline(point.x - radius, point.y - radius, radius * 2 + 1, radius * 2 + 1, CYAN);
				g.text(client.font, fitText(drone.getName().getString(), 105), Math.min(point.x + 10, map.right() - 112), point.y - 5, TEXT);
				if (drone.hasWaypoint()) {
					MapPoint target = mapPoint(map, drone.waypointPos().getX(), drone.waypointPos().getZ());
					if (target != null) drawLine(g, point.x, point.y, target.x, target.y, CYAN);
				}
			}
		}
		Set<java.util.UUID> localIds = drones.stream().map(DroneEntity::getUUID).collect(java.util.stream.Collectors.toSet());
		for (FleetLinkPayload.Unit unit : FleetLinkClient.units(client)) {
			if (localIds.contains(unit.id())) continue;
			MapPoint point = mapPoint(map, unit.x(), unit.z());
			if (point == null) continue;
			boolean active = selected.contains(unit.entityId());
			drawDiamond(g, point.x, point.y, active ? CYAN : 0xFF65AFA8);
			if (active) {
				g.outline(point.x - 6, point.y - 6, 13, 13, CYAN);
				g.text(client.font, fitText(unit.unitId() + " / H" + unit.relayHops(), 112),
					Math.min(point.x + 10, map.right() - 116), point.y - 5, TEXT);
			}
		}
		for (FleetLinkPayload.Relay relay : FleetLinkClient.relays(client)) {
			MapPoint point = mapPoint(map, relay.x(), relay.z());
			if (point == null) continue;
			g.outline(point.x - 4, point.y - 4, 9, 9, CYAN);
			g.fill(point.x - 1, point.y - 1, point.x + 2, point.y + 2, GREEN);
			if (mapZoom >= 3.0) g.text(client.font, "R" + relay.hops(), point.x + 6, point.y - 4, MUTED);
		}
		for (DroneEntity drone : foreignDrones()) {
			MapPoint point = mapPoint(map, drone.getX(), drone.getZ());
			if (point == null) continue;
			g.outline(point.x - 3, point.y - 3, 7, 7, MUTED);
			String owner = drone.ownerName().isBlank() ? "他ユーザー" : drone.ownerName();
			g.text(client.font, fitText(owner + " / " + roleLabel(drone.role()), 108),
				Math.min(point.x + 7, map.right() - 110), point.y - 4, MUTED);
		}
		if (selectionMode == SelectionMode.GROUP) drawOperationalWingMarkers(g, map, drones);
		if (client.player != null) {
			MapPoint player = mapPoint(map, client.player.getX(), client.player.getZ());
			if (player != null) { int radius = preferences.markerRadius() + 1; g.outline(player.x - radius, player.y - radius, radius * 2 + 1, radius * 2 + 1, TEXT); }
		}
		g.fill(map.x, map.y, map.right(), map.y + 29, PANEL);
		g.text(client.font, "作戦地図", map.x + 10, map.y + 10, TEXT);
		String[] names = {"自分", "選択", "-", "+"};
		for (int i = 0; i < 4; i++) drawCargoButton(g, mapToolRect(map, i), names[i], false, CYAN, i != 1 || !selected.isEmpty());
		drawRouteControls(g, map, mouseX, mouseY);
		drawPendingWaypoint(g, map);
		drawPendingTask(g, map);
		g.disableScissor();
	}

	private Rect mapToolRect(Rect map, int index) { return new Rect(map.right() - 130 + index * 31, map.y + 4, 28, 21); }

	private void recenterMap(boolean selection) {
		if (client.player == null) return;
		var units = selection ? selectedCommandUnits() : List.<CommandUnit>of();
		mapCenterX = units.isEmpty() ? client.player.getX() : units.stream().mapToDouble(unit -> unit.position().x).average().orElseThrow();
		mapCenterZ = units.isEmpty() ? client.player.getZ() : units.stream().mapToDouble(unit -> unit.position().z).average().orElseThrow();
	}

	private boolean selectUnitAt(Rect map, List<DroneEntity> drones, double x, double y, boolean additive) {
		DroneEntity nearest = null;
		double distance = preferences.markerHitRadius();
		for (DroneEntity drone : drones) {
			MapPoint point = mapPoint(map, drone.getX(), drone.getZ());
			if (point == null) continue;
			double candidate = Math.hypot(point.x - x, point.y - y);
			if (candidate < distance) { distance = candidate; nearest = drone; }
		}
		FleetLinkPayload.Unit remote = null;
		double remoteDistance = distance;
		Set<java.util.UUID> localIds = drones.stream().map(DroneEntity::getUUID).collect(java.util.stream.Collectors.toSet());
		for (FleetLinkPayload.Unit unit : FleetLinkClient.units(client)) {
			if (localIds.contains(unit.id())) continue;
			MapPoint point = mapPoint(map, unit.x(), unit.z());
			if (point == null) continue;
			double candidate = Math.hypot(point.x - x, point.y - y);
			if (candidate < remoteDistance) { remoteDistance = candidate; remote = unit; }
		}
		if (nearest == null && remote == null) return false;
		if (!additive) selected.clear();
		int id = remote != null && remoteDistance < distance ? remote.entityId() : nearest.getId();
		if (additive && selected.contains(id)) selected.remove(id); else selected.add(id);
		selectionMode = SelectionMode.UNIT;
		wingMapMode = false;
		return true;
	}

	private void drawOperationalWingMarkers(GuiGraphicsExtractor g, Rect map, List<DroneEntity> drones) {
		for (OperationalWing wing : operationalWings(drones)) {
			MapPoint point = mapPoint(map, wing.leader.getX(), wing.leader.getZ());
			if (point == null) continue;
			boolean active = wing.members.stream().allMatch(member -> selected.contains(member.getId()));
			int color = active ? AMBER : CYAN;
			String title = fitText(wing.label + " / " + wing.members.size() + "U", 94);
			String mission = fitText(wing.missionLabel, 94);
			int width = Math.max(82, Math.max(client.font.width(title), client.font.width(mission)) + 22);
			int x = Math.max(map.x + 4, Math.min(point.x + 8, map.right() - width - 4));
			int y = Math.max(map.y + 62, Math.min(point.y - 13, map.bottom() - 31));
			if (!active) continue;
			// Selected Wing labels remain unframed so dense formations do not cover the terrain.
			drawDiamond(g, x + 10, y + 10, color);
			g.text(client.font, title, x + 20, y + 5, TEXT, true);
			g.text(client.font, mission, x + 20, y + 17, MUTED);
		}
	}

	private void drawPendingWaypoint(GuiGraphicsExtractor g, Rect map) {
		if (!armedWaypoint) return;
		g.outline(map.x, map.y, map.w, map.h, AMBER);
		if (Double.isNaN(pendingMapX) || Double.isNaN(pendingMapY)) return;
		MapPoint point = pendingWorldPoint == null ? null : mapPoint(map, pendingWorldPoint.getX(), pendingWorldPoint.getZ());
		int x = point == null ? map.x + 12 : point.x;
		int y = point == null ? map.y + 70 : point.y;
		List<DroneEntity> formation = selectedMissionFormation();
		if (point != null && !formation.isEmpty()) {
			MapPoint origin = mapPoint(map, formation.getFirst().getX(), formation.getFirst().getZ());
			if (origin != null) drawLine(g, origin.x, origin.y, x, y, AMBER);
		}
		if (point != null) g.outline(x - 6, y - 6, 12, 12, AMBER);
		g.text(client.font, point == null ? "移動先は表示範囲外" : "移動先", Math.min(x + 10, map.right() - 112), y - 4, AMBER, true);
		Rect apply = waypointApplyRect(map);
		Rect cancel = waypointCancelRect(map);
		g.fill(apply.x, apply.y, apply.right(), apply.bottom(), 0xFF3B3015);
		g.outline(apply.x, apply.y, apply.w, apply.h, AMBER);
		g.centeredText(client.font, "移動を指示", apply.x + apply.w / 2, apply.y + 6, TEXT);
		g.fill(cancel.x, cancel.y, cancel.right(), cancel.bottom(), PANEL_ALT);
		g.outline(cancel.x, cancel.y, cancel.w, cancel.h, BORDER);
		g.centeredText(client.font, "取消", cancel.x + cancel.w / 2, cancel.y + 6, TEXT);
	}

	private void drawPendingTask(GuiGraphicsExtractor g, Rect map) {
		if (pendingTaskPoint == null || (!armedGuard && armedWork == FieldOperationType.NONE)) return;
		int radius = armedGuard ? guardRadius : workRadii[workIndex(armedWork)];
		MapPoint point = mapPoint(map, pendingTaskPoint.getX(), pendingTaskPoint.getZ());
		if (point != null) drawOrbitRing(g, map, point.x, point.y, (int)(radius * mapZoom), AMBER);
		int y = map.bottom() - 94;
		g.fill(map.x + 8, y, map.right() - 8, map.bottom() - 30, PANEL);
		String label = (armedGuard ? "警戒" : WORK_LABELS[workIndex(armedWork)])
			+ " / " + (autoOperations && !armedGuard ? "自動編成" : selected.size() + "機") + " / 半径 " + radius + "m";
		g.text(client.font, fitText(label, map.w - 28), map.x + 14, y + 7, AMBER);
		g.text(client.font, fitText(pendingTaskPoint.getX() + ", " + pendingTaskPoint.getZ()
			+ (armedGuard ? " / 警戒範囲" : " / 地形変更あり"), map.w - 28), map.x + 14, y + 21, MUTED);
		drawCargoButton(g, waypointApplyRect(map), "指示を送信", false, AMBER, true);
		drawCargoButton(g, waypointCancelRect(map), "取消", false, CYAN, true);
	}

	private void drawWingBoard(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, int mouseX, int mouseY) {
		Rect board = l.map;
		g.fill(board.x, board.y, board.right(), board.bottom(), BG);
		for (int x = board.x; x <= board.right(); x += 16) g.verticalLine(x, board.y, board.bottom(), GRID);
		for (int y = board.y; y <= board.bottom(); y += 16) g.horizontalLine(board.x, board.right(), y, GRID);
		g.text(client.font, "Wing編成", board.x + 12, board.y + 11, TEXT, true);
		g.text(client.font, "1 Wing 最大8機", board.right() - 116, board.y + 11, CYAN);
		Rect create = newWingRect(board);
		boolean createHover = create.contains(mouseX, mouseY);
		g.fill(create.x, create.y, create.right(), create.bottom(), createHover ? 0xFF174139 : PANEL_ALT);
		g.outline(create.x, create.y, create.w, create.h, createHover ? GREEN : CYAN);
		g.centeredText(client.font, "+ Wingを新規編成", create.x + create.w / 2, create.y + 6, TEXT);

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
			g.fill(card.x, card.y, card.right(), card.bottom(), hover ? PANEL_ALT : PANEL);
			g.outline(card.x, card.y, card.w, card.h, hover ? CYAN : BORDER);

			WingCard wing = cards.get(cardIndex);
			int stateColor = wing.averageBattery < 35 ? AMBER : GREEN;
			g.fill(card.x, card.y, card.right(), card.y + 29, PANEL_ALT);
			String count = wing.members.size() + " / " + WingMembershipPolicy.MAX_MEMBERS;
			g.text(client.font, fitText(wing.groupId, card.w - client.font.width(count) - 92),
				card.x + 8, card.y + 8, TEXT, true);
			g.text(client.font, count, card.right() - client.font.width(count) - 76, card.y + 8,
				wing.members.size() >= WingMembershipPolicy.MAX_MEMBERS ? AMBER : CYAN, true);
			drawCargoButton(g, wingTransferRect(card), "移籍", false, CYAN, selected.size() == 1);
			String condition = "電力 " + wing.averageBattery + "%  回収 " + wing.recoveryCount;
			int conditionX = card.right() - client.font.width(condition) - 8;
			g.text(client.font, fitText("L " + shortId(wing.leader.unitId()) + "  " + wing.missionLabel,
				conditionX - card.x - 16), card.x + 8, card.y + 32, MUTED);
			g.text(client.font, condition, conditionX, card.y + 32, stateColor);

			for (int slot = 0; slot < WingMembershipPolicy.MAX_MEMBERS; slot++) {
				Rect slotRect = wingSlotRect(card, slot);
				boolean slotHover = slotRect.contains(mouseX, mouseY);
				g.fill(slotRect.x, slotRect.y, slotRect.right(), slotRect.bottom(), slotHover ? 0xFF1C3A40 : BG);
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
			if (center != null && selected.size() <= 8) g.text(client.font, "警戒 / 半径 " + drone.securityRadius() + "m",
				center.x + 6, center.y - 5, color, true);
		}
	}

	private void drawWingModeToggle(GuiGraphicsExtractor g, Layout l, int mouseX, int mouseY) {
		Rect toggle = wingModeToggleRect(l.map);
		boolean hover = toggle.contains(mouseX, mouseY);
		g.fill(toggle.x, toggle.y, toggle.right(), toggle.bottom(), hover ? 0xFF174139 : PANEL_ALT);
		g.outline(toggle.x, toggle.y, toggle.w, toggle.h, hover ? GREEN : CYAN);
		g.centeredText(client.font, wingMapMode ? "Wing編成へ" : "作戦地図へ",
			toggle.x + toggle.w / 2, toggle.y + 6, TEXT);
	}

	private MapPoint mapPoint(Rect map, double worldX, double worldZ) {
		if (client.player == null) return null;
		int x = map.x + map.w / 2 + (int) ((worldX - mapCenterX) * mapZoom);
		int y = map.y + map.h / 2 + (int) ((worldZ - mapCenterZ) * mapZoom);
		return map.contains(x, y) ? new MapPoint(x, y) : null;
	}

	private void drawMobMarker(GuiGraphicsExtractor g, int x, int y, int color, boolean hostile) {
		int radius = preferences.markerRadius() + 1;
		if (hostile) {
			g.horizontalLine(x - radius, x + radius, y, color);
			g.verticalLine(x, y - radius, y + radius, color);
			g.fill(x - 1, y - 1, x + 2, y + 2, BG);
		} else {
			int half = Math.max(1, radius - 2);
			g.fill(x - half, y - half, x + half + 1, y + half + 1, color);
			g.fill(x - 1, y - 1, x + 2, y + 2, BG);
		}
	}

	private void drawTerrain(GuiGraphicsExtractor g, Rect map) {
		if (client.level == null || client.player == null) return;
		int centerWorldX = (int) Math.floor(mapCenterX);
		int centerWorldZ = (int) Math.floor(mapCenterZ);
		int zoomKey = (int) Math.round(mapZoom * 100.0);
		long gameTime = client.level.getGameTime();
		boolean stale = terrainTexture == null || terrainCacheLevel != client.level
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
				int worldX = (int) Math.floor(mapCenterX + (screenX + sample / 2.0 - centerX) / mapZoom);
				int worldZ = (int) Math.floor(mapCenterZ + (screenY + sample / 2.0 - centerY) / mapZoom);
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
					pixels.setPixel(columnIndex, row, (columnIndex + row) % 6 < 2 ? BORDER : BG);
					continue;
				}
				int west = columnIndex > 0 && heights[index - 1] != Integer.MIN_VALUE ? heights[index - 1] : height;
				int north = row > 0 && heights[index - columns] != Integer.MIN_VALUE ? heights[index - columns] : height;
				int slope = height - (west + north) / 2;
				pixels.setPixel(columnIndex, row, ControllerTerrainTone.darken(terrainColor(colors[index], height, slope, water[index])));
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
		panning = false;
		draggedEntity = -1;
		search.setFocused(false);
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
		g.fill(right.x, right.y, right.right(), right.bottom(), PANEL);
		g.verticalLine(right.x, right.y, right.bottom(), BORDER);
		inspectorScroll = Math.clamp(inspectorScroll, 0, Math.max(0, inspectorHeight() - right.h));
		g.enableScissor(right.x, right.y, right.right(), right.bottom());
		int x = right.x + 10, y = right.y + 10 - inspectorScroll, w = right.w - 20;
		List<DroneEntity> units = drones.stream().filter(d -> selected.contains(d.getId())).toList();
		DroneEntity unit = units.size() == 1 ? units.getFirst() : null;
		List<FleetLinkPayload.Unit> remoteUnits = selectedRemoteUnits(drones);
		FleetLinkPayload.Unit remoteUnit = unit == null && units.isEmpty() && remoteUnits.size() == 1
			? remoteUnits.getFirst() : null;
		int selectedCount = units.size() + remoteUnits.size();
		g.text(client.font, selectedCount == 0 ? "選択詳細" : selectedCount + "機を選択", x, y, CYAN);
		if (unit != null) {
			HmiArt.role(g, unit.role(), right.x + (right.w - 104) / 2, y + 17, 104);
			g.text(client.font, fitText(unit.getName().getString(), w), x, y + 124, TEXT);
			g.text(client.font, fitText("役割: " + roleLabel(unit.role()), w), x, y + 138, MUTED);
			g.text(client.font, fitText("所属 " + (unit.groupId().isBlank() ? "未所属" : unit.groupId()), w), x, y + 153, TEXT);
			g.text(client.font, fitText("現在 " + statusLabel(unit), w), x, y + 168, CYAN);
			var resources = resourceSnapshot(unit);
			g.text(client.font, fitText(resources.readiness(), w), x, y + 187, resourceColor(resources.tone()));
			int ry = y + 204;
			for (var metric : resources.metrics()) {
				g.text(client.font, fitText(metric.label(), w / 2), x, ry, MUTED);
				g.text(client.font, fitText(metric.value(), w / 2), x + w / 2, ry, resourceColor(metric.tone()));
				ry += 14;
			}
		} else if (remoteUnit != null) {
			DroneRole role = DroneRole.byId(remoteUnit.role());
			HmiArt.role(g, role, right.x + (right.w - 104) / 2, y + 17, 104);
			g.text(client.font, fitText(remoteUnit.name(), w), x, y + 124, TEXT);
			g.text(client.font, "役割: " + roleLabel(role), x, y + 138, MUTED);
			g.text(client.font, "所属 " + (remoteUnit.groupId().isBlank() ? "未所属" : remoteUnit.groupId()), x, y + 153, TEXT);
			g.text(client.font, fitText("現在 " + remoteUnit.status(), w), x, y + 168, CYAN);
			g.text(client.font, "中継 H" + remoteUnit.relayHops() + " / LINK Q" + remoteUnit.linkQuality(), x, y + 187, GREEN);
			g.text(client.font, "飛行電力", x, y + 204, MUTED);
			g.text(client.font, remoteUnit.batteryPercent() + "%", x + w / 2, y + 204,
				remoteUnit.batteryPercent() < 35 ? AMBER : GREEN);
		} else {
			g.text(client.font, selectedCount == 0 ? "対象を選択してください" : "複数対象の共通操作", x, y + 35, TEXT);
			int ry = y + 60;
			for (DroneRole role : MODULE_ROLES) {
				long count = units.stream().filter(d -> d.role() == role).count()
					+ remoteUnits.stream().filter(d -> DroneRole.byId(d.role()) == role).count();
				if (count == 0) continue;
				HmiArt.role(g, role, x, ry, 24);
				g.text(client.font, roleLabel(role) + " " + count + "機", x + 30, ry + 8, TEXT);
				ry += 30;
			}
			g.text(client.font, "未受信の選択 " + selected.unavailableCount() + "機", x, y + 260, MUTED);
		}
		g.text(client.font, "移動・任務", x, right.y + 323 - inspectorScroll, MUTED);
		for (int i = 0; i < ACTIONS.length; i++) {
			Rect button = commandRect(l, i);
			boolean confirming = i == 5 && client.level != null && client.level.getGameTime() < confirmStoreUntil;
			drawCargoButton(g, button, fitText(confirming ? "格納を確定" : actionLabel(i, drones), button.w - 8), false, i == 5 ? RED : CYAN, selectedCount > 0);
		}
		g.text(client.font, "作業地点を指定", x, right.y + 422 - inspectorScroll, MUTED);
		var available = availability(drones);
		for (int i = 0; i < 4; i++) {
			boolean enabled = i == 3 ? available.securityEnabled() : autoOperations ? !drones.isEmpty() : available.workEnabled(WORK_TYPES[i]);
			drawCargoButton(g, workRect(l, i), i == 3 ? "警戒" : WORK_LABELS[i], false, CYAN, enabled);
		}
		drawCargoButton(g, autoOperationsRect(l), autoOperations ? "自動編成 ON" : "自動編成 OFF", autoOperations, GREEN, true);
		drawCargoButton(g, forceOverrideRect(l), forceOverride ? "強制介入 ON" : "強制介入 OFF",
			forceOverride, forceOverride ? RED : AMBER, selectedCount > 0);
		if (page != 3) drawCargoButton(g, attackTargetRect(l), armedAttack
			? "攻撃対象を地図で選択" : "特定モブを攻撃", armedAttack, RED,
			selectedCount > 0 && available.securityEnabled());
		if (page != 3) drawCargoButton(g, cargoEditorRect(l), "輸送経路を設定", false, CYAN,
			selectedCount > 0 && units.stream().allMatch(d -> d.role() == DroneRole.CARGO)
				&& remoteUnits.stream().allMatch(d -> DroneRole.byId(d.role()) == DroneRole.CARGO));
		if (page != 3) drawCargoButton(g, radiusEditorRect(l), "作業・警戒の範囲", false, CYAN,
			armedGuard || armedWork != FieldOperationType.NONE);
		if (page == 3) {
			g.text(client.font, "着艦中の役割装備", x, right.y + 520 - inspectorScroll, MUTED);
			for (int i = 0; i < MODULE_ROLES.length; i++)
				drawCargoButton(g, moduleRect(l, i), roleLabel(MODULE_ROLES[i]), false, CYAN, !units.isEmpty() && units.stream().allMatch(DroneEntity::isDocked));
			g.text(client.font, "兵装", x, right.y + 608 - inspectorScroll, MUTED);
			for (int i = 0; i < WEAPON_LOADOUTS.length; i++)
				drawCargoButton(g, weaponModuleRect(l, i), WEAPON_LABELS[i], false, AMBER, !units.isEmpty() && units.stream().allMatch(d -> d.role() == DroneRole.SECURITY));
			drawCargoButton(g, capacityUpgradeRect(l), "搭載容量を強化", false, CYAN, unit != null && unit.isDocked() && unit.role() == DroneRole.SECURITY && unit.capacityTier() < jp.morrowgear.drone.PayloadCapacity.MAX_TIER);
		}
		g.disableScissor();
		if (inspectorHeight() > right.h) {
			int track = right.h - 8, thumb = Math.max(20, track * right.h / inspectorHeight());
			int top = right.y + 4 + (track - thumb) * inspectorScroll / Math.max(1, inspectorHeight() - right.h);
			g.fill(right.right() - 3, top, right.right() - 1, top + thumb, MUTED);
		}
	}

	private int inspectorHeight() { return page == 3 ? 715 : 632; }

	private static String resourceName(String code) {
		return switch (code) {
			case "FLT" -> "飛行電力"; case "LZR" -> "レーザー電力"; case "GUN" -> "機関砲弾";
			case "MSL" -> "ミサイル"; case "HEAT" -> "熱"; case "STR" -> "機体"; case "SYS" -> "システム";
			default -> code;
		};
	}

	private void drawBottomPanel(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones) {
		g.fill(NAV_RAIL_WIDTH, l.bottomTop, virtualWidth(), virtualHeight(), PANEL);
		g.horizontalLine(NAV_RAIL_WIDTH, virtualWidth(), l.bottomTop, BORDER);
		g.text(client.font, "選択 " + selected.size() + "機", NAV_RAIL_WIDTH + 10, l.bottomTop + 10, CYAN);
		String[] labels = {"移動先", "任務変更", "巡回経路", "Wing編成", "Dock帰還", "集合", "追従"};
		for (int i = 0; i < labels.length; i++)
			drawCargoButton(g, quickCommandRect(l, i), labels[i], false, i == 4 ? AMBER : CYAN, !selected.isEmpty());
		String status = client.level != null && client.level.getGameTime() < transientNoticeUntil ? transientNotice
			: selected.unavailableCount() > 0 ? "未受信の選択 " + selected.unavailableCount() + "機"
			: "対象 " + selected.size() + "機 / " + ControllerCommandResults.latestReason();
		g.text(client.font, fitText(status, virtualWidth() - NAV_RAIL_WIDTH - 20), NAV_RAIL_WIDTH + 10, virtualHeight() - 15, MUTED);
	}

	private void drawCargoButton(GuiGraphicsExtractor g, Rect rect, String label, boolean active, int color, boolean enabled) {
		g.fill(rect.x, rect.y, rect.right(), rect.bottom(), active || enabled && rect.contains(hoverX, hoverY) ? PANEL_RAISED : PANEL);
		g.outline(rect.x, rect.y, rect.w, rect.h, enabled ? color : BORDER);
		g.centeredText(client.font, fitText(label, rect.w - 8), rect.x + rect.w / 2, rect.y + 5, enabled ? (active ? color : TEXT) : MUTED);
	}

	private void drawIconButton(GuiGraphicsExtractor g, Rect rect, String icon, String label, boolean enabled) {
		g.fill(rect.x, rect.y, rect.right(), rect.bottom(), enabled && rect.contains(hoverX, hoverY) ? PANEL_RAISED : PANEL);
		g.outline(rect.x, rect.y, rect.w, rect.h, enabled ? CYAN : BORDER);
		int size = Math.min(16, rect.h - 4);
		HmiArt.icon(g, icon, rect.x + 5, rect.y + (rect.h - size) / 2, size);
		g.text(client.font, fitText(label, rect.w - size - 16), rect.x + size + 10,
			rect.y + (rect.h - 9) / 2, enabled ? TEXT : MUTED);
	}

	private void drawInteractionStatus(GuiGraphicsExtractor g, Layout l,
		TacticalUiPolicy.CommandAvailability availability) {
		if (page != 0 || !(armedWaypoint || armedGuard || armedWork != FieldOperationType.NONE)) return;
		String message;
		int color;
		if (armedWaypoint) {
			message = Double.isNaN(pendingMapX)
				? "目的地変更: 地図をクリック / 取消で下書きを破棄"
				: "新経路を確認 / 移動を指示 または 取消";
			color = AMBER;
		} else if (armedGuard) {
			message = "警戒配置: 地図上の警戒中心をクリック / " + TacticalUiPolicy.radiusLabel(guardRadius);
			color = RED;
		} else if (armedWork != FieldOperationType.NONE) {
			int radius = workRadii[workIndex(armedWork)];
			String assignment = autoOperations ? "自動編成" : "選択ユニット";
			message = assignment + " / " + WORK_LABELS[workIndex(armedWork)] + ": 地図上の作業中心をクリック / "
				+ TacticalUiPolicy.radiusLabel(radius);
			color = GREEN;
		} else if (client.level != null && client.level.getGameTime() < transientNoticeUntil) {
			message = transientNotice;
			color = CYAN;
		} else if (!availability.hasSelection()) {
			message = "操作対象がありません。左の個体またはWingを選択してください";
			color = AMBER;
		} else {
			message = availability.selected() + "機を選択中 / 移動先で目的地変更 / 地図上のWingも選択可能";
			color = MUTED;
		}
		int width = Math.min(l.map.w - 24, client.font.width(message) + 18);
		int x = l.map.x + (l.map.w - width) / 2;
		int y = l.map.bottom() - 27;
		g.fill(x, y, x + width, y + 19, 0xEA111920);
		g.outline(x, y, width, 19, color);
		g.centeredText(client.font, fitText(message, width - 12), x + width / 2, y + 6, color);
	}


	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		transmitted = suppressed = 0; transmitProblem = "";
		double mouseX = event.x() / dashboardScale();
		double mouseY = event.y() / dashboardScale();
		List<DroneEntity> drones = drones();
		List<DockInfo> docks = docks(drones);
		TacticalUiPolicy.CommandAvailability availability = availability(drones);
		Layout l = layout();
		if (event.button() != 0) {
			draggedEntity = -1;
			panning = page == 0 && l.map.contains(mouseX, mouseY);
			return;
		}
		if (mouseY < l.bottomTop && handlePageClick(l, drones, docks, mouseX, mouseY, event.hasControlDown())) {
			search.setFocused(false); return;
		}
		search.setFocused(page < 5 && searchRect(l).contains(mouseX, mouseY));
		if (search.isFocused()) { search.mouseClicked(new MouseButtonEvent(mouseX, mouseY, event.buttonInfo()), doubleClick); return; }
		if (page == 0) for (int i = 0; i < 4; i++) if (mapToolRect(l.map, i).contains(mouseX, mouseY)) {
			if (i < 2) recenterMap(i == 1);
			else mapZoom = Math.clamp(mapZoom + (i == 2 ? -0.75 : 0.75), 1.5, 14.0);
			return;
		}
		for (int i = 0; i < 7; i++) if (quickCommandRect(l, i).contains(mouseX, mouseY)) {
			if (selected.isEmpty()) { notifyAction("対象を選択してください"); return; }
			if (i == 0) {
				armedWaypoint = !armedWaypoint;
				captureDraftTargets();
				pendingMapX = pendingMapY = Double.NaN;
				armedRoute = false;
				armedAttack = false;
				armedWork = FieldOperationType.NONE;
				armedGuard = false;
				page = 0;
			} else if (i == 1) {
				page = 0;
				inspectorScroll = Math.min(323, Math.max(0, inspectorHeight() - l.right.h));
			} else if (i == 2) {
				armedRoute = !armedRoute;
				seedRouteDraft(drones);
				armedGuard = false;
				armedWork = FieldOperationType.NONE;
				armedWaypoint = false;
				armedAttack = false;
				pendingMapX = pendingMapY = Double.NaN;
				page = 0;
			} else if (i == 3) {
				page = 1;
				selectionMode = SelectionMode.GROUP;
				wingMapMode = false;
			} else if (i == 4 && availability.hasSelection()) execute(3);
			else if (i == 5 && availability.hasSelection()) execute(2);
			else if (i == 6 && availability.hasSelection()) execute(0);
			return;
		}
		if (page >= 5 || mouseY >= l.bottomTop) return;
		if (page == 0 && pendingTaskPoint != null && (armedGuard || armedWork != FieldOperationType.NONE)) {
			if (waypointCancelRect(l.map).contains(mouseX, mouseY)) {
				pendingTaskPoint = null; armedGuard = false; armedWork = FieldOperationType.NONE;
				notifyAction("作業・警戒の下書きを取り消しました"); return;
			}
			if (waypointApplyRect(l.map).contains(mouseX, mouseY)) {
				double targetX = l.map.x + l.map.w / 2 + (pendingTaskPoint.getX() - mapCenterX) * mapZoom;
				double targetY = l.map.y + l.map.h / 2 + (pendingTaskPoint.getZ() - mapCenterZ) * mapZoom;
				if (armedGuard) issueSecurityPatrol(l, targetX, targetY);
				else issueFieldOperation(l, targetX, targetY);
				if (transmitted > 0 || (armedWork == FieldOperationType.NONE && !armedGuard)) pendingTaskPoint = null;
				return;
			}
		}
		if (page == 0 && armedWaypoint && waypointApplyRect(l.map).contains(mouseX, mouseY)
			&& !Double.isNaN(pendingMapX)) {
			if (!confirmDraftTargets()) return;
			if (pendingWorldPoint != null) issueWaypointAt(pendingWorldPoint.getX(), pendingWorldPoint.getZ());
			armedWaypoint = false;
			pendingMapX = pendingMapY = Double.NaN;
			return;
		}
		if (page == 0 && armedWaypoint && waypointCancelRect(l.map).contains(mouseX, mouseY)) {
			armedWaypoint = false;
			pendingMapX = pendingMapY = Double.NaN;
			notifyAction("目的地変更を取り消しました");
			return;
		}
		if (page == 0 && armedAttack && l.map.contains(mouseX, mouseY) && mouseY > l.map.y + 29) {
			TacticalContactPayload.Row target = mapEntityAt(l.map, mouseX, mouseY, mapEntities());
			if (target == null) {
				notifyAction("敵対モブのマーカーを選択してください");
				return;
			}
			issueAttack(target.entityId());
			armedAttack = false;
			return;
		}
		if (page == 0 && armedWaypoint && !doubleClick && l.map.contains(mouseX, mouseY)
			&& mouseY > l.map.y + 60) {
			pendingMapX = mouseX;
			pendingMapY = mouseY;
			pendingWorldPoint = worldPoint(l.map, mouseX, mouseY);
			return;
		}
		if (page == 0 && !armedWaypoint && !armedRoute && !armedGuard && !armedAttack
			&& armedWork == FieldOperationType.NONE && !doubleClick && l.map.contains(mouseX, mouseY)
			&& (selectionMode == SelectionMode.UNIT
				? selectUnitAt(l.map, drones, mouseX, mouseY, event.hasControlDown() || event.hasShiftDown())
				: selectOperationalWingAt(l.map, drones, mouseX, mouseY, event.hasControlDown() || event.hasShiftDown()))) return;
		for (int i = 0; i < 2; i++) if (rosterFilterRect(l, i).contains(mouseX, mouseY)) {
			if (i == 0) roleFilter = (roleFilter + 2) % 7 - 1;
			else sortMode = (sortMode + 1) % 4;
			unitScroll = 0; return;
		}
		if (page == 0 && routeToggleRect(l.map).contains(mouseX, mouseY)) {
			armedRoute = !armedRoute;
			seedRouteDraft(drones);
			armedWaypoint = false;
			pendingMapX = pendingMapY = Double.NaN;
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
			routeDraft.clear(); routeStartIndex = -1;
			notifyAction("巡回ルート編集を取り消しました");
			return;
		}
		if (page == 0 && armedRoute && handleRouteEditor(l.map, mouseX, mouseY)) return;
		if (page == 0 && armedRoute && !doubleClick && l.map.contains(mouseX, mouseY) && mouseY > l.map.y + 60) {
			if ((relocateRoutePoint || event.hasShiftDown()) && routePointIndex >= 0 && routePointIndex < routeDraft.size()) {
				routeDraft.set(routePointIndex, worldPoint(l.map, mouseX, mouseY));
				relocateRoutePoint = false;
				return;
			}
			addPatrolPoint(l, mouseX, mouseY);
			return;
		}
		if (page == 0 && !armedRoute && !armedWaypoint
			&& (doubleClick || armedGuard || armedWork != FieldOperationType.NONE)
			&& l.map.contains(mouseX, mouseY)) {
			if (armedGuard) {
				pendingTaskPoint = worldPoint(l.map, mouseX, mouseY);
				return;
			}
			if (armedWork != FieldOperationType.NONE) {
				pendingTaskPoint = worldPoint(l.map, mouseX, mouseY);
				return;
			}
			TacticalContactPayload.Row target = mapEntityAt(l.map, mouseX, mouseY, mapEntities());
			if (target == null) {
				armedWaypoint = true;
				captureDraftTargets();
				pendingMapX = mouseX;
				pendingMapY = mouseY;
				pendingWorldPoint = worldPoint(l.map, mouseX, mouseY);
			}
			else issueTracking(target.entityId());
			return;
		}
		if (unitFirstRect(l).contains(mouseX, mouseY)) { unitScroll = 0; return; }
		if (unitUpRect(l).contains(mouseX, mouseY)) { unitScroll -= unitCapacity(l); return; }
		if (unitDownRect(l).contains(mouseX, mouseY)) { unitScroll += unitCapacity(l); return; }
		if (unitLastRect(l).contains(mouseX, mouseY)) { unitScroll = Integer.MAX_VALUE; return; }
		for (int i = 0; i < 5; i++) {
			if (!tabRect(l, i).contains(mouseX, mouseY)) continue;
			if (i == 4) { selected.clear(); return; }
			if (i == 3) { selectAllReceived(drones); return; }
			if (i == 2) {
				List<DroneEntity> visible = visibleRoster(drones);
				if (selectionMode == SelectionMode.GROUP && wingMapMode)
					visible = operationalWings(visible).stream().flatMap(wing -> wing.members.stream()).toList();
				visible.forEach(drone -> selected.add(drone.getId()));
				return;
			}
			selectionMode = SelectionMode.values()[i];
			unitScroll = 0;
			wingBoardScroll = 0;
			wingMapMode = selectionMode == SelectionMode.GROUP && page != 1;
			return;
		}
		if (page == 1
			&& beginWingCardInteraction(l, drones, mouseX, mouseY)) return;
		List<DroneEntity> displayed = visibleRoster(drones);
		List<FleetLinkPayload.Unit> remoteDisplayed = visibleRemoteRoster(displayed);
		List<OperationalWing> wings = operationalWings(displayed);
		int rowCount = selectionMode == SelectionMode.GROUP && wingMapMode ? wings.size() : displayed.size() + remoteDisplayed.size();
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
			if (clickedIndex >= displayed.size()) {
				FleetLinkPayload.Unit unit = remoteDisplayed.get(clickedIndex - displayed.size());
				boolean toggle = event.hasControlDown()
					|| selectionToggleRect(unitRect(l, visible)).contains(mouseX, mouseY);
				if (!toggle) selected.clear();
				if (toggle && selected.contains(unit.entityId())) selected.remove(unit.entityId());
				else selected.add(unit.entityId());
				selectionAnchor = unit.id();
				return;
			}
			DroneEntity drone = displayed.get(clickedIndex);
			{
				boolean toggle = event.hasControlDown()
					|| selectionToggleRect(unitRect(l, visible)).contains(mouseX, mouseY);
				if (event.hasShiftDown() && selectionAnchor != null) {
					int anchorIndex = -1;
					for (int index = 0; index < displayed.size(); index++)
						if (displayed.get(index).getUUID().equals(selectionAnchor)) { anchorIndex = index; break; }
					if (!event.hasControlDown()) selected.clear();
					if (anchorIndex < 0) selected.add(drone.getId());
					else for (int index = Math.min(anchorIndex, clickedIndex);
						index <= Math.max(anchorIndex, clickedIndex); index++) selected.add(displayed.get(index).getId());
				} else {
					if (!toggle) selected.clear();
					if (toggle && selected.contains(drone.getId())) selected.remove(drone.getId());
					else selected.add(drone.getId());
					selectionAnchor = drone.getUUID();
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
		if (!l.right.contains(mouseX, mouseY)) return;
		if (page != 3 && radiusEditorRect(l).contains(mouseX, mouseY)) {
			if (armedGuard || armedWork != FieldOperationType.NONE) openRadiusEditor();
			return;
		}
		if (page != 3 && cargoEditorRect(l).contains(mouseX, mouseY)) {
			openCargoEditor(drones);
			return;
		}
		for (int i = 0; page < 5 && i < ACTIONS.length; i++) {
			if (commandRect(l, i).contains(mouseX, mouseY)) {
				if (availability.hasSelection()) execute(i);
				return;
			}
		}
		for (int i = 0; page < 5 && i < WORK_TYPES.length; i++) {
			if (!workRect(l, i).contains(mouseX, mouseY)) continue;
			if (!autoOperations && !availability.workEnabled(WORK_TYPES[i])) {
				notifyAction(availability.workBlockReason(WORK_TYPES[i]));
				return;
			}
			armedWork = armedWork == WORK_TYPES[i] ? FieldOperationType.NONE : WORK_TYPES[i];
			pendingTaskPoint = null;
			captureDraftTargets();
			armedRoute = false;
			armedWaypoint = false;
			armedAttack = false;
			pendingMapX = pendingMapY = Double.NaN;
			routeDraft.clear(); routeStartIndex = -1;
			page = 0;
			armedGuard = false;
			return;
		}
		if (page < 5 && autoOperationsRect(l).contains(mouseX, mouseY)) {
			autoOperations = !autoOperations;
			notifyAction(autoOperations
				? "自動編成: 待機中の適格機から任務部隊を編成します"
				: "自動編成を解除しました。選択機を対象にします");
			return;
		}
		if (page < 5 && forceOverrideRect(l).contains(mouseX, mouseY)) {
			if (selected.isEmpty()) { notifyAction("強制介入する機体を選択してください"); return; }
			forceOverride = !forceOverride;
			notifyAction(forceOverride
				? "強制介入 ON / 次の命令は現在任務・充填・照射を中断します"
				: "強制介入 OFF / 通常の任務優先度を使用します");
			return;
		}
		if (page != 3 && attackTargetRect(l).contains(mouseX, mouseY)) {
			boolean securitySelected = availability(drones).securityEnabled();
			if (!securitySelected) { notifyAction("攻撃には警備機を選択してください"); return; }
			armedAttack = !armedAttack;
			armedWaypoint = armedRoute = armedGuard = false;
			armedWork = FieldOperationType.NONE;
			page = 0;
			notifyAction(armedAttack ? "地図上の敵対モブを選択してください" : "攻撃対象指定を解除しました");
			return;
		}
		if (page < 5 && workRect(l, WORK_TYPES.length).contains(mouseX, mouseY)) {
			if (!availability.securityEnabled()) {
				notifyAction("警戒には警備機が必要です");
				return;
			}
			armedGuard = !armedGuard;
			pendingTaskPoint = null;
			captureDraftTargets();
			armedRoute = false;
			armedWaypoint = false;
			armedAttack = false;
			pendingMapX = pendingMapY = Double.NaN;
			routeDraft.clear(); routeStartIndex = -1;
			page = 0;
			armedWork = FieldOperationType.NONE;
			return;
		}
		if (page < 5 && modulePanelVisible(l)) {
			if (capacityUpgradeRect(l).contains(mouseX, mouseY) && selected.size() == 1) {
				DroneEntity unit = drones.stream().filter(d -> selected.contains(d.getId())).findFirst().orElse(null);
				if (unit != null && unit.role() == DroneRole.SECURITY && unit.isDocked()
					&& unit.capacityTier() < jp.morrowgear.drone.PayloadCapacity.MAX_TIER) {
					sendDroneCommand(new DroneCommandPayload(unit.getId(), "capacity_upgrade:" + unit.capacityTier()));
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
					notifyAction("武装指定には警備機を選択してください");
					return;
				}
				UUID commandId = UUID.randomUUID();
				for (int id : selected) sendDroneCommand(commandId, id,
					"weapon_module:" + WEAPON_LOADOUTS[i].id());
				notifyCommandResult();
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
				UUID commandId = UUID.randomUUID();
				for (int id : selected) sendDroneCommand(commandId, id, "module:" + MODULE_ROLES[i].id());
					notifyCommandResult();
				return;
			}
		}
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double deltaX, double deltaY) {
		if (panning) { mapCenterX -= deltaX / dashboardScale() / mapZoom; mapCenterZ -= deltaY / dashboardScale() / mapZoom; return; }
		if (draggedEntity < 0) return;
		dragX = event.x() / dashboardScale();
		dragY = event.y() / dashboardScale();
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		transmitted = suppressed = 0; transmitProblem = "";
		if (panning) { panning = false; return; }
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
			if (page < 5 && l.right.contains(mouseX, mouseY))
				for (int i = 0; i < ACTIONS.length; i++) if (commandRect(l, i).contains(mouseX, mouseY)) execute(i);
			if (page == 0 && l.map.contains(mouseX, mouseY)) {
				TacticalContactPayload.Row target = mapEntityAt(l.map, mouseX, mouseY, mapEntities());
				if (target == null) {
					armedWaypoint = true; armedRoute = false; armedGuard = false; armedWork = FieldOperationType.NONE;
					captureDraftTargets();
					pendingMapX = mouseX; pendingMapY = mouseY;
					pendingWorldPoint = worldPoint(l.map, mouseX, mouseY);
				}
				else issueTracking(target.entityId());
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
		if (page < 5 && l.right.contains(x, y)) { inspectorScroll = Math.clamp(inspectorScroll + direction * 30, 0, Math.max(0, inspectorHeight() - l.right.h)); return true; }
		if (page >= 2 && (page >= 5 || l.map.contains(x, y))) {
			pageScroll = Math.max(0, pageScroll + direction); return true;
		}
		if (page == 1 && l.map.contains(x, y)) {
			wingBoardScroll += direction;
		}
		else if (l.map.contains(x, y)) {
			mapZoom = Math.max(1.5, Math.min(14.0, mapZoom + (scrollY > 0 ? 0.75 : -0.75)));
		}
		else if (x >= NAV_RAIL_WIDTH && x < l.leftWidth && y >= l.header + 50 && y < l.dockHeaderY - 5) unitScroll += direction;
		else if (x >= NAV_RAIL_WIDTH && x < l.leftWidth && y >= l.dockHeaderY) dockScroll += direction;
		else if (y >= l.bottomTop && x < virtualWidth() * 0.60) missionScroll += direction;
		else if (y >= l.bottomTop) cargoScroll += direction;
		else return false;
		return true;
	}

	@Override
	protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo button) {
		return button.button() >= 0 && button.button() <= 2;
	}

	boolean isPanning() { return panning; }

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		return search.isFocused() && search.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (search.isFocused() && search.keyPressed(event)) return true;
		if (event.key() == 256 && (armedWaypoint || armedRoute || armedGuard || armedAttack
			|| armedWork != FieldOperationType.NONE)) {
			armedWaypoint = false;
			armedRoute = false;
			armedGuard = false;
			armedAttack = false;
			armedWork = FieldOperationType.NONE;
			pendingMapX = pendingMapY = Double.NaN;
			routeDraft.clear(); routeStartIndex = -1;
			notifyAction("コマンド入力を取り消しました");
			return true;
		}
		return super.keyPressed(event);
	}

	private void selectAllReceived(List<DroneEntity> drones) {
		selected.clear();
		drones.forEach(drone -> selected.add(drone.getId()));
		FleetLinkClient.units(client).forEach(unit -> selected.add(unit.entityId()));
	}

	private void execute(int index) {
		if (selected.isEmpty()) return;
		if (ACTIONS[index].equals("decommission") && client.level != null) {
			long now = client.level.getGameTime();
			if (now >= confirmStoreUntil || !selected.equals(confirmStoreSelection)) {
				confirmStoreUntil = now + 60L;
				confirmStoreSelection = Set.copyOf(selected);
				notifyAction("格納すると機体はアイテム化されます。格納をもう一度押してください");
				return;
			}
		}
		confirmStoreUntil = 0;
		confirmStoreSelection = Set.of();
		if (ACTIONS[index].equals("follow")) {
			issueFollowFormation();
			notifyCommandResult();
			return;
		}
		UUID commandId = UUID.randomUUID();
		for (int id : selected) sendDroneCommand(commandId, id, ACTIONS[index]);
		notifyCommandResult();
	}

	private String actionLabel(int index, List<DroneEntity> drones) {
		return ControllerDisplayText.action(ACTIONS[index]);
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
		List<List<CommandUnit>> formations = selectedCommandFormations();
		String missionRoot = client.level.getGameTime() + "-follow-" + client.player.getId();
		UUID commandId = UUID.randomUUID();
		for (int wing = 0; wing < formations.size(); wing++) {
			List<CommandUnit> formation = formations.get(wing).stream()
				.sorted(Comparator.comparingDouble((CommandUnit drone) -> drone.position().distanceToSqr(client.player.position()))
					.thenComparing(CommandUnit::unitId)).toList();
			CommandUnit leader = formation.getFirst();
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			for (int index = 0; index < formation.size(); index++) {
				CommandUnit drone = formation.get(index);
				sendDroneCommand(commandId, drone.entityId(),
					"follow:" + missionId + ":" + formation.size() + ":" + index + ":" + leader.entityId());
			}
		}
	}

	private void issueFieldOperation(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || client.level == null || (!autoOperations && selected.isEmpty())
			|| armedWork == FieldOperationType.NONE) return;
		if (!autoOperations && !confirmDraftTargets()) return;
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int)Math.floor(mapCenterX + (mouseX - centerX) / mapZoom);
		int worldZ = (int)Math.floor(mapCenterZ + (mouseY - centerY) / mapZoom);
		int surfaceY = client.level.hasChunkAt(new BlockPos(worldX, 0, worldZ))
			? client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ)
			: client.player.getBlockY();
		int worldY = armedWork == FieldOperationType.ORE ? surfaceY - 16
			: surfaceY - 1;
		int radius = workRadii[workIndex(armedWork)];
		String orderId = Long.toString(client.level.getGameTime(), 36).toUpperCase()
			+ "-" + armedWork.id().toUpperCase() + "-" + worldX + "-" + worldZ;
		if (autoOperations) {
			if (!ClientPlayNetworking.canSend(FleetOperationPayload.TYPE)) {
				notifyAction("自動編成の通信リンクがありません / 未送信");
				return;
			}
			ClientPlayNetworking.send(new FleetOperationPayload(armedWork.id(), worldX, worldZ, radius));
			notifyAction("自動編成要求を送信 / " + WORK_LABELS[workIndex(armedWork)] + " / 結果未確認");
			armedWork = FieldOperationType.NONE;
			return;
		}
		UUID commandId = UUID.randomUUID();
		for (CommandUnit drone : selectedCommandUnits()) {
			if (!MissionAssignmentPolicy.allows(drone.role(),
				MissionAssignmentPolicy.MissionKind.FIELD_OPERATION)) continue;
			sendDroneCommand(commandId, drone.entityId(), "work:" + armedWork.id()
				+ ":" + worldX + ":" + worldY + ":" + worldZ + ":" + radius + ":" + orderId);
		}
		notifyCommandResult();
		armedWork = FieldOperationType.NONE;
	}

	private void issueSecurityPatrol(Layout layout, double mouseX, double mouseY) {
		if (!confirmDraftTargets()) return;
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int)Math.floor(mapCenterX + (mouseX - centerX) / mapZoom);
		int worldZ = (int)Math.floor(mapCenterZ + (mouseY - centerY) / mapZoom);
		int worldY = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
		int radius = guardRadius;
		String orderId = Long.toString(client.level.getGameTime(), 36).toUpperCase()
			+ "-GUARD-" + worldX + "-" + worldZ;
		UUID commandId = UUID.randomUUID();
		for (CommandUnit drone : selectedCommandUnits()) {
			if (!MissionAssignmentPolicy.allows(drone.role(),
				MissionAssignmentPolicy.MissionKind.SECURITY_PATROL)) continue;
			sendDroneCommand(commandId, drone.entityId(), "guard:"
				+ worldX + ":" + worldY + ":" + worldZ + ":" + radius + ":" + orderId);
		}
		notifyCommandResult();
		armedGuard = false;
	}

	private void issueWaypoint(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || selected.isEmpty()) return;
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int) Math.floor(mapCenterX + (mouseX - centerX) / mapZoom);
		int worldZ = (int) Math.floor(mapCenterZ + (mouseY - centerY) / mapZoom);
		issueWaypointAt(worldX, worldZ);
	}

	private void issueWaypointAt(int worldX, int worldZ) {
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		String missionRoot = client.level.getGameTime() + "-" + client.player.getId() + "-" + worldX + "-" + worldZ;
		List<List<CommandUnit>> formations = selectedCommandFormations();
		UUID commandId = UUID.randomUUID();
		int total = 0;
		for (int wing = 0; wing < formations.size(); wing++) {
			List<CommandUnit> formation = formations.get(wing);
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			for (int index = 0; index < formation.size(); index++) {
				CommandUnit drone = formation.get(index);
				sendDroneCommand(commandId, drone.entityId(),
					"move:" + worldX + ":" + worldZ + ":" + missionId + ":" + formation.size() + ":" + index);
				total++;
			}
		}
		notifyCommandResult();
	}

	private void issueTracking(int targetEntityId) {
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		String missionRoot = client.level.getGameTime() + "-track-" + targetEntityId;
		List<List<CommandUnit>> formations = selectedCommandFormations();
		UUID commandId = UUID.randomUUID();
		int total = 0;
		for (int wing = 0; wing < formations.size(); wing++) {
			List<CommandUnit> formation = formations.get(wing);
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			for (int index = 0; index < formation.size(); index++) {
				CommandUnit drone = formation.get(index);
				sendDroneCommand(commandId, drone.entityId(),
					"track:" + targetEntityId + ":" + missionId + ":" + formation.size() + ":" + index);
				total++;
			}
		}
		notifyCommandResult();
	}

	private void issueAttack(int targetEntityId) {
		if (client.player == null || client.level == null || selected.isEmpty()) return;
		List<CommandUnit> security = selectedCommandUnits().stream().filter(drone -> drone.role() == DroneRole.SECURITY)
			.sorted(Comparator.comparing(CommandUnit::unitId)).toList();
		if (security.isEmpty()) { notifyAction("攻撃可能な警備機が選択されていません"); return; }
		String missionId = client.level.getGameTime() + "-attack-" + targetEntityId;
		UUID commandId = UUID.randomUUID();
		for (int index = 0; index < security.size(); index++) {
			CommandUnit drone = security.get(index);
			sendDroneCommand(commandId, drone.entityId(), "attack:" + targetEntityId
				+ ":" + missionId + ":" + security.size() + ":" + index);
		}
		notifyCommandResult();
	}

	private void addPatrolPoint(Layout layout, double mouseX, double mouseY) {
		if (client.player == null || routeDraft.size() >= PatrolRoutePolicy.MAX_POINTS) {
			notifyAction("巡回地点は最大" + PatrolRoutePolicy.MAX_POINTS + "点です");
			return;
		}
		int centerX = layout.map.x + layout.map.w / 2;
		int centerY = layout.map.y + layout.map.h / 2;
		int worldX = (int) Math.floor(mapCenterX + (mouseX - centerX) / mapZoom);
		int worldZ = (int) Math.floor(mapCenterZ + (mouseY - centerY) / mapZoom);
		BlockPos point = new BlockPos(worldX, 0, worldZ);
		if (!routeDraft.isEmpty() && routeDraft.getLast().distSqr(point) < 4.0) {
			notifyAction("直前の地点と近すぎます");
			return;
		}
		routeDraft.add(point);
		notifyAction("巡回地点 " + routeDraft.size() + " / " + worldX + ", " + worldZ);
	}

	private void issuePatrolRoute() {
		if (!confirmDraftTargets()) return;
		if (client.player == null || client.level == null || selected.isEmpty() || routeDraft.isEmpty()) {
			notifyAction("巡回地点を1点以上設定してください");
			return;
		}
		List<List<CommandUnit>> formations = selectedCommandFormations();
		String missionRoot = "R" + Long.toString(client.level.getGameTime(), 36).toUpperCase();
		String encoded = PatrolRoutePolicy.encode(routeDraft);
		UUID commandId = UUID.randomUUID();
		int total = 0;
		for (int wing = 0; wing < formations.size(); wing++) {
			List<CommandUnit> formation = formations.get(wing);
			String missionId = wingMissionId(missionRoot, wing, formations.size());
			Vec3 formationOrigin = PatrolRoutePolicy.formationOrigin(
				formation.stream().map(CommandUnit::position).toList());
			int startIndex = routeStartIndex >= 0 && routeStartIndex < routeDraft.size()
				? routeStartIndex : PatrolRoutePolicy.nearestIndex(routeDraft, formationOrigin);
			int originX = (int)Math.floor(formationOrigin.x);
			int originZ = (int)Math.floor(formationOrigin.z);
			String longestAction = "patrol:" + missionId + ":" + formation.size() + ":" + (formation.size() - 1)
				+ ":" + startIndex + ":" + originX + ":" + originZ + ":" + encoded;
			if (!jp.morrowgear.drone.DroneCommandPolicy.acceptablePayload(longestAction)) {
				suppressed += formation.size(); transmitProblem = "経路情報が送信長の上限を超えています"; continue;
			}
			for (int index = 0; index < formation.size(); index++) {
				CommandUnit drone = formation.get(index);
				sendDroneCommand(commandId, drone.entityId(), "patrol:" + missionId
					+ ":" + formation.size() + ":" + index + ":" + startIndex
					+ ":" + originX + ":" + originZ + ":" + encoded);
				total++;
			}
		}
		notifyCommandResult();
		armedRoute = false;
		routeDraft.clear(); routeStartIndex = -1;
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
			int radius = preferences.markerRadius() + 2;
			g.fill(point.x - radius, point.y - radius, point.x + radius + 1, point.y + radius + 1, 0xE6081014);
			g.outline(point.x - radius, point.y - radius, radius * 2 + 1, radius * 2 + 1, index == activeIndex ? AMBER : color);
			g.centeredText(client.font, Integer.toString(index + 1), point.x, point.y - 4, TEXT);
		}
	}

	private void drawRouteControls(GuiGraphicsExtractor g, Rect map, int mouseX, int mouseY) {
		Rect toggle = routeToggleRect(map);
		g.fill(toggle.x, toggle.y, toggle.right(), toggle.bottom(), armedRoute ? 0xFF174139 : PANEL_ALT);
		g.outline(toggle.x, toggle.y, toggle.w, toggle.h, armedRoute ? GREEN : CYAN);
		g.centeredText(client.font, armedRoute ? "経路 " + routeDraft.size() + "/" + PatrolRoutePolicy.MAX_POINTS : "経路編集",
			toggle.x + toggle.w / 2, toggle.y + 6, armedRoute ? GREEN : TEXT);
		if (!armedRoute) return;
		Rect start = routeStartRect(map);
		Rect cancel = routeCancelRect(map);
		g.fill(start.x, start.y, start.right(), start.bottom(), start.contains(mouseX, mouseY) ? PANEL_RAISED : PANEL_ALT);
		g.outline(start.x, start.y, start.w, start.h, routeDraft.isEmpty() ? BORDER : GREEN);
		g.centeredText(client.font, "巡回を指示", start.x + start.w / 2, start.y + 6, routeDraft.isEmpty() ? MUTED : TEXT);
		g.fill(cancel.x, cancel.y, cancel.right(), cancel.bottom(), cancel.contains(mouseX, mouseY) ? PANEL_RAISED : PANEL_ALT);
		g.outline(cancel.x, cancel.y, cancel.w, cancel.h, RED);
		g.centeredText(client.font, "取消", cancel.x + cancel.w / 2, cancel.y + 6, TEXT);
		drawRouteEditor(g, map);
	}

	private TacticalUiPolicy.CommandAvailability availability(List<DroneEntity> drones) {
		List<DroneRole> roles = new ArrayList<>(drones.stream().filter(drone -> selected.contains(drone.getId()))
			.map(DroneEntity::role).toList());
		Set<java.util.UUID> localIds = drones.stream().map(DroneEntity::getUUID)
			.collect(java.util.stream.Collectors.toSet());
		FleetLinkClient.units(client).stream().filter(unit -> !localIds.contains(unit.id())
			&& selected.contains(unit.entityId())).map(unit -> DroneRole.byId(unit.role())).forEach(roles::add);
		return TacticalUiPolicy.availability(roles);
	}

	private static int workIndex(FieldOperationType type) {
		for (int index = 0; index < WORK_TYPES.length; index++) if (WORK_TYPES[index] == type) return index;
		return 0;
	}

	private static String compatibleRoleSummary(TacticalUiPolicy.CommandAvailability availability) {
		return "FIELD " + availability.field() + " / CARGO " + availability.cargo()
			+ " / SEC " + availability.security();
	}

	private static ControllerCommandWatch.Observation commandObservation(DroneEntity drone) {
		return new ControllerCommandWatch.Observation(drone.mode() + ":" + drone.groupId() + ":" + drone.role()
			+ ":" + drone.securityLoadout() + ":" + drone.capacityTier() + ":" + drone.operationalState()
			+ ":" + drone.patrolRoutePoints() + ":" + drone.isDocked()
			+ ":" + drone.hasCargoSource() + ":" + drone.cargoSource()
			+ ":" + drone.hasCargoTarget() + ":" + drone.cargoTarget(), drone.missionId());
	}

	private void sendDroneCommand(DroneCommandPayload payload) {
		DroneEntity drone = drones().stream().filter(d -> d.getId() == payload.entityId()).findFirst().orElse(null);
		FleetLinkPayload.Unit remote = drone == null ? FleetLinkClient.units(client).stream()
			.filter(unit -> unit.entityId() == payload.entityId()).findFirst().orElse(null) : null;
		if (drone == null && remote == null || !ClientPlayNetworking.canSend(DroneCommandPayload.TYPE)) {
			suppressed++;
			transmitProblem = "未受信または通信リンクなし";
			notifyAction("");
			return;
		}
		if (drone != null && !locallyEligible(drone)) { notifyCommandResult(); return; }
		String rawAction = payload.action();
		String outgoingAction = forceOverride && forceableAction(rawAction) ? "force:" + rawAction : rawAction;
		DroneCommandPayload outgoing = outgoingAction.equals(rawAction) ? payload
			: new DroneCommandPayload(payload.commandId(), payload.entityId(), outgoingAction);
		String[] fields = rawAction.split(":", 8);
		String mission = fields[0].equals("patrol") && fields.length > 1 ? fields[1]
			: fields[0].equals("move") && fields.length > 3 ? fields[3]
			: (fields[0].equals("track") || fields[0].equals("attack")) && fields.length > 2 ? fields[2] : "";
		java.util.UUID identity = drone != null ? drone.getUUID() : remote.id();
		ControllerCommandWatch.Observation observation = drone != null ? commandObservation(drone)
			: new ControllerCommandWatch.Observation(remote.status() + ":" + remote.groupId()
				+ ":" + remote.role() + ":" + remote.mode() + ":" + remote.docked(), remote.mission());
		if (!commandWatch.submit(outgoing.commandId(), identity, outgoingAction,
			observation, mission, System.currentTimeMillis())) {
			suppressed++;
			transmitProblem = "同じ指示の結果を確認中";
			notifyAction("");
			return;
		}
		menuRequestAt = 0;
		ClientPlayNetworking.send(outgoing);
		transmitted++;
		notifyAction("");
	}

	private void sendDroneCommand(UUID commandId, int entityId, String action) {
		sendDroneCommand(new DroneCommandPayload(commandId, entityId, action));
	}

	private static boolean forceableAction(String action) {
		if (action == null || action.equals("decommission")) return false;
		return action.equals("standby") || action.equals("return") || action.equals("dock")
			|| action.equals("orbit") || action.startsWith("move:") || action.startsWith("track:")
			|| action.startsWith("attack:") || action.startsWith("patrol:") || action.startsWith("follow:")
			|| action.startsWith("guard:") || action.startsWith("work:");
	}

	private void notifyCommandResult() {
		notifyAction(ControllerDispatchFeedback.describe(transmitted, suppressed, transmitProblem));
	}

	private void notifyAction(String message) {
		if (transmitted + suppressed > 0) {
			message = ControllerDispatchFeedback.describe(transmitted, suppressed, transmitProblem);
		}
		if (message != null && (message.contains("要求を送信") || message.startsWith("現地回収を要求"))) menuRequestAt = System.currentTimeMillis();
		transientNotice = message == null ? "" : message;
		transientNoticeUntil = client.level == null ? 0 : client.level.getGameTime() + 80L;
	}

	private List<DroneEntity> selectedMissionFormation() {
		return drones().stream().filter(drone -> selected.contains(drone.getId()))
			.sorted(Comparator.comparing(DroneEntity::groupId).thenComparing(DroneEntity::unitId)).toList();
	}

	private List<List<DroneEntity>> selectedMissionFormations() {
		List<DroneEntity> eligible = selectedMissionFormation().stream().filter(this::locallyEligible).toList();
		return jp.morrowgear.drone.MissionWingPolicy.partitionByWing(
			eligible, DroneEntity::groupId);
	}

	private List<CommandUnit> selectedCommandUnits() {
		Map<java.util.UUID, CommandUnit> result = new LinkedHashMap<>();
		for (DroneEntity drone : drones()) {
			if (!selected.contains(drone.getId()) || !locallyEligible(drone)) continue;
			result.put(drone.getUUID(), new CommandUnit(drone.getId(), drone.getUUID(), drone.unitId(),
				drone.groupId(), drone.role(), drone.position()));
		}
		for (FleetLinkPayload.Unit unit : FleetLinkClient.units(client)) {
			if (!selected.contains(unit.entityId()) || result.containsKey(unit.id())) continue;
			result.put(unit.id(), new CommandUnit(unit.entityId(), unit.id(), unit.unitId(), unit.groupId(),
				DroneRole.byId(unit.role()), new Vec3(unit.x(), unit.y(), unit.z())));
		}
		return result.values().stream().sorted(Comparator.comparing(CommandUnit::groupId)
			.thenComparing(CommandUnit::unitId)).toList();
	}

	private List<List<CommandUnit>> selectedCommandFormations() {
		return jp.morrowgear.drone.MissionWingPolicy.partitionByWing(selectedCommandUnits(), CommandUnit::groupId);
	}

	private boolean locallyEligible(DroneEntity drone) {
		if (ControllerCommandEligibility.maySubmit(drone.salvageState())) return true;
		suppressed++;
		transmitProblem = "回収物の搬送・整備中 / 完了後に再確認";
		return false;
	}

	private record CommandUnit(int entityId, java.util.UUID id, String unitId, String groupId,
		DroneRole role, Vec3 position) {}

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
			String mission = leader.hasActiveFieldOperation() ? WORK_LABELS[workIndex(leader.fieldOperationType())]
				: leader.mode() == DroneMode.STANDBY ? "待機" : statusLabel(leader);
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
			UUID commandId = UUID.randomUUID();
			members.forEach(d -> sendDroneCommand(commandId, d.getId(), "wing_create:WING-" + suffix));
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
			if (wingTransferRect(card).contains(mouseX, mouseY)) {
				if (selected.size() != 1) { notifyAction("移籍する1機を選択してください"); return true; }
				DroneEntity unit = drones.stream().filter(d -> selected.contains(d.getId())).findFirst().orElse(null);
				if (unit == null) return true;
				var result = WingMembershipPolicy.evaluate(unit.groupId(), wing.groupId, wing.members.size());
				if (result == WingMembershipPolicy.Result.ACCEPTED) {
					sendDroneCommand(new DroneCommandPayload(unit.getId(), "wing_join:" + wing.groupId));
					notifyAction("Wing移籍を送信 / 結果未確認");
				} else notifyAction(result == WingMembershipPolicy.Result.UNCHANGED ? "すでに所属しています" : "移籍先を確認してください / 定員8機");
				return true;
			}
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
			sendDroneCommand(new DroneCommandPayload(draggedEntity, "wing_create:WING-" + suffix));
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
			sendDroneCommand(new DroneCommandPayload(draggedEntity, "wing_join:" + target.groupId));
			return;
		}
		sendDroneCommand(new DroneCommandPayload(draggedEntity, "wing_leave"));
	}

	private List<OperationalWing> operationalWings(List<DroneEntity> drones) {
		return wingCards(drones).stream()
			.map(wing -> new OperationalWing("manual:" + wing.groupId, wing.groupId, wing.members,
				wing.leader, wing.recoveryCount, wing.averageBattery, wing.missionLabel))
			.toList();
	}

	private boolean selectOperationalWingAt(Rect map, List<DroneEntity> drones,
		double mouseX, double mouseY, boolean additive) {
		for (OperationalWing wing : operationalWings(drones)) {
			MapPoint point = mapPoint(map, wing.leader.getX(), wing.leader.getZ());
			if (point == null) continue;
			String title = fitText(wing.label + " / " + wing.members.size() + "U", 94);
			String mission = fitText(wing.missionLabel, 94);
			int width = Math.max(82, Math.max(client.font.width(title), client.font.width(mission)) + 22);
			int x = Math.max(map.x + 4, Math.min(point.x + 8, map.right() - width - 4));
			int y = Math.max(map.y + 62, Math.min(point.y - 13, map.bottom() - 31));
			boolean fullySelected = wing.members.stream().allMatch(member -> selected.contains(member.getId()));
			if ((!fullySelected || !new Rect(x, y, width, 29).contains(mouseX, mouseY))
				&& Math.hypot(mouseX - point.x, mouseY - point.y) > preferences.markerHitRadius()) continue;
			if (!additive) selected.clear();
			if (additive && fullySelected) wing.members.forEach(member -> selected.remove(member.getId()));
			else wing.members.forEach(member -> selected.add(member.getId()));
			selectionMode = SelectionMode.GROUP;
			wingMapMode = true;
			return true;
		}
		return false;
	}

	private TacticalContactPayload.Row mapEntityAt(Rect map, double mouseX, double mouseY,
		List<TacticalContactPayload.Row> entities) {
		TacticalContactPayload.Row closest = null;
		double closestDistance = preferences.markerHitRadius() - 1;
		for (TacticalContactPayload.Row entity : entities) {
			MapPoint point = mapPoint(map, entity.x(), entity.z());
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
		if (client.level == null || client.player == null) {
			selected.receive(Map.of());
			return List.of();
		}
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
		Map<Integer, java.util.UUID> identities = new LinkedHashMap<>();
		result.forEach(drone -> identities.put(drone.getId(), drone.getUUID()));
		FleetLinkClient.units(client).forEach(unit -> identities.put(unit.entityId(), unit.id()));
		selected.receive(identities);
		return result;
	}

	private List<FleetLinkPayload.Unit> visibleRemoteRoster(List<DroneEntity> visibleLocal) {
		Set<java.util.UUID> localIds = visibleLocal.stream().map(DroneEntity::getUUID)
			.collect(java.util.stream.Collectors.toSet());
		String query = search.getValue().strip().toLowerCase(java.util.Locale.ROOT);
		Comparator<FleetLinkPayload.Unit> order = switch (sortMode) {
			case 1 -> Comparator.comparing(unit -> DroneRole.byId(unit.role()).ordinal());
			case 2 -> Comparator.comparingInt(FleetLinkPayload.Unit::batteryPercent);
			case 3 -> Comparator.comparing(FleetLinkPayload.Unit::status);
			default -> Comparator.comparing(FleetLinkPayload.Unit::unitId);
		};
		return FleetLinkClient.units(client).stream()
			.filter(unit -> !localIds.contains(unit.id()))
			.filter(unit -> roleFilter < 0 || DroneRole.byId(unit.role()).ordinal() == roleFilter)
			.filter(unit -> (unit.name() + " " + unit.unitId() + " " + unit.groupId())
				.toLowerCase(java.util.Locale.ROOT).contains(query))
			.sorted(order.thenComparing(FleetLinkPayload.Unit::unitId)).toList();
	}

	private List<FleetLinkPayload.Unit> selectedRemoteUnits(List<DroneEntity> local) {
		Set<java.util.UUID> localIds = local.stream().map(DroneEntity::getUUID)
			.collect(java.util.stream.Collectors.toSet());
		return FleetLinkClient.units(client).stream().filter(unit -> !localIds.contains(unit.id())
			&& selected.contains(unit.entityId())).toList();
	}

	private List<DroneEntity> visibleRoster(List<DroneEntity> drones) {
		Comparator<DroneEntity> order = switch (sortMode) {
			case 1 -> Comparator.comparingInt(d -> d.role().ordinal());
			case 2 -> Comparator.comparingInt(DroneEntity::batteryPercent);
			case 3 -> Comparator.comparing(d -> d.operationalState().name());
			default -> Comparator.comparing(DroneEntity::unitId);
		};
		// A Wing row always represents its complete membership, even with a role filter.
		String query = search.getValue().strip().toLowerCase(java.util.Locale.ROOT);
		Set<String> matchingWings = new LinkedHashSet<>();
		for (DroneEntity drone : drones) if (matchesSearch(drone, query)) matchingWings.add(drone.groupId());
		return drones.stream().filter(d -> selectionMode == SelectionMode.GROUP && wingMapMode
			? matchingWings.contains(d.groupId())
			: (roleFilter < 0 || d.role().ordinal() == roleFilter) && matchesSearch(d, query))
			.sorted(order.thenComparing(DroneEntity::unitId)).toList();
	}

	private boolean matchesSearch(DroneEntity drone, String query) {
		return (drone.getName().getString() + " " + drone.unitId() + " " + drone.groupId())
			.toLowerCase(java.util.Locale.ROOT).contains(query);
	}

	private Rect pageTab(int index) {
		int position = 0;
		while (NAV_ORDER[position] != index) position++;
		return rect(ControllerLayoutPolicy.navigation(virtualHeight(), layout().header, position));
	}
	private Rect rosterFilterRect(Layout l, int index) {
		int contentWidth = l.leftWidth - NAV_RAIL_WIDTH;
		return new Rect(NAV_RAIL_WIDTH + 7 + index * (contentWidth - 14) / 2,
			l.header + 54, (contentWidth - 20) / 2, 21);
	}
	private Rect operationRow(Layout l, int index) { return new Rect(l.map.x + 12, l.map.y + 48 + index * 50, l.map.w - 24, 46); }
	private int operationCapacity(Layout l) { return Math.max(1, (l.map.h - 68) / 50); }

	private List<List<DroneEntity>> missionGroups(List<DroneEntity> drones) {
		Map<String, List<DroneEntity>> groups = new java.util.TreeMap<>();
		for (DroneEntity drone : drones) {
			String key = drone.missionId().isBlank() ? "待機 / " + drone.groupId() : drone.missionId();
			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(drone);
		}
		return List.copyOf(groups.values());
	}

	private void drawOperationsPage(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones, List<DockInfo> docks) {
		Rect area = l.map;
		g.fill(area.x, area.y, area.right(), area.bottom(), BG);
		HmiArt.icon(g, PAGE_ICONS[page], area.x + 12, area.y + 12, 19);
		g.text(client.font, PAGES[page], area.x + 40, area.y + 18, TEXT, true);
		if (page == 4) {
			drawIconButton(g, cargoLiftButton(l), "container", "コンテナ共同輸送", true);
			drawIconButton(g, recoveryOverviewButton(l), "chevron-right", "救助進捗一覧", true);
		}
		var missions = missionGroups(drones);
		int total = page == 3 ? docks.size() : page == 4 && client.level != null
			? PowerLostBeaconStore.snapshot(client.level.getGameTime()).size() : missions.size();
		pageScroll = clampScroll(pageScroll, total, operationCapacity(l));
		if (total == 0) g.text(client.font, page == 4 ? "救難信号なし" : "現在の情報なし", area.x + 18, area.y + 60, MUTED);
		for (int visible = 0; visible < operationCapacity(l) && pageScroll + visible < total; visible++) {
			Rect row = operationRow(l, visible);
			g.fill(row.x, row.y, row.right(), row.bottom(), PANEL);
			g.horizontalLine(row.x, row.right(), row.bottom(), BORDER);
			int index = pageScroll + visible;
			if (page == 3) {
				DockInfo dock = docks.get(index);
				String access = ControllerNearbyAccess.dockReason(client, BlockPos.of(dock.pos));
				drawCargoButton(g, dockRowButton(row, false), "現地操作", false, CYAN, access.isEmpty());
				drawCargoButton(g, dockRowButton(row, true), "補給網", false, CYAN, true);
				HmiArt.item(g, "dock_item", row.x + 4, row.y + 4, 38);
				g.text(client.font, fitText(dock.id, row.w - 140), row.x + 48, row.y + 6, TEXT);
				g.text(client.font, fitText(DockAllocationUiPolicy.dockStateLabel(dock.allocation)
					+ " / 電力 " + dock.status.powerPercent() + "%", row.w - 140), row.x + 48, row.y + 19,
					allocationColor(dock.allocation.state()));
				String allocation = DockAllocationUiPolicy.queueLabel(dock.allocation) + "  "
					+ DockAllocationUiPolicy.holdingLabel(dock.allocation) + "  "
					+ (dock.allocation.aircraftId().isBlank() ? "接続機なし" : shortId(dock.allocation.aircraftId()));
				g.text(client.font, fitText(access.isEmpty() ? allocation : access, row.w - 140), row.x + 48, row.y + 32, MUTED);
			} else if (page == 4) {
				var beacon = PowerLostBeaconStore.snapshot(client.level.getGameTime()).get(index);
				DroneEntity recovery = recoveryTarget(beacon, drones);
				String reason = recovery == null ? "未受信・別次元 / 現地へ接近" : ControllerNearbyAccess.entityReason(client, recovery);
				drawIconButton(g, recoveryButton(row), "archive", "現地回収", reason.isEmpty());
				drawIconButton(g, recoveryDetailButton(row), "chevron-right", recoveryDetailTarget(recovery, drones) == null ? "受信一覧" : "対象詳細", true);
				HmiArt.icon(g, "radio-tower", row.x + 8, row.y + 10, 24);
				g.text(client.font, fitText(beacon.unitId(), row.w - 152), row.x + 44, row.y + 6, AMBER);
				String destination = Math.round(beacon.position().x) + ", " + Math.round(beacon.position().y) + ", " + Math.round(beacon.position().z);
				g.text(client.font, fitText(destination + " / " + (ControllerDisplayText.sameDimension(beacon.dimension(), client.level.dimension().identifier().toString())
					? Math.round(beacon.position().distanceTo(client.player.position())) + "m" : "別次元"), row.w - 152), row.x + 44, row.y + 20, TEXT);
				g.text(client.font, fitText(reason.isEmpty() ? "現地回収可能 / 格納アイテムとして回収" : reason, row.w - 152), row.x + 44, row.y + 33, MUTED);
			} else {
				List<DroneEntity> members = missions.get(index);
				DroneEntity drone = members.getFirst();
				if (members.stream().anyMatch(d -> selected.contains(d.getId()))) g.fill(row.x, row.y, row.x + 2, row.bottom(), CYAN);
				HmiArt.role(g, drone.role(), row.x + 4, row.y + 5, 36);
				g.text(client.font, fitText((drone.missionId().isBlank() ? "待機" : drone.missionId()) + " / " + members.size() + "機", row.w - 54), row.x + 46, row.y + 6, TEXT);
				String states = members.stream().map(TacticalDashboard::statusLabel).distinct().collect(java.util.stream.Collectors.joining(" / "));
				g.text(client.font, fitText(states, row.w - 54), row.x + 46, row.y + 19, CYAN);
				g.text(client.font, fitText("着艦 " + members.stream().filter(DroneEntity::isDocked).count()
					+ " / 要回収 " + members.stream().filter(d -> d.recoveryLevel() > 0).count(), row.w - 54), row.x + 46, row.y + 32, MUTED);
			}
		}
		g.text(client.font, (total == 0 ? 0 : pageScroll + 1) + " / " + total, area.x + 14, area.bottom() - 14, MUTED);
	}

	private void drawUtilityPage(GuiGraphicsExtractor g, Layout l, List<DroneEntity> drones) {
		int contentX = NAV_RAIL_WIDTH + 20;
		g.text(client.font, PAGES[page], contentX, l.header + 18, TEXT, true);
		if (page == 8) { drawSupplyOverview(g, l); return; }
		if (page == 9) { drawCarrierOverview(g, l); return; }
		if (page == 6) {
			if (inspectedEquipment >= 0) { drawEquipmentDetail(g, l); return; }
			int availableWidth = virtualWidth() - contentX - 20;
			int columns = Math.max(1, availableWidth / 180), rows = Math.max(1, (l.bottomTop - l.header - 55) / 115);
			pageScroll = clampScroll(pageScroll, (ITEM_IDS.length + columns - 1) / columns, rows);
			for (int i = 0; i < rows * columns && pageScroll * columns + i < ITEM_IDS.length; i++) {
				String id = ITEM_IDS[pageScroll * columns + i];
				int x = contentX + i % columns * (availableWidth / columns), y = l.header + 44 + i / columns * 115;
				int w = availableWidth / columns - 8;
				g.fill(x, y, x + w, y + 105, PANEL);
				g.outline(x, y, w, 105, new Rect(x, y, w, 105).contains(hoverX, hoverY) ? CYAN : BORDER);
				HmiArt.item(g, id, x + (w - 64) / 2, y + 2, 64);
				String label = Component.translatable("item.morrowgear_drone." + id).getString();
				g.text(client.font, fitText(label, w - 12), x + 6, y + 70, TEXT);
				drawIconButton(g, new Rect(x + 6, y + 85, w - 12, 18), "panel-right-open", "詳細", true);
			}
			return;
		}
		if (page == 7) {
			String[] labels = {"バイザー表示", "近接マーカー距離", "通信履歴・字幕", "文字フォント（ゲーム共通）", "Controller文字・UI倍率", "地図マーカーサイズ"};
			for (int i = 0; i < labels.length; i++) {
				Rect row = settingRect(l, i);
				g.horizontalLine(row.x, row.right(), row.bottom(), BORDER);
				g.text(client.font, labels[i], row.x, row.y + 16, TEXT);
				Rect control = settingControl(l, i);
				if (i == 0 || i == 3) {
					boolean checked = i == 0 ? visorConfig.enabled : client.options.forceUnicodeFont().get();
					g.fill(control.x, control.y, control.right(), control.bottom(), control.contains(hoverX, hoverY) ? PANEL_RAISED : PANEL);
					g.outline(control.x + 3, control.y + 3, 20, 20, CYAN);
					if (checked) HmiArt.icon(g, "check", control.x + 5, control.y + 5, 16);
					g.text(client.font, i == 3 ? (checked ? "Unicode" : "標準") : (checked ? "表示する" : "表示しない"), control.x + 32, control.y + 9, TEXT);
				} else if (i == 1) {
					drawCargoButton(g, rangeStep(l, false), "-", false, CYAN, visorConfig.proximityRange > 0);
					drawCargoButton(g, rangeStep(l, true), "+", false, CYAN, visorConfig.proximityRange < 96);
					g.centeredText(client.font, visorConfig.proximityRange == 0 ? "非表示" : visorConfig.proximityRange + " m",
						control.x + control.w / 2, control.y + 9, TEXT);
				} else if (i == 2) drawIconButton(g, control, "clock", "報告履歴", true);
                else {
                    int value = i == 4 ? preferences.effectivePixelScale(maximumPixelScale()) : preferences.markerSize;
                    int maximum = i == 4 ? maximumPixelScale() : 2;
                    drawCargoButton(g, preferenceStep(l, i, false), "-", false, CYAN, value > (i == 4 ? 1 : 0));
                    drawCargoButton(g, preferenceStep(l, i, true), "+", false, CYAN, value < maximum);
                    if (i == 4) {
                        Rect auto = automaticScaleRect(l);
                        g.fill(auto.x, auto.y, auto.right(), auto.bottom(), auto.contains(hoverX, hoverY) ? PANEL_RAISED : PANEL);
                        g.outline(auto.x + 3, auto.y + 3, 20, 20, CYAN);
                        if (preferences.pixelScale == 0) HmiArt.icon(g, "check", auto.x + 5, auto.y + 5, 16);
                        g.text(client.font, "自動", auto.x + 29, auto.y + 9, TEXT);
                    }
                    String text = i == 4 ? (preferences.pixelScale > value ? preferences.pixelScale + "x / " : "") + currentPixelScale() + "x"
                        : switch (value) { case 0 -> "小"; case 2 -> "大"; default -> "標準"; };
                    g.centeredText(client.font, text, control.x + control.w / 2, control.y + 9, TEXT);
                }
			}
			return;
		}
		HmiArt.item(g, "tactical_visor", contentX, l.header + 50, 48);
		Rect history = visorHistoryButton(l);
		drawCargoButton(g, history, "戦闘履歴", false, CYAN, ControllerVisorHistory.available());
		if (!ControllerVisorHistory.available()) g.text(client.font, "履歴接続の初期化待ち", history.x, history.bottom() + 7, MUTED);
		g.text(client.font, "受信状況 / " + drones.size() + "機", contentX + 60, l.header + 60, CYAN);
		drawCargoButton(g, visorSettingsButton(l), "表示設定", false, CYAN, true);
		int y = l.header + 90;
		for (DroneEntity drone : drones.stream().filter(d -> selected.isEmpty() || selected.contains(d.getId())).limit(Math.max(1, (l.bottomTop - l.header - 95) / 40)).toList()) {
			HmiArt.role(g, drone.role(), 174, y, 28);
			g.text(client.font, shortId(drone.unitId()) + " / " + statusLabel(drone), 210, y + 2, TEXT);
			g.text(client.font, fitText("飛行電力 " + drone.batteryPercent() + "% / 損傷状態 " + drone.lowestSubsystemCondition() / 10 + "%", virtualWidth() - 250), 210, y + 17, MUTED);
			y += 40;
		}
	}

	private Rect visorSettingsButton(Layout l) { return new Rect(virtualWidth() - 330, l.header + 16, 150, 22); }
	private Rect equipmentBackRect(Layout l) { return new Rect(NAV_RAIL_WIDTH + 20, l.header + 42, 90, 22); }
	private Rect equipmentLinkRect(Layout l) { return new Rect(NAV_RAIL_WIDTH + 190, l.header + 142, 180, 22); }

	private DroneRole equipmentRole() {
		if (inspectedEquipment < 0) return null;
		return switch (ITEM_IDS[inspectedEquipment]) {
			case "field_drone_unit" -> DroneRole.FIELD;
			case "scout_module" -> DroneRole.SCOUT; case "cargo_module" -> DroneRole.CARGO;
			case "engineer_module" -> DroneRole.ENGINEER; case "salvage_module" -> DroneRole.SALVAGE;
			case "security_module", "autocannon_module", "laser_module", "missile_module" -> DroneRole.SECURITY;
			default -> null;
		};
	}

	private void drawEquipmentDetail(GuiGraphicsExtractor g, Layout l) {
		String id = ITEM_IDS[inspectedEquipment];
		int x = NAV_RAIL_WIDTH + 20;
		drawCargoButton(g, equipmentBackRect(l), "機器一覧へ", false, CYAN, true);
		HmiArt.item(g, id, x, l.header + 76, 128);
		int textX = x + 170, w = virtualWidth() - textX - 20;
		g.text(client.font, fitText(Component.translatable("item.morrowgear_drone." + id).getString(), w), textX, l.header + 78, TEXT);
		DroneRole role = equipmentRole();
		g.text(client.font, role == null ? "装備・補給品・材料" : "対応役割: " + roleLabel(role), textX, l.header + 99, CYAN);
		g.text(client.font, fitText(ControllerEquipmentData.conversion(id), w), textX, l.header + 120, MUTED);
		drawCargoButton(g, equipmentLinkRect(l), role == null ? "Dock・補給へ" : "対応機体一覧へ", false, CYAN, true);
		drawIconButton(g, equipmentRecipeRect(l), "chevron-right", "材料・レシピを開く", true);
	}
	private Rect equipmentRecipeRect(Layout l) { return new Rect(NAV_RAIL_WIDTH + 190, l.header + 174, 180, 22); }
	private Rect deliveryListRect(Layout l) { return new Rect(virtualWidth() - 210, l.header + 28, 190, 22); }

	private Rect visorHistoryButton(Layout l) { return new Rect(virtualWidth() - 170, l.header + 16, 150, 22); }
	private Rect settingRect(Layout l, int index) { return new Rect(NAV_RAIL_WIDTH + 20, l.header + 38 + index * 44, Math.min(640, virtualWidth() - NAV_RAIL_WIDTH - 40), 42); }
	private Rect settingControl(Layout l, int index) {
		Rect row = settingRect(l, index); return new Rect(row.right() - 160, row.y + 10, 160, 26);
	}

    private int maximumPixelScale() {
        double gui = client.getWindow().getGuiScale();
        return Math.clamp((int)Math.floor(Math.min(width * gui / 800.0, height * gui / 450.0)), 1, 4);
    }
    private int currentPixelScale() { return Math.max(1, Math.round(dashboardScale() * (float)client.getWindow().getGuiScale())); }
    private Rect automaticScaleRect(Layout l) {
        Rect control = settingControl(l, 4);
        return new Rect(control.x - 78, control.y, 72, 26);
    }
    private Rect preferenceStep(Layout l, int index, boolean increase) {
        Rect control = settingControl(l, index);
        return new Rect(increase ? control.right() - 26 : control.x, control.y, 26, 26);
    }
	private Rect rangeStep(Layout l, boolean increase) {
		Rect control = settingControl(l, 1); return new Rect(increase ? control.right() - 26 : control.x, control.y, 26, 26);
	}
	private void stepVisorRange(boolean increase) {
		int current = visorConfig.proximityRange;
		int next = increase ? switch (current) { case 0 -> 24; case 24 -> 48; default -> 96; }
			: switch (current) { case 96 -> 48; case 48 -> 24; default -> 0; };
		if (current != next) { visorConfig.proximityRange = next; visorConfig.save(); }
	}

	private BlockPos worldPoint(Rect map, double x, double y) {
		if (client.player == null) return BlockPos.ZERO;
		return new BlockPos((int)Math.floor(mapCenterX + (x - map.x - map.w / 2) / mapZoom),
			0, (int)Math.floor(mapCenterZ + (y - map.y - map.h / 2) / mapZoom));
	}

	private void seedRouteDraft(List<DroneEntity> drones) {
		captureDraftTargets();
		if (!armedRoute || !routeDraft.isEmpty()) return;
		drones.stream().filter(d -> selected.contains(d.getId()) && d.hasPatrolRoute()).findFirst()
			.ifPresent(d -> routeDraft.addAll(d.patrolRoutePoints()));
		routePointIndex = routeDraft.isEmpty() ? -1 : 0;
	}

	private Rect routeNumbersRect(Rect map) {
		Rect area = routeEditorRect(map);
		return new Rect(area.right() - 146, area.y + 48, 140, 18);
	}

	private void openRouteNumbers() {
		if (client.player == null) return;
		int editing = routeDraft.isEmpty() ? -1 : Math.clamp(routePointIndex, 0, routeDraft.size() - 1);
		BlockPos point = editing < 0 ? client.player.blockPosition() : routeDraft.get(editing);
		int px = client.player.getBlockX(), pz = client.player.getBlockZ();
		var fields = List.of(
			new ControllerNumberScreen.Field("X座標", point.getX(), px - 512, px + 512),
			new ControllerNumberScreen.Field("Z座標", point.getZ(), pz - 512, pz + 512),
			new ControllerNumberScreen.Field("開始地点", routeStartIndex + 1, 0, Math.max(1, routeDraft.size()), true));
		client.setScreenAndShow(new ControllerNumberScreen(client.gui.screen(), "巡回経路の下書き", fields, values -> {
			BlockPos changed = new BlockPos(values.get(0), 0, values.get(1));
			if (editing < 0) { routeDraft.add(changed); routePointIndex = 0; }
			else if (editing < routeDraft.size()) routeDraft.set(editing, changed);
			routeStartIndex = values.get(2) - 1;
		}));
	}

	private void openRadiusEditor() {
		boolean guard = armedGuard;
		FieldOperationType work = armedWork;
		int minimum = guard ? jp.morrowgear.drone.DroneStatePolicy.securityRadius(Integer.MIN_VALUE)
			: jp.morrowgear.drone.DroneStatePolicy.fieldRadius(work, Integer.MIN_VALUE);
		int maximum = guard ? jp.morrowgear.drone.DroneStatePolicy.securityRadius(Integer.MAX_VALUE)
			: jp.morrowgear.drone.DroneStatePolicy.fieldRadius(work, Integer.MAX_VALUE);
		int current = guard ? guardRadius : workRadii[workIndex(work)];
		client.setScreenAndShow(new ControllerNumberScreen(client.gui.screen(),
			(guard ? "警戒" : WORK_LABELS[workIndex(work)]) + "の範囲",
			List.of(new ControllerNumberScreen.Field("半径 (ブロック)", current, minimum, maximum)), values -> {
				int radius = values.getFirst();
				if (guard && jp.morrowgear.drone.DroneCommandPolicy.validSecurityRadius(radius)) guardRadius = radius;
				else if (!guard && jp.morrowgear.drone.DroneCommandPolicy.validFieldRadius(work, radius))
					workRadii[workIndex(work)] = radius;
			}));
	}

	private boolean confirmDraftTargets() {
		if (!draftTargets.equals(selected.identities())) {
			captureDraftTargets();
			notifyAction("対象が変更されました。対象数を確認してもう一度指示してください");
			return false;
		}
		if (selected.unavailableCount() > 0 || selected.isEmpty()) {
			notifyAction("未受信または未選択の対象があります。対象を選び直してください");
			return false;
		}
		if (!draftMissions.equals(currentDraftMissions())) {
			captureDraftTargets();
			notifyAction("編集中に任務が変わりました。対象の現在行動を確認して再度指示してください");
			return false;
		}
		return true;
	}

	private Map<java.util.UUID, String> currentDraftMissions() {
		Map<java.util.UUID, String> missions = new LinkedHashMap<>();
		for (DroneEntity drone : drones()) if (selected.contains(drone.getId()))
			missions.put(drone.getUUID(), drone.missionId() + ":" + drone.patrolRoutePoints());
		return missions;
	}

	private void captureDraftTargets() {
		draftTargets = selected.identities();
		draftMissions = currentDraftMissions();
	}

	private static ControllerLayoutPolicy.Box box(Rect rect) { return new ControllerLayoutPolicy.Box(rect.x, rect.y, rect.w, rect.h); }
	private static Rect rect(ControllerLayoutPolicy.Box box) { return new Rect(box.x(), box.y(), box.width(), box.height()); }
	private Rect routeEditorRect(Rect map) { return rect(ControllerLayoutPolicy.routeEditor(box(map))); }

	private void drawRouteEditor(GuiGraphicsExtractor g, Rect map) {
		Rect area = routeEditorRect(map);
		g.fill(area.x, area.y, area.right(), area.bottom(), PANEL);
		g.horizontalLine(area.x, area.right(), area.y, BORDER);
		routePointIndex = Math.max(0, Math.min(routePointIndex, routeDraft.size() - 1));
		String point = routeDraft.isEmpty() ? "経路の下書き: 地点なし" : "地点 " + (routePointIndex + 1) + "/" + routeDraft.size()
			+ "  X " + routeDraft.get(routePointIndex).getX() + "  Z " + routeDraft.get(routePointIndex).getZ()
			+ " / 開始 " + (routeStartIndex < 0 ? "最寄り" : routeStartIndex + 1);
		g.text(client.font, fitText(point, area.w - 12), area.x + 6, area.y + 7, CYAN);
		String[] labels = {"前の点", "次の点", "順序↑", "順序↓", "削除", "移動"};
		for (int i = 0; i < labels.length; i++) {
			Rect button = routeEditButton(map, i);
			drawCargoButton(g, button, labels[i], false, i == 4 ? RED : CYAN, !routeDraft.isEmpty());
		}
		g.text(client.font, "高度: 自動", area.x + 6, area.y + 53, MUTED);
		drawCargoButton(g, routeNumbersRect(map), "座標・開始点", false, CYAN, true);
		g.text(client.font, fitText(relocateRoutePoint ? "地点の移動先を選択" : "高度変更不可: 現行サーバーは水平経路のみ", area.w - 12), area.x + 6, area.bottom() - 12, MUTED);
	}

	private Rect routeEditButton(Rect map, int index) {
		return rect(ControllerLayoutPolicy.routeButton(box(map), index));
	}

	private boolean handleRouteEditor(Rect map, double x, double y) {
		if (!routeEditorRect(map).contains(x, y)) return false;
		if (routeNumbersRect(map).contains(x, y)) { openRouteNumbers(); return true; }
		if (routeDraft.isEmpty()) return true;
		routePointIndex = Math.max(0, Math.min(routePointIndex, routeDraft.size() - 1));
		for (int i = 0; i < 6; i++) if (routeEditButton(map, i).contains(x, y)) {
			if (i == 0) routePointIndex = Math.max(0, routePointIndex - 1);
			if (i == 1) routePointIndex = Math.min(routeDraft.size() - 1, routePointIndex + 1);
			if (i == 2 && routePointIndex > 0) {
				routeStartIndex = ControllerNumericPolicy.movedStart(routeStartIndex, routePointIndex, routePointIndex - 1);
				java.util.Collections.swap(routeDraft, routePointIndex, --routePointIndex);
			}
			if (i == 3 && routePointIndex + 1 < routeDraft.size()) {
				routeStartIndex = ControllerNumericPolicy.movedStart(routeStartIndex, routePointIndex, routePointIndex + 1);
				java.util.Collections.swap(routeDraft, routePointIndex, ++routePointIndex);
			}
			if (i == 4) {
				routeStartIndex = ControllerNumericPolicy.removedStart(routeStartIndex, routePointIndex);
				routeDraft.remove(routePointIndex);
			}
			if (i == 5) relocateRoutePoint = true;
			return true;
		}
		return true;
	}

	private void drawSupplyOverview(GuiGraphicsExtractor g, Layout l) {
		SupplyNetworkClient.request();
		var routes = SupplyNetworkClient.docks();
		int x = NAV_RAIL_WIDTH + 20;
		int available = virtualWidth() - x - 20;
		int listWidth = Math.min(300, available / 2);
		int capacity = Math.max(1, (l.bottomTop - l.header - 110) / 50);
		pageScroll = clampScroll(pageScroll, routes.size(), capacity);
		g.text(client.font, "遠隔状況 / 設定・在庫操作は現地Dock", x, l.header + 36, MUTED);
		if (routes.isEmpty()) g.text(client.font, "補給経路なし、または未受信", x, l.header + 66, MUTED);
		for (int i = 0; i < capacity && pageScroll + i < routes.size(); i++) {
			var dock = routes.get(pageScroll + i);
			int y = l.header + 58 + i * 50;
			g.fill(x, y, x + listWidth, y + 44, dock.dock() == inspectedSupplyDock ? PANEL_RAISED : PANEL);
			g.outline(x, y, listWidth, 44, dock.dock() == inspectedSupplyDock || new Rect(x, y, listWidth, 44).contains(hoverX, hoverY) ? CYAN : BORDER);
			HmiArt.icon(g, "chevron-right", x + listWidth - 22, y + 14, 16);
			g.text(client.font, DockBlockEntity.idFor(BlockPos.of(dock.dock())), x + 8, y + 7, TEXT);
			g.text(client.font, (dock.enabled() ? "自動補給 ON" : "自動補給 OFF") + " / 配送 " + dock.deliveries().size(), x + 8, y + 25, CYAN);
		}
		var detail = routes.stream().filter(d -> d.dock() == inspectedSupplyDock).findFirst().orElse(null);
		Rect open = nearbyDockButton(l);
		String access = inspectedSupplyDock == Long.MIN_VALUE ? "現地Dockの受信情報がありません"
			: ControllerNearbyAccess.dockReason(client, BlockPos.of(inspectedSupplyDock));
		drawIconButton(g, open, "dock", "現地Dock・補給経路を開く", access.isEmpty());
		g.text(client.font, fitText(access.isEmpty() ? "Dockの補給経路から設定を編集" : access, virtualWidth() - x - 20), x, open.bottom() + 7, MUTED);
		drawIconButton(g, deliveryListRect(l), "chevron-right", "個別配送一覧", detail != null);
		if (detail == null) return;
		int dx = x + listWidth + 20;
		int w = available - listWidth - 20;
		g.text(client.font, fitText(detail.source() == jp.morrowgear.drone.SupplyNetworkRegistry.Route.AUTO_SOURCE
			? "補給元 物流網から自動選択" : "補給元 " + BlockPos.of(detail.source()).toShortString(), w),
			dx, l.header + 60, TEXT);
		g.text(client.font, "実在庫 / 配送中 / 不足 (個)", dx, l.header + 80, MUTED);
		int y = l.header + 103;
		for (var stock : detail.stock()) {
			String name = switch (stock.rule().kind()) {
				case FUEL -> "燃料"; case GUN -> "機関砲弾"; case LASER -> "兵装電力";
				case MISSILE -> "ミサイル"; case REPAIR -> "修理材";
			};
			String amount = (stock.available() < 0 ? "不明" : stock.available()) + " / " + stock.incoming()
				+ " / " + (stock.missing() < 0 ? "不明" : stock.missing());
			g.text(client.font, fitText(name + "  " + amount, w), dx, y, stock.missing() > 0 ? AMBER : TEXT);
			g.text(client.font, "最低在庫 " + stock.rule().minimum() + " / 優先度 " + stock.rule().priority(), dx, y + 14, MUTED);
			y += 40;
		}
	}


    private String deliveryJumpReason(java.util.UUID identity, String dimension) {
        if (client.level == null || !client.level.dimension().identifier().toString().equals(dimension))
            return "別次元 / 現在位置の受信なし";
        return drones().stream().anyMatch(d -> d.isAlive() && d.getUUID().equals(identity))
            ? "" : "機体未受信・対象範囲外 / 最終位置には移動できません";
    }

    private ControllerDeliveryScreen.Snapshot deliverySnapshot(long dock) {
        SupplyNetworkClient.request();
        var received = SupplyNetworkClient.dock(dock);
        if (received.isEmpty()) return new ControllerDeliveryScreen.Snapshot("配送情報未受信 / 更新待ち", List.of());
        var detail = received.get();
        var receivedDrones = drones();
        var rows = detail.deliveries().stream().sorted(Comparator.comparing(d -> d.drone().toString())).map(delivery -> {
            var drone = receivedDrones.stream().filter(d -> d.isAlive() && d.getUUID().equals(delivery.drone())).findFirst();
            return new ControllerDeliveryScreen.Row(delivery.drone(), drone.map(d -> shortId(d.unitId())).orElse("機体詳細未受信"),
                ControllerSupplyText.status(delivery.status()),
                ControllerSupplyText.kind(delivery.kind()) + " / 配送残数 " + delivery.remaining() + " 個",
                drone.isPresent() ? "" : "機体未受信・対象範囲外 / 地図へ移動できません");
        }).toList();
        return new ControllerDeliveryScreen.Snapshot(DockBlockEntity.idFor(BlockPos.of(dock)) + " / "
            + ControllerSupplyText.status(detail.status()) + " / " + (rows.isEmpty() ? "受信中の配送なし" : "個別取消・再割当は未対応"), rows);
    }

	private void drawCarrierOverview(GuiGraphicsExtractor g, Layout l) {
		int x = NAV_RAIL_WIDTH + 20;
		g.text(client.font, "この入口は近接操作専用 / 遠方は母艦コンソール（128m以内）", x, l.header + 36, MUTED);
		if (client.level == null || client.player == null) return;
		var carriers = nearbyCarriers();
		if (carriers.isEmpty()) g.text(client.font, "周辺256mに母艦の受信情報がありません", x, l.header + 68, MUTED);
		int capacity = Math.max(1, (l.bottomTop - l.header - 65) / 70);
		pageScroll = clampScroll(pageScroll, carriers.size(), capacity);
		for (int i = 0; i < capacity && pageScroll + i < carriers.size(); i++) {
			var carrier = carriers.get(pageScroll + i);
			int y = l.header + 58 + i * 70;
			Rect open = carrierOpenButton(l, i);
			String access = ControllerNearbyAccess.entityReason(client, carrier);
			drawIconButton(g, open, "panel-right-open", "近接操作", access.isEmpty());
			HmiArt.item(g, "carrier_unit", x, y, 52);
			g.text(client.font, fitText(carrier.getName().getString() + " / " + carrier.blockPosition().toShortString(), virtualWidth() - x - 192), x + 62, y + 7, TEXT);
			g.text(client.font, "回収ブロック " + carrier.minedBlocks() + " / 収納品 " + carrier.capturedItems(), x + 62, y + 24, CYAN);
			g.text(client.font, fitText(access.isEmpty() ? carrier.beamActive() ? "作業装置 稼働中" : "作業装置 待機" : access, virtualWidth() - x - 82), x + 62, y + 41, MUTED);
			g.horizontalLine(x, virtualWidth() - 20, y + 62, BORDER);
		}
	}

	private List<jp.morrowgear.drone.carrier.CarrierEntity> nearbyCarriers() {
		if (client.level == null || client.player == null) return List.of();
		return client.level.getEntitiesOfClass(jp.morrowgear.drone.carrier.CarrierEntity.class,
			new AABB(client.player.blockPosition()).inflate(256), c -> c.isOwnedBy(client.player.getUUID())).stream()
			.sorted(Comparator.comparing(c -> c.getUUID().toString())).toList();
	}
	private Rect carrierOpenButton(Layout l, int row) { return new Rect(virtualWidth() - 130, l.header + 63 + row * 70, 110, 22); }
	private Rect nearbyDockButton(Layout l) { return new Rect(NAV_RAIL_WIDTH + 20, l.bottomTop - 48, 230, 22); }
	private Rect recoveryButton(Rect row) { return new Rect(row.right() - 96, row.y + 3, 88, 18); }
	private Rect recoveryDetailButton(Rect row) { return new Rect(row.right() - 96, row.y + 25, 88, 18); }
	private Rect recoveryOverviewButton(Layout l) { return new Rect(l.map.right() - 152, l.map.y + 10, 140, 24); }
	private Rect cargoLiftButton(Layout l) { return new Rect(l.map.right() - 310, l.map.y + 10, 150, 24); }
	private Rect dockRowButton(Rect row, boolean network) { return new Rect(row.right() - 82, row.y + (network ? 25 : 3), 76, 18); }
	private DroneEntity recoveryTarget(PowerLostBeaconStore.Beacon beacon, List<DroneEntity> drones) {
		if (client.level == null || !ControllerDisplayText.sameDimension(beacon.dimension(), client.level.dimension().identifier().toString())) return null;
		var matches = drones.stream().filter(d -> d.unitId().equals(beacon.unitId())).toList();
		return matches.size() == 1 ? matches.getFirst() : null;
	}
	private DroneEntity recoveryDetailTarget(DroneEntity target, List<DroneEntity> drones) {
		if (target == null) return null;
		// The read-only screen includes live LOST targets and targets assigned to a received salvage drone.
		return target.isPowerLost() || drones.stream().anyMatch(d -> d.role() == DroneRole.SALVAGE
			&& d.salvageState() != jp.morrowgear.drone.SalvageState.IDLE && d.salvageTargetEntityId() == target.getId())
			? target : null;
	}

	private boolean handlePageClick(Layout l, List<DroneEntity> drones, List<DockInfo> docks, double x, double y, boolean additive) {
		for (int i = 0; i < PAGES.length; i++) if (pageTab(i).contains(x, y)) {
			if (i == 10) { client.setScreenAndShow(new OperationHistoryScreen(client.gui.screen())); return true; }
			pageOffsets[page] = pageScroll;
			page = i; pageScroll = pageOffsets[i]; draggedEntity = -1;
			if (i == 8 && inspectedSupplyDock == Long.MIN_VALUE && !docks.isEmpty())
				inspectedSupplyDock = docks.stream().min(Comparator.comparingDouble(d -> BlockPos.of(d.pos).distSqr(client.player.blockPosition()))).orElseThrow().pos;
			if (i == 1) { selectionMode = SelectionMode.GROUP; wingMapMode = false; }
			else wingMapMode = selectionMode == SelectionMode.GROUP;
			return true;
		}
		if (page >= 5) {
			if (page == 6) {
				if (inspectedEquipment >= 0) {
					if (equipmentRecipeRect(l).contains(x, y)) {
                        var recipes = ControllerEquipmentData.recipes(client, ITEM_IDS[inspectedEquipment]);
                        client.setScreenAndShow(new ControllerRecipeScreen(client.gui.screen(), recipes));
                    } else if (equipmentBackRect(l).contains(x, y)) inspectedEquipment = -1;
					else if (equipmentLinkRect(l).contains(x, y)) {
						DroneRole role = equipmentRole();
						pageOffsets[6] = pageScroll; page = role == null ? 3 : 0; pageScroll = pageOffsets[page];
						if (role != null) { roleFilter = role.ordinal(); unitScroll = 0; search.setValue(""); selectionMode = SelectionMode.UNIT; wingMapMode = false; }
					}
					return true;
				}
				int contentX = NAV_RAIL_WIDTH + 20, available = virtualWidth() - contentX - 20;
				int columns = Math.max(1, available / 180), rows = Math.max(1, (l.bottomTop - l.header - 55) / 115);
				for (int row = 0; row < rows * columns && pageScroll * columns + row < ITEM_IDS.length; row++) {
					Rect card = new Rect(contentX + row % columns * (available / columns),
						l.header + 44 + row / columns * 115, available / columns - 8, 105);
					if (card.contains(x, y)) { inspectedEquipment = pageScroll * columns + row; return true; }
				}
				return true;
			}
			if (page == 5 && visorSettingsButton(l).contains(x, y)) {
				client.setScreenAndShow(new VisorSettingsScreen(client.gui.screen()));
				return true;
			}
			if (page == 5 && visorHistoryButton(l).contains(x, y)) {
				ControllerVisorHistory.open(client);
				return true;
			}
			if (page == 9) {
				var carriers = nearbyCarriers();
				for (int row = 0; row < Math.max(1, (l.bottomTop - l.header - 65) / 70); row++) {
					if (pageScroll + row >= carriers.size() || !carrierOpenButton(l, row).contains(x, y)) continue;
					var carrier = carriers.get(pageScroll + row);
					String reason = ControllerNearbyAccess.entityReason(client, carrier);
					if (reason.isEmpty() && ControllerNearbyAccess.interact(client, carrier)) notifyAction("母艦メニュー要求を送信 / サーバー応答待ち");
					else notifyAction(reason.isEmpty() ? "操作要求を送信できませんでした" : reason);
					return true;
				}
			}
			if (page == 8) {
                if (deliveryListRect(l).contains(x, y) && SupplyNetworkClient.dock(inspectedSupplyDock).isPresent()) {
                    long dock = inspectedSupplyDock;
                    var level = client.level;
                    String dimension = level == null ? "" : level.dimension().identifier().toString();
                    client.setScreenAndShow(new ControllerDeliveryScreen(client.gui.screen(), () -> deliverySnapshot(dock),
                        identity -> client.level != level ? "ワールド変更 / 元画面へ戻ってください"
                            : deliveryJumpReason(identity, dimension),
                        identity -> client.level == level && deliveryJumpReason(identity, dimension).isEmpty()
                            && focusReport(identity, dimension)));
                    return true;
                }
				if (nearbyDockButton(l).contains(x, y) && inspectedSupplyDock != Long.MIN_VALUE) {
					BlockPos pos = BlockPos.of(inspectedSupplyDock);
					String reason = ControllerNearbyAccess.dockReason(client, pos);
					if (reason.isEmpty() && ControllerNearbyAccess.openDock(client, pos)) notifyAction("Dockメニュー要求を送信 / サーバー応答待ち");
					else notifyAction(reason.isEmpty() ? "操作要求を送信できませんでした" : reason);
					return true;
				}
				var routes = SupplyNetworkClient.docks();
				int row = (int)((y - l.header - 58) / 50);
				int listWidth = Math.min(300, (virtualWidth() - NAV_RAIL_WIDTH - 40) / 2);
				if (x >= NAV_RAIL_WIDTH + 20 && x < NAV_RAIL_WIDTH + 20 + listWidth
					&& y >= l.header + 58 && row < Math.max(1, (l.bottomTop - l.header - 110) / 50)
					&& row + pageScroll < routes.size())
					inspectedSupplyDock = routes.get(row + pageScroll).dock();
			}
			if (page == 7) for (int i = 0; i < 6; i++) if (settingControl(l, i).contains(x, y)
				|| i == 4 && automaticScaleRect(l).contains(x, y)) {
				if (i == 0) { visorConfig.enabled = !visorConfig.enabled; visorConfig.save(); }
				else if (i == 1) {
					if (rangeStep(l, false).contains(x, y) && visorConfig.proximityRange > 0) stepVisorRange(false);
					if (rangeStep(l, true).contains(x, y) && visorConfig.proximityRange < 96) stepVisorRange(true);
				}
				else if (i == 2) client.setScreenAndShow(new OperationHistoryScreen(client.gui.screen()));
				else if (i == 3) { client.options.forceUnicodeFont().set(!client.options.forceUnicodeFont().get()); client.options.save(); }
                else {
                    int direction = preferenceStep(l, i, false).contains(x, y) ? -1 : preferenceStep(l, i, true).contains(x, y) ? 1 : 0;
                    boolean changed = i == 4 && automaticScaleRect(l).contains(x, y)
                        ? preferences.automaticScale(preferences.pixelScale != 0, maximumPixelScale())
                        : i == 4 ? preferences.stepPixelScale(direction, maximumPixelScale()) : preferences.stepMarkerSize(direction);
                    if (changed && !preferences.save()) notifyAction("表示は変更済み / 設定ファイルへ保存できませんでした");
                    return true;
                }
			}
			return true;
		}
		if (page < 2 || !l.map.contains(x, y)) return false;
		if (page == 4 && recoveryOverviewButton(l).contains(x, y)) {
			client.setScreenAndShow(new DroneRecoveryScreen(client.gui.screen()));
			return true;
		}
		if (page == 4 && cargoLiftButton(l).contains(x, y)) {
			client.setScreenAndShow(new ControllerCargoLiftScreen(client.gui.screen()));
			return true;
		}
		for (int i = 0; i < operationCapacity(l); i++) if (operationRow(l, i).contains(x, y)) {
			int index = pageScroll + i;
			var missions = missionGroups(drones);
			if (page == 2 && index < missions.size()) {
				selectionMode = SelectionMode.UNIT;
				if (!additive) selected.clear();
				missions.get(index).forEach(d -> selected.add(d.getId()));
			} else if (page == 3 && index < docks.size()) {
				DockInfo dock = docks.get(index);
				inspectedSupplyDock = dock.pos;
				if (dockRowButton(operationRow(l, i), true).contains(x, y)) {
					pageOffsets[3] = pageScroll; page = 8; pageScroll = pageOffsets[8]; return true;
				}
				if (dockRowButton(operationRow(l, i), false).contains(x, y)) {
					String reason = ControllerNearbyAccess.dockReason(client, BlockPos.of(dock.pos));
					if (reason.isEmpty() && ControllerNearbyAccess.openDock(client, BlockPos.of(dock.pos)))
						notifyAction("Dockメニュー要求を送信 / サーバー応答待ち");
					else notifyAction(reason.isEmpty() ? "操作要求を送信できませんでした" : reason);
					return true;
				}
				notifyAction(dock.id + " / " + DockAllocationUiPolicy.dockStateLabel(dock.allocation)
					+ " / " + DockAllocationUiPolicy.queueLabel(dock.allocation)
					+ " / " + DockAllocationUiPolicy.holdingLabel(dock.allocation));
			} else if (page == 4) {
				var beacons = client.level == null ? List.<PowerLostBeaconStore.Beacon>of() : PowerLostBeaconStore.snapshot(client.level.getGameTime());
				if (index >= beacons.size()) return true;
				if (recoveryButton(operationRow(l, i)).contains(x, y)) {
					DroneEntity target = recoveryTarget(beacons.get(index), drones);
					String reason = ControllerNearbyAccess.entityReason(client, target);
					if (reason.isEmpty() && ControllerNearbyAccess.interact(client, target)) notifyAction("現地回収を要求 / 結果未確認");
					else notifyAction(reason.isEmpty() ? "操作要求を送信できませんでした" : reason);
					return true;
				}
				DroneEntity target = recoveryDetailTarget(recoveryTarget(beacons.get(index), drones), drones);
				if (target != null) { selected.clear(); selected.add(target.getId()); selectionMode = SelectionMode.UNIT; }
				if (target == null) client.setScreenAndShow(new DroneRecoveryScreen(client.gui.screen()));
				else client.setScreenAndShow(new DroneRecoveryScreen(client.gui.screen(), target.getUUID()));
			}
			return true;
		}
		return true;
	}

	private List<TacticalContactPayload.Row> mapEntities() {
		return TacticalContactClient.contacts(client);
	}

	private List<DroneEntity> foreignDrones() {
		refreshForeignMapContacts();
		return cachedForeignDrones;
	}

	private List<ForeignDock> foreignDocks() {
		refreshForeignMapContacts();
		return cachedForeignDocks;
	}

	private void refreshForeignMapContacts() {
		if (client.level == null || client.player == null) {
			cachedForeignDrones = List.of(); cachedForeignDocks = List.of(); return;
		}
		long gameTime = client.level.getGameTime();
		if (foreignCacheLevel == client.level && gameTime < foreignRefreshAt) return;
		foreignCacheLevel = client.level;
		foreignRefreshAt = gameTime + 20L;
		cachedForeignDrones = List.copyOf(client.level.getEntitiesOfClass(DroneEntity.class,
			new AABB(client.player.blockPosition()).inflate(256), drone -> drone.isAlive()
				&& !drone.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())));
		List<ForeignDock> docks = new ArrayList<>();
		int cx = client.player.chunkPosition().x(), cz = client.player.chunkPosition().z();
		for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) {
			if (!client.level.hasChunk(x, z)) continue;
			for (BlockEntity blockEntity : client.level.getChunk(x, z).getBlockEntities().values()) {
				if (!(blockEntity instanceof DockBlockEntity dock)
					|| dock.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())) continue;
				String owner = dock.ownerName().isBlank() ? "他ユーザー" : dock.ownerName();
				docks.add(new ForeignDock(dock.getBlockPos().asLong(), owner));
			}
		}
		cachedForeignDocks = List.copyOf(docks);
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

	private void openCargoEditor(List<DroneEntity> drones) {
		var units = drones.stream().filter(d -> selected.contains(d.getId())).toList();
		if (units.isEmpty() || selected.unavailableCount() > 0 || units.stream().anyMatch(d -> d.role() != DroneRole.CARGO)) {
			notifyAction("受信中の輸送機だけを選択してください"); return;
		}
		Set<java.util.UUID> targets = selected.identities();
		var level = client.level;
		client.setScreenAndShow(new ControllerCargoRouteScreen(client.gui.screen(), targets.size(),
			() -> {
				SupplyNetworkClient.request();
				return SupplyNetworkClient.nodes().stream().map(node -> {
					BlockPos position = BlockPos.of(node.position());
					return new ControllerCargoRouteScreen.Place(node.registered() ? "登録済み / " + node.name() : node.name(),
						node.position(), position.getX() + "," + position.getY() + "," + position.getZ(),
						node.contents(), node.distance(), node.verticalOffset(), node.registered());
				}).toList();
			},
			(sourcePosition, targetPosition) -> {
				transmitted = suppressed = 0; transmitProblem = "";
				boolean sourceKnown = SupplyNetworkClient.nodes().stream().anyMatch(node -> node.position() == sourcePosition);
				boolean targetKnown = SupplyNetworkClient.nodes().stream().anyMatch(node -> node.position() == targetPosition);
				if (client.level != level || client.player == null || level == null || !sourceKnown || !targetKnown
					|| sourcePosition.equals(targetPosition))
					return "未送信 / 物流ノードの受信状態を再確認";
				var current = drones().stream().filter(d -> targets.contains(d.getUUID())).toList();
				if (current.size() != targets.size() || current.stream().anyMatch(d -> d.role() != DroneRole.CARGO))
					return "未送信 / 対象が未受信、または役割変更";
				UUID commandId = UUID.randomUUID();
				for (DroneEntity unit : current) {
					sendDroneCommand(commandId, unit.getId(), "cargo_source:" + sourcePosition);
					sendDroneCommand(commandId, unit.getId(), "cargo_target:" + targetPosition);
				}
				return ControllerDispatchFeedback.describe(transmitted, suppressed, transmitProblem);
			}));
	}

	private Layout layout() {
		TacticalLayoutPolicy.Metrics metrics = metrics();
		Rect right = new Rect(metrics.width() - metrics.rightWidth(), metrics.header(),
			metrics.rightWidth(), metrics.mapHeight());
		Rect map = new Rect(metrics.leftWidth(), metrics.header(), metrics.mapWidth(), metrics.mapHeight());
		return new Layout(metrics.header(), metrics.bottomTop(), metrics.leftWidth(), metrics.dockHeaderY(), map, right);
	}

	private TacticalLayoutPolicy.Metrics metrics() { return TacticalLayoutPolicy.calculateForGui(width, height, client.getWindow().getGuiScale(), preferences.pixelScale); }
	private float dashboardScale() { return metrics().scale(); }
	private int virtualWidth() { return metrics().width(); }
	private int virtualHeight() { return metrics().height(); }
	private int unitCapacity(Layout l) { return Math.max(1, (l.dockHeaderY - (l.header + 105) - 20) / 38); }
	private int dockCapacity(Layout l) { return Math.max(1, (l.bottomTop - (l.dockHeaderY + 17) - 8) / 26); }
	private int missionCapacity(Layout l) { return Math.max(1, (virtualHeight() - (l.bottomTop + 55)) / 25); }
	private Rect tabRect(Layout l, int index) {
		int contentWidth = l.leftWidth - NAV_RAIL_WIDTH;
		int w = contentWidth / 5;
		return new Rect(NAV_RAIL_WIDTH + index * w, l.header + 27,
			index == 4 ? l.leftWidth - (NAV_RAIL_WIDTH + index * w) : w, 23);
	}
	private Rect searchRect(Layout l) { return new Rect(NAV_RAIL_WIDTH + 7, l.header + 79, l.leftWidth - NAV_RAIL_WIDTH - 14, 20); }
	private Rect unitRect(Layout l, int visible) { return new Rect(NAV_RAIL_WIDTH + 7, l.header + 105 + visible * 38, l.leftWidth - NAV_RAIL_WIDTH - 14, 35); }
	private Rect selectionToggleRect(Rect row) { return new Rect(row.x + 6, row.y + 10, 8, 8); }
	private void drawSelectionToggle(GuiGraphicsExtractor g, Rect row, boolean active, int color) {
		Rect toggle = selectionToggleRect(row);
		g.outline(toggle.x, toggle.y, toggle.w, toggle.h, active ? CYAN : color);
		if (active) g.fill(toggle.x + 2, toggle.y + 2, toggle.right() - 2, toggle.bottom() - 2, CYAN);
	}
	private Rect dockRect(Layout l, int visible) { return new Rect(NAV_RAIL_WIDTH + 7, l.dockHeaderY + 17 + visible * 26, l.leftWidth - NAV_RAIL_WIDTH - 14, 22); }
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
	private Rect wingTransferRect(Rect card) { return new Rect(card.right() - 68, card.y + 4, 60, 20); }
	private Rect wingSlotRect(Rect card, int slot) {
		int gap = 5;
		int slotWidth = (card.w - 16 - gap) / 2;
		int column = slot % 2;
		int row = slot / 2;
		return new Rect(card.x + 8 + column * (slotWidth + gap), card.y + 49 + row * 22, slotWidth, 18);
	}

	private Rect inspectorButton(Layout l, int offset, int index, int columns) {
		int gap = 5, w = (l.right.w - 20 - gap * (columns - 1)) / columns;
		return new Rect(l.right.x + 10 + (index % columns) * (w + gap),
			l.right.y + offset + (index / columns) * 27 - inspectorScroll, w, 22);
	}
	private Rect commandRect(Layout l, int index) { return inspectorButton(l, 338, index, 2); }
	private Rect routeToggleRect(Rect map) { return new Rect(map.x + 10, map.y + 34, 76, 22); }
	private Rect routeStartRect(Rect map) { return new Rect(map.x + 92, map.y + 34, 62, 22); }
	private Rect routeCancelRect(Rect map) { return new Rect(map.x + 160, map.y + 34, 68, 22); }
	private Rect waypointApplyRect(Rect map) { return new Rect(map.right() - 154, map.bottom() - 55, 68, 22); }
	private Rect waypointCancelRect(Rect map) { return new Rect(map.right() - 80, map.bottom() - 55, 68, 22); }
	private Rect quickCommandRect(Layout l, int index) {
		return rect(ControllerLayoutPolicy.quickCommand(virtualWidth(), l.bottomTop, index));
	}
	private Rect workRect(Layout l, int index) { return inspectorButton(l, 437, index, 2); }
	private Rect autoOperationsRect(Layout l) { return inspectorButton(l, 492, 0, 2); }
	private Rect forceOverrideRect(Layout l) { return inspectorButton(l, 492, 1, 2); }
	private Rect attackTargetRect(Layout l) { return inspectorButton(l, 519, 0, 1); }
	private Rect cargoEditorRect(Layout l) { return inspectorButton(l, 546, 0, 1); }
	private Rect radiusEditorRect(Layout l) { return inspectorButton(l, 573, 0, 1); }
	private boolean modulePanelVisible(Layout l) { return page == 3; }
	private boolean dualModulePanelVisible(Layout l) { return page == 3; }
	private Rect moduleRect(Layout l, int index) { return inspectorButton(l, 535, index, 2); }
	private Rect capacityUpgradeRect(Layout l) { return inspectorButton(l, 679, 0, 1); }
	private Rect weaponModuleRect(Layout l, int index) { return inspectorButton(l, 623, index, 2); }

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
		g.fill(x, y, x + w, y + 22, BG);
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

	private static String rosterStatusLabel(DroneEntity drone) {
		if (drone.role() == DroneRole.SALVAGE && !drone.isPowerLost()
			&& drone.salvageState() != jp.morrowgear.drone.SalvageState.IDLE) return statusLabel(drone);
		return switch (drone.operationalState()) {
			case POWER_LOSS -> "電力喪失 / 要回収"; case DOCKED -> "着艦中"; case SERVICE_RTB -> "補給帰還中";
			case COMBAT -> "交戦中"; case EMERGENCY_INTERCEPT -> "緊急迎撃"; case REJOIN -> "任務に復帰中";
			case FIELD_OPERATION, CARGO_ROUTE, ENGINEER_SUPPORT -> statusLabel(drone);
			case SECURITY_PATROL -> "警戒中";
			case WAYPOINT -> drone.hasPatrolRoute() ? "巡回 " + (drone.patrolRouteIndex() + 1) + "/" + drone.patrolRouteSize() : "地点へ移動";
			case FOLLOW -> "追従中"; case RETURN -> drone.mode() == DroneMode.DOCK ? "Dock帰還中" : "集合中";
			case ORBIT -> "旋回中"; case STANDBY -> "待機";
		};
	}

	private static String roleLabel(DroneRole role) {
		return switch (role) {
			case FIELD -> "作業機"; case SCOUT -> "偵察機"; case CARGO -> "輸送機";
			case ENGINEER -> "整備機"; case SECURITY -> "警備機"; case SALVAGE -> "回収機";
		};
	}

	private static String statusLabel(DroneEntity drone) {
		if (drone.isPowerLost()) return "電力喪失 / 要回収";
		if (drone.isDocked()) return "Dock着艦中";
		if (drone.serviceReturnActive()) return "補給帰還中";
		if (drone.combatActive()) return "交戦中";
		if (drone.emergencyInterceptActive()) return "緊急迎撃";
		if (drone.hasPatrolRoute()) return "巡回 地点 " + (drone.patrolRouteIndex() + 1)
			+ "/" + drone.patrolRouteSize();
		if (drone.hasSecurityPatrol()) return drone.securityContacts() > 0 ? "警戒 / 接触 " + drone.securityContacts() : "地点警戒";
		if (drone.hasFieldOperation()) return switch (drone.fieldOperationState()) {
			case IDLE -> "作業待機"; case SCANNING -> "調査中"; case WAITING_DATA -> "調査情報待ち";
			case TRANSIT -> "作業地点へ移動"; case WORKING -> "作業中"; case COLLECTING -> "資源回収中";
			case DELIVERING -> "資源搬送中"; case PLANTING -> "植林中"; case COMPLETE -> "作業完了";
			case BLOCKED -> "作業停止"; case SCOUT_SURVEY -> "偵察調査"; case SCOUT_OVERWATCH -> "作業監視";
			case SCOUT_CARGO_ESCORT, GUARD_CARGO_ESCORT -> "輸送護衛";
			case ENGINEER_WAITING_INTEL -> "整備情報待ち"; case ENGINEER_LOCAL_SURVEY -> "現地調査";
			case CARGO_HOLD -> "輸送待機"; case GUARD_WORK_ESCORT -> "作業護衛";
			case ENGINEER_RECOVERY_OVERWATCH -> "回収監視"; case FIELD_DATA_RELAY -> "情報中継";
			case FIELD_MISSION_SUPPORT -> "任務支援";
		};
		if (drone.role() == DroneRole.CARGO && drone.cargoState() != jp.morrowgear.drone.CargoState.UNASSIGNED) {
			return switch (drone.cargoState()) {
				case UNASSIGNED -> "輸送未割当"; case TO_SOURCE -> "積込元へ移動"; case WAIT_SOURCE -> "積込元待機";
				case TO_TARGET -> "搬送先へ移動"; case WAIT_TARGET -> "搬送先待機";
				case QUEUE_SOURCE -> "積込順番待ち"; case LOADING_SOURCE -> "積込中";
				case QUEUE_TARGET -> "荷卸順番待ち"; case UNLOADING_TARGET -> "荷卸中";
			};
		}
		if (drone.role() == DroneRole.ENGINEER) return switch (drone.engineerState()) {
			case IDLE -> modeLabel(drone.mode()); case APPROACH -> "整備対象へ接近";
			case REPAIRING -> "修理中"; case MATERIAL_LOW -> "修理材不足";
		};
		if (drone.role() == DroneRole.SALVAGE) return switch (drone.salvageState()) {
			case IDLE -> modeLabel(drone.mode()); case INTERCEPT -> "回収対象へ接近"; case HOOK -> "回収接続";
			case HOIST -> "吊り上げ中"; case RETURN -> "回収搬送中"; case DELIVER -> "引渡し中"; case SERVICE -> "復旧待ち";
		};
		String defense = drone.defenseSlot() >= 0 ? "防衛配置 " + (drone.defenseSlot() + 1) + " / " : "";
		String recovery = drone.recoveryLevel() > 0 ? "回収段階 " + drone.recoveryLevel() + " / " : "";
		defense += recovery;
		if (drone.formationCatchUp()) defense += "隊列合流 / ";
		if (drone.mode() != DroneMode.WAYPOINT) return defense + modeLabel(drone.mode());
		if (drone.hasTrackingTarget()) {
			return defense + switch (drone.missionStage()) {
				case DroneEntity.MISSION_MUSTER -> "追跡集合";
				case DroneEntity.MISSION_MOVING -> "目標追従";
				case DroneEntity.MISSION_ORBIT -> "目標監視";
				case DroneEntity.MISSION_CONVERGING -> "合流";
				case DroneEntity.MISSION_ORBIT_ENTRY -> "旋回進入";
				default -> "目標任務";
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

	private static DroneResourceUiPolicy.Snapshot resourceSnapshot(DroneEntity drone) {
		int structure = Math.round(drone.getHealth() * 100.0f / Math.max(1.0f, drone.getMaxHealth()));
		return DroneResourceUiPolicy.assess(drone.role() == DroneRole.SECURITY,
			drone.securityLoadout(), drone.batteryPercent(), drone.weaponPowerPercent(),
			drone.gunAmmo(), drone.gunCapacity(), drone.missiles(), drone.missileCapacity(),
			drone.laserHeat(), structure, drone.lowestSubsystemCondition() / 10,
			drone.serviceReturnActive());
	}

	private static String resourceSummary(DroneEntity drone) {
		return resourceSnapshot(drone).metrics().stream()
			.filter(metric -> !metric.code().equals("STR") && !metric.code().equals("SYS"))
			.map(metric -> metric.code() + " " + metric.value())
			.collect(java.util.stream.Collectors.joining("  "));
	}

	private static int resourceColor(DroneResourceUiPolicy.Tone tone) {
		return switch (tone) {
			case NOMINAL -> GREEN;
			case CAUTION -> AMBER;
			case BLOCKED -> RED;
		};
	}

	private static String shortId(String id) { return ControllerDisplayText.shortUnitId(id); }
	private static String threatLabel(int score) {
		if (score >= 28) return "重大";
		if (score >= 14) return "高";
		if (score >= 6) return "警戒";
		return "低";
	}

	private void drawDiamond(GuiGraphicsExtractor g, int x, int y, int color) {
		int radius = preferences.markerRadius();
		for (int dy = -radius; dy <= radius; dy++) { int half = radius - Math.abs(dy); g.horizontalLine(x - half, x + half, y + dy, color); }
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
	private record ForeignDock(long pos, String owner) {}
	private record ContainerInfo(String label, long pos, int distance, String coordinate) {}
	private record MapPoint(int x, int y) {}
	private record WingCard(String groupId, List<DroneEntity> members, DroneEntity leader,
		int recoveryCount, int averageBattery, String missionLabel) {}
	private record OperationalWing(String id, String label, List<DroneEntity> members,
		DroneEntity leader, int recoveryCount, int averageBattery, String missionLabel) {}
}
