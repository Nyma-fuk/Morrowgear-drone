package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneResourceUiPolicy;
import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.MorrowgearMissileEntity;
import jp.morrowgear.drone.SolarServiceStationEntity;
import jp.morrowgear.drone.VisorHudPolicy;
import jp.morrowgear.drone.VisorCombatLedger;
import jp.morrowgear.drone.VisorCombatLayout;
import jp.morrowgear.drone.VisorAirspaceMapPolicy;
import jp.morrowgear.drone.network.FleetLinkPayload;
import jp.morrowgear.drone.carrier.CarrierEntity;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class VisorHudOverlay implements HudElement {
	private static final int CYAN = HmiArt.CYAN;
	private static final int GREEN = HmiArt.GREEN;
	private static final int AMBER = HmiArt.AMBER;
	private static final int RED = HmiArt.RED;
	private static final int WHITE = HmiArt.TEXT;
	private static final int MUTED = HmiArt.MUTED;
	private static final int PANEL = 0xD91A1E1C;
	private static final int PANEL_SOFT = 0xBD121514;
	private static final int LINE = 0xB9343C36;
	private static final int CACHE_TICKS = 5;
	private static final int FOCUS_DWELL_TICKS = 12;
	private static final float TEXT_SCALE = 2.0f;
	private static final int MAX_CONTACTS = 64;
	private static final int MAX_BEACONS = 3;
	private final VisorHudCache<Boolean> visibility = new VisorHudCache<>(128);
	private final VisorHudCache<Projection> projections = new VisorHudCache<>(128);
	private final Map<String, Object> tickMemo = new HashMap<>();
	private Object hudLevel, hudPlayer;
	private long memoTick = Long.MIN_VALUE;
	private int memoPin = -1;
	private String memoGroup = "";
	private Map<Integer, DroneEntity> rosterById = Map.of();

	private final VisorClientConfig config = VisorClientConfig.current();
	private List<DroneEntity> roster = List.of();
	private List<SolarServiceStationEntity> solarStations = List.of();
	private int cacheUntil;
	private boolean wornLastTick;
	private int bootTicks;
	private String commandGroup = "";
	private int pinnedEntityId = -1;
	private int focusEntityId = -1;
	private int focusCandidateId = -1;
	private int focusCandidateTicks;
	private final VisorCombatLedger combatLedger = new VisorCombatLedger();
	private final Map<java.util.UUID, LivingEntity> observedTargets = new LinkedHashMap<>();
	private java.util.UUID pinnedUuid;
	private java.util.UUID selectedFrontTarget;
	private final List<VisorCombatLayout.Box> occupiedLabels = new ArrayList<>();
	private int worldLabels;
	private Object airspaceTerrainLevel;
	private List<AirspaceTerrainPoint> airspaceTerrain = List.of();
	private long airspaceTerrainRefreshAt = Long.MIN_VALUE;
	private int airspaceTerrainCenterX = Integer.MIN_VALUE;
	private int airspaceTerrainCenterZ = Integer.MIN_VALUE;
	private int airspaceTerrainRangeKey = -1;
	private int renderWidth = 1;
	private int renderHeight = 1;

	void tick(Minecraft client) {
		ensureContext(client);
		if (client.player == null || client.level == null) return;
		boolean worn = visorWorn(client);
		if (worn && !wornLastTick) bootTicks = 7;
		wornLastTick = worn;
		if (bootTicks > 0) bootTicks--;
		if (!worn || !config.enabled) return;
		refreshRoster(client);
		updateFocus(client);
		if (client.player.tickCount % CACHE_TICKS == 0) updateCombatLedger(client);
	}

	void toggleEnabled(Minecraft client) {
		config.enabled = !config.enabled;
		config.save();
	}

	void toggleAirspaceMap(Minecraft client) {
		if (!visorWorn(client) || client.player == null) return;
		config.airspaceMapEnabled = !config.airspaceMapEnabled;
		config.save();
		client.player.sendOverlayMessage(Component.translatable(config.airspaceMapEnabled
			? "message.morrowgear_drone.airspace_map.on"
			: "message.morrowgear_drone.airspace_map.off"));
	}

	void enableAirspaceMapForAutomation() {
		config.airspaceMapEnabled = true;
	}

	int operationSubtitleY(Minecraft client, int boxHeight) {
		int guiHeight = client.getWindow().getGuiScaledHeight();
		int normal = Math.max(40, guiHeight - 112);
		if (!config.airspaceMapEnabled || !visorWorn(client)) return normal;
		float hudScale = config.hudScale(client.getWindow().getWidth(), client.getWindow().getHeight());
		int mapWidth = Math.round(client.getWindow().getWidth() / hudScale);
		int mapHeight = Math.round(client.getWindow().getHeight() / hudScale);
		VisorAirspaceMapPolicy.Panel panel = VisorAirspaceMapPolicy.panel(mapWidth, mapHeight);
		double physicalTop = panel.y() * hudScale;
		double guiScale = Math.max(1.0, client.getWindow().getGuiScale());
		int aboveMap = (int)Math.floor(physicalTop / guiScale) - Math.max(1, boxHeight) - 2;
		return Math.max(24, Math.min(normal, aboveMap));
	}

	void togglePin(Minecraft client) {
		if (!visorWorn(client)) return;
		pinnedEntityId = pinnedUuid != null ? -1 : focusEntityId;
		DroneEntity pinned = findById(pinnedEntityId);
		pinnedUuid = pinned == null ? null : pinned.getUUID();
	}

	void cycleContext(Minecraft client) {
		if (!visorWorn(client)) return;
		refreshRoster(client);
		if (!combatLedger.active().isEmpty()) {
			selectedFrontTarget = combatLedger.nextActive(selectedFrontTarget);
			tickMemo.clear();
			return;
		}
		selectedFrontTarget = null;
		List<String> groups = roster.stream().map(DroneEntity::groupId).filter(id -> !id.isBlank())
			.distinct().sorted().toList();
		if (groups.isEmpty()) {
			commandGroup = "";
			return;
		}
		int index = groups.indexOf(commandGroup);
		commandGroup = groups.get((index + 1) % groups.size());
	}

	void cycleRange(Minecraft client) {
		if (!visorWorn(client)) return;
		config.cycleRange();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		ensureContext(client);
		if (client.player == null || client.level == null || !config.enabled || !visorWorn(client)) return;
		refreshRoster(client);
		float hudScale = config.hudScale(client.getWindow().getWidth(), client.getWindow().getHeight());
		int width = Math.round(client.getWindow().getWidth() / hudScale);
		int height = Math.round(client.getWindow().getHeight() / hudScale);
		renderWidth = Math.max(1, width);
		renderHeight = Math.max(1, height);
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		int wingCount = memo("wings", () -> (int)roster.stream().map(this::groupKey).distinct().count());
		int actionable = memo("actionable", () -> (int)roster.stream().filter(d -> markerPriority(d) <= 1).count());
		int combat = memo("combat", () -> (int)roster.stream().filter(DroneEntity::combatActive).count());
		VisorHudPolicy.DisplayPlan plan = VisorHudPolicy.displayPlan(roster.size(), wingCount,
			actionable, combat, width, height);
		float pixelScale = hudScale / Math.max(1, client.getWindow().getGuiScale());
		g.pose().pushMatrix();
		g.pose().scale(pixelScale, pixelScale);
		try {
			occupiedLabels.clear();
			worldLabels = 0;
			List<Projection> contacts = proximityContacts(client, width, height, partialTick, plan);
			Map<String, List<Projection>> clusters = cluster(contacts.stream()
				.filter(p -> p.depth > 0 && p.onScreen(width, height)).toList());
			int clusterCount = (int)clusters.values().stream().filter(group -> group.size() > 1).count();
			if (bootTicks > 0) drawBoot(g, client, width, height);
			drawAdaptiveCombat(g, client, width, height);
			drawInspectionContext(g, client, width, plan.compactContext());
			drawActionRail(g, client, width, height, plan);
			reserveOperationSubtitle(client, width, height);
			if (config.airspaceMapEnabled) drawAirspaceMesh(g, client, width, height);
			else drawFieldMesh(g, client, width, height);
			drawEngagements(g, client, contacts, width, height, partialTick, plan);
			drawCarrierMissileLocks(g, client, width, height, partialTick);
			drawProximity(g, client, contacts, clusters, width, height, plan);
			drawPowerLostBeacons(g, client, width, height);
		} finally {
			g.pose().popMatrix();
		}
	}

	private void refreshRoster(Minecraft client) {
		ensureContext(client);
		if (client.level == null || client.player == null || client.player.tickCount < cacheUntil) return;
		cacheUntil = client.player.tickCount + CACHE_TICKS;
		roster = List.copyOf(client.level.getEntitiesOfClass(DroneEntity.class,
			new AABB(client.player.blockPosition()).inflate(256),
				drone -> drone.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())));
		rosterById = new HashMap<>();
		for (DroneEntity drone : roster) rosterById.put(drone.getId(), drone);
		tickMemo.clear();
		solarStations = List.copyOf(client.level.getEntitiesOfClass(SolarServiceStationEntity.class,
			new AABB(client.player.blockPosition()).inflate(512),
			station -> station.ownerId().equals(client.player.getUUID())));
		if (commandGroup.isBlank() || roster.stream().noneMatch(d -> d.groupId().equals(commandGroup))) {
			commandGroup = roster.stream().map(DroneEntity::groupId).filter(id -> !id.isBlank()).sorted()
				.findFirst().orElse("");
		}
		if (pinnedUuid != null) {
			DroneEntity match = roster.stream().filter(d -> d.getUUID().equals(pinnedUuid)).findFirst().orElse(null);
			pinnedEntityId = match == null ? -1 : match.getId();
		}
	}

	private void updateCombatLedger(Minecraft client) {
		Map<UUID, Set<String>> attackers = new LinkedHashMap<>();
		Map<UUID, LivingEntity> current = new LinkedHashMap<>();
		roster.stream().filter(d -> d.isAlive() && !d.isRemoved() && d.combatActive())
			.sorted(Comparator.comparing(d -> d.getUUID().toString())).forEach(drone -> {
				Entity entity = client.level.getEntity(drone.combatTargetId());
				if (!(entity instanceof LivingEntity target)) return;
				current.put(target.getUUID(), target);
				attackers.computeIfAbsent(target.getUUID(), ignored -> new LinkedHashSet<>()).add(drone.getUUID().toString());
			});
		client.level.getEntitiesOfClass(MorrowgearMissileEntity.class,
			new AABB(client.player.blockPosition()).inflate(512),missile->missile.heavy()&&missile.isAlive()
				&&missile.ownedBy(client.player.getUUID()))
			.stream().sorted(Comparator.comparing(missile->missile.getUUID().toString())).forEach(missile->{
				Entity entity=client.level.getEntity(missile.targetId());
				if(!(entity instanceof LivingEntity target))return;
				current.put(target.getUUID(),target);
				attackers.computeIfAbsent(target.getUUID(),ignored->new LinkedHashSet<>())
					.add("CARRIER-MSL-"+missile.getUUID());
			});
		for (var entry : current.entrySet()) {
			if (!observedTargets.containsKey(entry.getKey()) && observedTargets.size() >= VisorCombatLedger.CAPACITY) {
				UUID expired = observedTargets.keySet().stream().filter(id -> !current.containsKey(id)).findFirst().orElse(null);
				if (expired == null) continue;
				observedTargets.remove(expired);
			}
			observedTargets.put(entry.getKey(), entry.getValue());
		}
		List<VisorCombatLedger.Observation> observations = new ArrayList<>();
		for (var entry : observedTargets.entrySet()) {
			LivingEntity target = entry.getValue();
			// isAlive() also becomes false when a live entity unloads; only explicit death is evidence.
			boolean dead = target.getHealth() <= 0 || target.deathTime > 0 || target.getRemovalReason() == Entity.RemovalReason.KILLED;
			Boolean alive = VisorCombatLayout.life(dead, target.isRemoved(), target.level() == client.level
				&& client.level.getEntity(target.getId()) == target);
			Vec3 relative = target.position().subtract(client.player.position());
			observations.add(new VisorCombatLedger.Observation(entry.getKey(), target.getName().getString(),
				attackers.getOrDefault(entry.getKey(), Set.of()), alive,
				VisorCombatLayout.location(relative.x, relative.y, relative.z)));
		}
		combatLedger.update(client.level.getGameTime(), observations);
	}

	List<VisorCombatLedger.Event> combatHistory() { return combatLedger.history(); }
	void openCombatHistory(Minecraft client) {
		ensureContext(client);
		if (client.player != null) client.setScreenAndShow(new VisorCombatHistoryScreen(client.gui.screen(), this::combatHistory));
	}

	private void drawAdaptiveCombat(GuiGraphicsExtractor g, Minecraft client, int width, int height) {
		List<VisorCombatLedger.Front> active = combatLedger.active();
		var summary = VisorCombatLayout.summary(width, active.size());
		occupiedLabels.add(summary);
		g.fill(summary.x(), summary.y(), summary.x() + summary.width(), summary.y() + summary.height(), config.panelAlpha(PANEL));
		g.fill(summary.x(), summary.y(), summary.x() + 2, summary.y() + summary.height(), CYAN);
		int x = summary.x() + 12;
		drawText(g, client, fit(client, "MORROWGEAR / 受信 " + roster.size() + "機", summary.width() - 24), x, 32, CYAN);
		int responding = active.stream().mapToInt(f -> f.attackers().size()).sum();
		String status = active.isEmpty() ? "受信範囲内 / 交戦割当なし" : active.size() + "戦線 / 対応 " + responding + "機";
		if (active.size() > 3) status += " / 他 " + (active.size() - 3);
		drawText(g, client, fit(client, status, summary.width() - 24), x, 56, WHITE);
		List<VisorCombatLedger.Front> visibleFronts = combatLedger.page(selectedFrontTarget, 3);
		for (int i = 0; i < visibleFronts.size(); i++) {
			var front = visibleFronts.get(i);
			int y = 86 + i * 48;
			boolean selected = front.target().equals(selectedFrontTarget);
			if (selected) g.fill(summary.x(), y - 3, summary.x() + 3, y + 41, CYAN);
			drawText(g, client, fit(client, "交戦" + front.number() + " / " + front.location(), summary.width() - 24), x, y, selected ? CYAN : AMBER);
			drawText(g, client, fit(client, front.name() + " / 味方 " + front.attackers().size() + "機", summary.width() - 24), x, y + 22, WHITE);
		}
		List<VisorCombatLedger.Event> events = combatLedger.recent(client.level.getGameTime(), 3);
		var feed = VisorCombatLayout.feed(width, events.size());
		if (!events.isEmpty()) occupiedLabels.add(feed);
		for (int i = 0; i < events.size(); i++) {
			var event = events.get(events.size() - i - 1);
			int y = feed.y() + i * 62;
			int color = event.label().equals("目標撃破") ? GREEN : event.label().equals("交戦開始") ? RED : AMBER;
			g.fill(feed.x(), y, feed.x() + feed.width(), y + 56, config.panelAlpha(PANEL));
			g.fill(feed.x(), y, feed.x() + 2, y + 56, color);
			drawText(g, client, fit(client, event.label() + " / 交戦" + event.front(), feed.width() - 24), feed.x() + 12, y + 7, color);
			drawText(g, client, fit(client, event.detail(), feed.width() - 24), feed.x() + 12, y + 31, WHITE);
		}
	}

	private void drawFieldMesh(GuiGraphicsExtractor g, Minecraft client, int width, int height) {
		FleetLinkPayload.Mesh mesh = FleetLinkClient.mesh(client);
		List<FleetLinkPayload.Relay> relays = FleetLinkClient.relays(client);
		int side = VisorCombatLayout.sideWidth(width);
		int panelWidth = Math.max(260, Math.min(520, width - (side + 48) * 2));
		int x = width / 2 - panelWidth / 2;
		int y = 24;
		int color = switch (mesh.state()) {
			case "STABLE" -> GREEN;
			case "SHORT" -> RED;
			case "BUILDING" -> CYAN;
			default -> MUTED;
		};
		occupiedLabels.add(new VisorCombatLayout.Box(x, y, panelWidth, 58));
		g.fill(x, y, x + panelWidth, y + 58, config.panelAlpha(PANEL));
		g.fill(x, y, x + 3, y + 58, color);
		String state = switch (mesh.state()) {
			case "STABLE" -> "安定";
			case "BUILDING" -> "構築中";
			case "SHORT" -> "FIELD不足 +" + mesh.shortage();
			case "DIRECT" -> "直接通信のみ";
			default -> "未受信";
		};
		drawText(g, client, fit(client, "通信メッシュ / " + state + " / "
			+ mesh.established() + "/" + mesh.assigned() + " NODE", panelWidth - 24), x + 12, y + 7, color);
		String extent = "基線 " + mesh.baseline() + "  予備 " + mesh.reserve() + "  動的 " + mesh.dynamic()
			+ " / 範囲 X " + Math.round(mesh.minX()) + ".." + Math.round(mesh.maxX())
			+ " / Z " + Math.round(mesh.minZ()) + ".." + Math.round(mesh.maxZ());
		drawText(g, client, fit(client, extent, panelWidth - 24), x + 12, y + 31, WHITE);

		Map<UUID, FleetLinkPayload.Relay> byId = new HashMap<>();
		for (FleetLinkPayload.Relay relay : relays) byId.put(relay.id(), relay);
		Map<UUID, String> unitNames = relayUnitNames(client);
		for (FleetLinkPayload.Relay relay : relays) {
			ScreenPoint actual = projectWorldPoint(client, new Vec3(relay.x(), relay.y(), relay.z()), width, height);
			if (actual == null || actual.depth() <= 0) continue;
			int relayColor = relay.duty().equals("DYNAMIC") ? AMBER
				: relay.duty().equals("MESH_RESERVE") ? GREEN : CYAN;
			if (relay.established() && relay.hops() < 255) {
				FleetLinkPayload.Relay parent = relay.parentId() == null ? null : byId.get(relay.parentId());
				Vec3 parentPosition = parent == null ? client.player.position()
					: new Vec3(parent.x(), parent.y(), parent.z());
				ScreenPoint parentPoint = projectWorldPoint(client, parentPosition, width, height);
				if (parentPoint != null && parentPoint.depth() > 0) drawLine(g, actual.x(), actual.y(),
					parentPoint.x(), parentPoint.y(), withAlpha(relayColor, 92));
			} else {
				ScreenPoint target = projectWorldPoint(client,
					new Vec3(relay.targetX(), relay.targetY(), relay.targetZ()), width, height);
				if (target != null && target.depth() > 0) {
					drawDashedLine(g, actual.x(), actual.y(), target.x(), target.y(), withAlpha(AMBER, 150), 6);
					drawDiamond(g, target.x(), target.y(), 5, withAlpha(AMBER, 170), true);
				}
			}
			drawDiamond(g, actual.x(), actual.y(), relay.established() ? 6 : 4,
				relay.established() ? relayColor : withAlpha(AMBER, 170), !relay.established());
			boolean needsAttention = !relay.established() || relay.linkQuality() < 55;
			if (needsAttention) {
				String issue = !relay.established() ? "LINK PENDING" : "LINK LOW " + relay.linkQuality() + "%";
				String tag = issue + " / " + unitNames.getOrDefault(relay.id(), relayCode(relay.id()));
				if (claimLabel(actual.x() + 9, actual.y() - 8, textWidth(client, tag), 20))
					drawText(g, client, tag, actual.x() + 9, actual.y() - 8, AMBER);
			}
		}
	}

	private void drawAirspaceMesh(GuiGraphicsExtractor g, Minecraft client, int width, int height) {
		FleetLinkPayload.Mesh mesh = FleetLinkClient.mesh(client);
		List<FleetLinkPayload.Relay> relays = FleetLinkClient.relays(client);
		if (mesh.assigned() <= 0 && relays.isEmpty()) return;
		List<FleetLinkPayload.Unit> snapshotUnits = FleetLinkClient.units(client);
		VisorAirspaceMapPolicy.Panel panel = VisorAirspaceMapPolicy.panel(width, height);
		int panelX = panel.x(), panelY = panel.y();
		int panelWidth = panel.width(), panelHeight = panel.height();
		occupiedLabels.add(new VisorCombatLayout.Box(panelX, panelY, panelWidth, panelHeight));
		g.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, config.panelAlpha(PANEL_SOFT));
		g.fill(panelX, panelY, panelX + 3, panelY + panelHeight, CYAN);
		g.outline(panelX, panelY, panelWidth, panelHeight, withAlpha(CYAN, 72));

		double centerX = client.player.getX(), centerZ = client.player.getZ();
		double range = VisorAirspaceMapPolicy.range(centerX, centerZ,
			mesh.minX(), mesh.maxX(), mesh.minZ(), mesh.maxZ());
		List<FleetLinkPayload.Relay> establishedRelays = relays.stream()
			.filter(FleetLinkPayload.Relay::established).toList();
		int established = establishedRelays.size();
		Set<UUID> relayIds = relays.stream().map(FleetLinkPayload.Relay::id)
			.collect(java.util.stream.Collectors.toSet());
		List<FleetLinkPayload.Unit> trafficCandidates = snapshotUnits.stream()
			.filter(unit -> !relayIds.contains(unit.id()))
			.filter(unit -> Math.abs(unit.x() - centerX) <= range && Math.abs(unit.z() - centerZ) <= range)
			.sorted(Comparator.comparingInt((FleetLinkPayload.Unit unit) -> airspaceUnitPriority(unit.role(), unit.status()))
				.thenComparingDouble(unit -> square(unit.x() - centerX) + square(unit.z() - centerZ))).toList();
		List<FleetLinkPayload.Unit> traffic = trafficCandidates.stream()
			.limit(VisorAirspaceMapPolicy.MAX_AIR_TRAFFIC).toList();
		long alerts = trafficCandidates.stream()
			.filter(unit -> airspaceUnitPriority(unit.role(), unit.status()) <= 1).count();
		drawText(g, client, fit(client, "3D AIRSPACE / NODE " + established + "/" + relays.size()
			+ " / LINKED " + snapshotUnits.size() + " / 警戒 " + alerts,
			panelWidth - 24), panelX + 12, panelY + 8, CYAN, true);
		drawText(g, client, fit(client, "FIELD R128 / 空域 ±" + Math.round(range) + "m / M: CLOSE",
			panelWidth - 24), panelX + 12, panelY + 30, MUTED);

		int mapX = panelX + 10, mapY = panelY + 50;
		int mapW = panelWidth - 20, mapH = panelHeight - 78;
		refreshAirspaceTerrain(client, centerX, centerZ, range);
		double terrainDatum = airspaceTerrainDatum(client.player.getY());
		double centerY = terrainDatum + 32.0;
		var frame = new VisorAirspaceMapPolicy.Frame(mapX, mapY, mapW, mapH,
			centerX, centerY, centerZ, range);

		Map<UUID, FleetLinkPayload.Relay> byId = new HashMap<>();
		for (FleetLinkPayload.Relay relay : relays) byId.put(relay.id(), relay);

		g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
		drawAirspaceTerrain(g, frame);
		if (!establishedRelays.isEmpty()) {
			double domeCenterX = establishedRelays.stream().mapToDouble(FleetLinkPayload.Relay::x)
				.average().orElse(centerX);
			double domeCenterZ = establishedRelays.stream().mapToDouble(FleetLinkPayload.Relay::z)
				.average().orElse(centerZ);
			double nodeExtent = establishedRelays.stream().mapToDouble(relay -> Math.hypot(
				relay.x() - domeCenterX, relay.z() - domeCenterZ)).max().orElse(0.0);
			double domeRadius = Math.max(VisorAirspaceMapPolicy.FIELD_COVERAGE,
				Math.min(range * 0.78, nodeExtent + VisorAirspaceMapPolicy.FIELD_COVERAGE * 0.45));
			double domeHeight = Math.max(72.0, Math.min(144.0, domeRadius * 0.65));
			int domeColor = mesh.shortage() > 0 ? AMBER : CYAN;
			double domeTerrainY = VisorAirspaceMapPolicy.terrainDisplayY(
				airspaceTerrainHeight(domeCenterX, domeCenterZ, centerX, centerZ, range), terrainDatum);
			double domeBaseY = VisorAirspaceMapPolicy.domeBaseY(domeTerrainY);
			drawAirspaceMeshDome(g, frame, domeCenterX, domeBaseY,
				domeCenterZ, domeRadius, domeHeight, domeColor);
		}
		for (FleetLinkPayload.Relay relay : relays) {
			var child = VisorAirspaceMapPolicy.project(frame, relay.x(), relay.y(), relay.z());
			if (relay.established()) {
				FleetLinkPayload.Relay parent = relay.parentId() == null ? null : byId.get(relay.parentId());
				var root = parent == null
					? VisorAirspaceMapPolicy.project(frame, centerX, centerY, centerZ)
					: VisorAirspaceMapPolicy.project(frame, parent.x(), parent.y(), parent.z());
				drawAirspaceLink(g, root.x(), root.y(), child.x(), child.y(), relayColor(relay), relay.linkQuality());
			} else {
				var target = VisorAirspaceMapPolicy.project(frame, relay.targetX(), relay.targetY(), relay.targetZ());
				drawAirspaceNode(g, target.x(), target.y(), 3, withAlpha(AMBER, 180));
			}
		}

		var controller = VisorAirspaceMapPolicy.project(frame, centerX, client.player.getY(), centerZ);
		traffic.forEach(unit -> {
				var point = VisorAirspaceMapPolicy.project(frame, unit.x(), unit.y(), unit.z());
				int unitColor = airspaceUnitColor(unit.role(), unit.docked(), unit.status());
				drawAirspaceUnit(g, point.x(), point.y(), unit.role(), unitColor);
			});

		for (FleetLinkPayload.Relay relay : relays) {
			var point = VisorAirspaceMapPolicy.project(frame, relay.x(), relay.y(), relay.z());
			int relayColor = relayColor(relay);
			drawAirspaceNode(g, point.x(), point.y(), relay.established() ? 4 : 3, relayColor);
		}
		drawAirspaceNode(g, controller.x(), controller.y(), 5, WHITE);
		var north = VisorAirspaceMapPolicy.project(frame, centerX, centerY, centerZ - range * 0.82);
		var east = VisorAirspaceMapPolicy.project(frame, centerX + range * 0.82, centerY, centerZ);
		drawText(g, client, "N", north.x() - 4, north.y() - 5, MUTED);
		drawText(g, client, "E", east.x() - 4, east.y() - 5, MUTED);
		g.disableScissor();
		drawText(g, client, "◆ CTRL   ◇ MESH DOME   ◈ FIELD   △ UNIT   - LINK",
			panelX + 12, panelY + panelHeight - 20, MUTED);
	}

	private void reserveOperationSubtitle(Minecraft client, int width, int height) {
		OperationCommunicationsClient.subtitle().filter(event -> event.kind().important()).ifPresent(event -> {
			int guiWidth = client.getWindow().getGuiScaledWidth();
			int guiHeight = client.getWindow().getGuiScaledHeight();
			int maxWidth = Math.max(80, Math.min(380, guiWidth - 32));
			int lines = Math.min(2, client.font.split(Component.translatable(event.messageKey(),
				event.source(), event.count()), maxWidth - 16).size());
			double scaleX = (double)width / Math.max(1, guiWidth);
			double scaleY = (double)height / Math.max(1, guiHeight);
			int x = (int)Math.floor((guiWidth - maxWidth) * 0.5 * scaleX);
			int boxHeight = lines * 10 + 10;
			int y = (int)Math.floor(operationSubtitleY(client, boxHeight) * scaleY);
			occupiedLabels.add(new VisorCombatLayout.Box(x, y,
				(int)Math.ceil(maxWidth * scaleX), (int)Math.ceil(boxHeight * scaleY)));
		});
	}

	private void refreshAirspaceTerrain(Minecraft client, double centerX, double centerZ, double range) {
		int cx = (int)Math.floor(centerX), cz = (int)Math.floor(centerZ), rangeKey = (int)Math.round(range);
		long tick = client.level.getGameTime();
		if (airspaceTerrainLevel == client.level && tick < airspaceTerrainRefreshAt
			&& Math.abs(cx - airspaceTerrainCenterX) < 16 && Math.abs(cz - airspaceTerrainCenterZ) < 16
			&& airspaceTerrainRangeKey == rangeKey) return;
		airspaceTerrainLevel = client.level;
		airspaceTerrainRefreshAt = tick + VisorAirspaceMapPolicy.TERRAIN_REFRESH_TICKS;
		airspaceTerrainCenterX = cx;
		airspaceTerrainCenterZ = cz;
		airspaceTerrainRangeKey = rangeKey;
		List<AirspaceTerrainPoint> samples = new ArrayList<>(VisorAirspaceMapPolicy.TERRAIN_GRID
			* VisorAirspaceMapPolicy.TERRAIN_GRID);
		for (int row = 0; row < VisorAirspaceMapPolicy.TERRAIN_GRID; row++) {
			for (int column = 0; column < VisorAirspaceMapPolicy.TERRAIN_GRID; column++) {
				int worldX = (int)Math.round(centerX + VisorAirspaceMapPolicy.sampleOffset(column, range));
				int worldZ = (int)Math.round(centerZ + VisorAirspaceMapPolicy.sampleOffset(row, range));
				BlockPos columnPos = new BlockPos(worldX, 0, worldZ);
				if (!client.level.hasChunkAt(columnPos)) {
					samples.add(new AirspaceTerrainPoint(worldX, 0, worldZ, 0, false));
					continue;
				}
				int worldY = client.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
				BlockPos surface = new BlockPos(worldX, worldY, worldZ);
				int color = client.level.getBlockState(surface).getMapColor(client.level, surface).col;
				samples.add(new AirspaceTerrainPoint(worldX, worldY, worldZ, color, true));
			}
		}
		airspaceTerrain = List.copyOf(samples);
	}

	private void drawAirspaceTerrain(GuiGraphicsExtractor g, VisorAirspaceMapPolicy.Frame frame) {
		int grid = VisorAirspaceMapPolicy.TERRAIN_GRID;
		if (airspaceTerrain.size() != grid * grid) return;
		double datum = airspaceTerrainDatum(frame.centerY() - 32.0);
		drawAirspaceTerrainSlab(g, frame, datum);
		for (int diagonal = 0; diagonal <= (grid - 2) * 2; diagonal++) {
			for (int row = 0; row < grid - 1; row++) {
				int column = diagonal - row;
				if (column < 0 || column >= grid - 1) continue;
				AirspaceTerrainPoint a = airspaceTerrain.get(row * grid + column);
				AirspaceTerrainPoint b = airspaceTerrain.get(row * grid + column + 1);
				AirspaceTerrainPoint c = airspaceTerrain.get((row + 1) * grid + column + 1);
				AirspaceTerrainPoint d = airspaceTerrain.get((row + 1) * grid + column);
				if (!a.loaded() || !b.loaded() || !c.loaded() || !d.loaded()) continue;
				int raw = averageMapColor(a.color(), b.color(), c.color(), d.color());
				double elevation = (a.y() + b.y() + c.y() + d.y()) * 0.25;
				int color = withAlpha(airspaceTerrainColor(raw, elevation, datum), 88);
				var pa = VisorAirspaceMapPolicy.project(frame, a.x(),
					VisorAirspaceMapPolicy.terrainDisplayY(a.y(), datum), a.z());
				var pb = VisorAirspaceMapPolicy.project(frame, b.x(),
					VisorAirspaceMapPolicy.terrainDisplayY(b.y(), datum), b.z());
				var pc = VisorAirspaceMapPolicy.project(frame, c.x(),
					VisorAirspaceMapPolicy.terrainDisplayY(c.y(), datum), c.z());
				var pd = VisorAirspaceMapPolicy.project(frame, d.x(),
					VisorAirspaceMapPolicy.terrainDisplayY(d.y(), datum), d.z());
				fillAirspaceTriangle(g, pa, pb, pc, color, frame);
				fillAirspaceTriangle(g, pa, pc, pd, color, frame);
			}
		}
	}

	private void drawAirspaceTerrainSlab(GuiGraphicsExtractor g, VisorAirspaceMapPolicy.Frame frame,
		double datum) {
		int grid = VisorAirspaceMapPolicy.TERRAIN_GRID;
		for (int side = 0; side < 4; side++) for (int step = 0; step < grid - 1; step++) {
			int first = switch (side) {
				case 0 -> step;
				case 1 -> step * grid + grid - 1;
				case 2 -> (grid - 1) * grid + step;
				default -> step * grid;
			};
			int second = switch (side) {
				case 0 -> step + 1;
				case 1 -> (step + 1) * grid + grid - 1;
				case 2 -> (grid - 1) * grid + step + 1;
				default -> (step + 1) * grid;
			};
			AirspaceTerrainPoint a = airspaceTerrain.get(first), b = airspaceTerrain.get(second);
			if (!a.loaded() || !b.loaded()) continue;
			double displayAY = VisorAirspaceMapPolicy.terrainDisplayY(a.y(), datum);
			double displayBY = VisorAirspaceMapPolicy.terrainDisplayY(b.y(), datum);
			var topA = VisorAirspaceMapPolicy.project(frame, a.x(), displayAY, a.z());
			var topB = VisorAirspaceMapPolicy.project(frame, b.x(), displayBY, b.z());
			var lowA = VisorAirspaceMapPolicy.project(frame, a.x(),
				displayAY - VisorAirspaceMapPolicy.TERRAIN_SLAB_DEPTH, a.z());
			var lowB = VisorAirspaceMapPolicy.project(frame, b.x(),
				displayBY - VisorAirspaceMapPolicy.TERRAIN_SLAB_DEPTH, b.z());
			drawFastLine(g, lowA.x(), lowA.y(), lowB.x(), lowB.y(), 0x8B16201F);
			drawFastLine(g, topA.x(), topA.y(), lowA.x(), lowA.y(), 0x8B22302E);
		}
	}

	private void drawAirspaceMeshDome(GuiGraphicsExtractor g, VisorAirspaceMapPolicy.Frame frame,
		double x, double baseY, double z, double radius, double height, int color) {
		drawAirspaceDomeArc(g, frame, x, baseY, z, radius, height,
			VisorAirspaceMapPolicy.DomeArc.BASE, withAlpha(color, 118));
		drawAirspaceDomeArc(g, frame, x, baseY, z, radius, height,
			VisorAirspaceMapPolicy.DomeArc.NORTH_SOUTH, withAlpha(color, 92));
		drawAirspaceDomeArc(g, frame, x, baseY, z, radius, height,
			VisorAirspaceMapPolicy.DomeArc.EAST_WEST, withAlpha(color, 92));
		drawAirspaceDomeArc(g, frame, x, baseY, z, radius, height,
			VisorAirspaceMapPolicy.DomeArc.DIAGONAL, withAlpha(color, 68));
	}

	private void drawAirspaceDomeArc(GuiGraphicsExtractor g, VisorAirspaceMapPolicy.Frame frame,
		double x, double baseY, double z, double radius, double height,
		VisorAirspaceMapPolicy.DomeArc arc, int color) {
		VisorAirspaceMapPolicy.Point previous = null;
		for (int step = 0; step <= VisorAirspaceMapPolicy.DOME_SEGMENTS; step++) {
			double sweep = arc == VisorAirspaceMapPolicy.DomeArc.BASE ? Math.PI * 2.0 : Math.PI;
			double angle = step * sweep / VisorAirspaceMapPolicy.DOME_SEGMENTS;
			var world = VisorAirspaceMapPolicy.domePoint(x, baseY, z, radius, height, arc, angle);
			var point = VisorAirspaceMapPolicy.project(frame, world.x(), world.y(), world.z());
			if (previous != null) {
				int glow = withAlpha(color, Math.max(24, (color >>> 24) / 3));
				drawFastLine(g, previous.x(), previous.y() + 1, point.x(), point.y() + 1, glow);
				drawFastLine(g, previous.x(), previous.y(), point.x(), point.y(), color);
			}
			previous = point;
		}
	}

	private void drawAirspaceLink(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
		int color, int quality) {
		int alpha = Math.max(112, Math.min(230, 96 + quality));
		drawFastLine(g, x1, y1 + 1, x2, y2 + 1, withAlpha(color, 46));
		drawFastLine(g, x1, y1, x2, y2, withAlpha(color, alpha));
	}

	private int airspaceTerrainHeight(double x, double z, double centerX, double centerZ, double range) {
		Minecraft client = Minecraft.getInstance();
		int exactX = (int)Math.floor(x), exactZ = (int)Math.floor(z);
		BlockPos exactColumn = new BlockPos(exactX, 0, exactZ);
		if (client.level != null && client.level.hasChunkAt(exactColumn))
			return client.level.getHeight(Heightmap.Types.WORLD_SURFACE, exactX, exactZ) - 1;
		int grid = VisorAirspaceMapPolicy.TERRAIN_GRID;
		int column = (int)Math.round((x - (centerX - range)) * (grid - 1) / (range * 2.0));
		int row = (int)Math.round((z - (centerZ - range)) * (grid - 1) / (range * 2.0));
		column = Math.max(0, Math.min(grid - 1, column));
		row = Math.max(0, Math.min(grid - 1, row));
		AirspaceTerrainPoint sample = airspaceTerrain.get(row * grid + column);
		return sample.loaded() ? sample.y() : client.player == null ? 0 : (int)Math.floor(client.player.getY());
	}

	private double airspaceTerrainDatum(double fallback) {
		return airspaceTerrain.stream().filter(AirspaceTerrainPoint::loaded)
			.mapToInt(AirspaceTerrainPoint::y).average().orElse(fallback);
	}

	private void fillAirspaceTriangle(GuiGraphicsExtractor g, VisorAirspaceMapPolicy.Point a,
		VisorAirspaceMapPolicy.Point b, VisorAirspaceMapPolicy.Point c, int color,
		VisorAirspaceMapPolicy.Frame frame) {
		int minimumY = Math.max(frame.y(), Math.min(a.y(), Math.min(b.y(), c.y())));
		int maximumY = Math.min(frame.y() + frame.height() - 1, Math.max(a.y(), Math.max(b.y(), c.y())));
		double[] intersections = new double[3];
		for (int scanY = minimumY; scanY <= maximumY; scanY += VisorAirspaceMapPolicy.TERRAIN_SCAN_STEP) {
			double sampleY = scanY + 0.5;
			int count = 0;
			count = triangleEdgeIntersection(a, b, sampleY, intersections, count);
			count = triangleEdgeIntersection(b, c, sampleY, intersections, count);
			count = triangleEdgeIntersection(c, a, sampleY, intersections, count);
			if (count < 2) continue;
			double low = intersections[0], high = intersections[0];
			for (int i = 1; i < count; i++) {
				low = Math.min(low, intersections[i]);
				high = Math.max(high, intersections[i]);
			}
			int startX = Math.max(frame.x(), (int)Math.floor(low));
			int endX = Math.min(frame.x() + frame.width(), (int)Math.ceil(high) + 1);
			if (endX > startX) g.fill(startX, scanY, endX,
				Math.min(maximumY + 1, scanY + VisorAirspaceMapPolicy.TERRAIN_SCAN_STEP), color);
		}
	}

	private int triangleEdgeIntersection(VisorAirspaceMapPolicy.Point from,
		VisorAirspaceMapPolicy.Point to, double y, double[] output, int count) {
		double minimum = Math.min(from.y(), to.y()), maximum = Math.max(from.y(), to.y());
		if (maximum - minimum < 0.001 || y < minimum || y >= maximum || count >= output.length) return count;
		double ratio = (y - from.y()) / (to.y() - from.y());
		output[count] = from.x() + (to.x() - from.x()) * ratio;
		return count + 1;
	}

	private int averageMapColor(int... colors) {
		int red = 0, green = 0, blue = 0;
		for (int color : colors) {
			red += color >> 16 & 0xff;
			green += color >> 8 & 0xff;
			blue += color & 0xff;
		}
		int count = Math.max(1, colors.length);
		return 0xFF000000 | red / count << 16 | green / count << 8 | blue / count;
	}

	private Map<UUID, String> relayUnitNames(Minecraft client) {
		Map<UUID, String> names = new HashMap<>();
		for (DroneEntity drone : roster) names.put(drone.getUUID(), shortId(drone.unitId()));
		for (FleetLinkPayload.Unit unit : FleetLinkClient.units(client)) names.put(unit.id(), shortId(unit.unitId()));
		return names;
	}

	private int relayColor(FleetLinkPayload.Relay relay) {
		return relay.duty().equals("DYNAMIC") ? AMBER : relay.duty().equals("MESH_RESERVE") ? GREEN : CYAN;
	}

	private String relayCode(UUID id) {
		return id.toString().replace("-", "").substring(0, 4).toUpperCase();
	}

	private int airspaceUnitPriority(String role, String status) {
		if (status.contains("COMBAT") || status.contains("POWER LOST")) return 0;
		return role.equalsIgnoreCase("field") ? 1 : 2;
	}

	private int airspaceUnitColor(String role, boolean docked, String status) {
		if (status.contains("COMBAT") || status.contains("POWER LOST")) return RED;
		if (docked) return MUTED;
		if (role.equalsIgnoreCase("field")) return CYAN;
		if (role.equalsIgnoreCase("scout")) return GREEN;
		if (role.equalsIgnoreCase("security")) return AMBER;
		if (role.equalsIgnoreCase("cargo")) return 0xFFE0B25E;
		return WHITE;
	}

	private int airspaceTerrainColor(int raw, double elevation, double datum) {
		int red = raw >> 16 & 0xff, green = raw >> 8 & 0xff, blue = raw & 0xff;
		int shade = (int)Math.round(Math.max(-20.0, Math.min(24.0, elevation - datum)) * 0.8);
		red = Math.max(0, Math.min(255, (red * 4 + 18) / 5 + shade));
		green = Math.max(0, Math.min(255, (green * 4 + 28) / 5 + shade));
		blue = Math.max(0, Math.min(255, (blue * 4 + 30) / 5 + shade));
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private void drawAirspaceUnit(GuiGraphicsExtractor g, int x, int y, String role, int color) {
		g.fill(x - 6, y - 6, x + 7, y + 7, withAlpha(PANEL, 132));
		switch (role.toLowerCase()) {
			case "field" -> drawAirspaceNode(g, x, y, 6, color);
			case "scout" -> {
				drawFastLine(g, x, y - 6, x + 6, y + 4, color);
				drawFastLine(g, x + 6, y + 4, x - 6, y + 4, color);
				drawFastLine(g, x - 6, y + 4, x, y - 6, color);
			}
			case "security" -> {
				drawFastLine(g, x - 6, y - 3, x, y + 6, color);
				drawFastLine(g, x, y + 6, x + 6, y - 3, color);
			}
			case "cargo" -> g.outline(x - 5, y - 5, 11, 11, color);
			case "salvage" -> {
				drawFastLine(g, x - 5, y - 5, x + 5, y + 5, color);
				drawFastLine(g, x + 5, y - 5, x - 5, y + 5, color);
			}
			default -> {
				drawFastLine(g, x - 5, y, x + 5, y, color);
				drawFastLine(g, x, y - 5, x, y + 5, color);
			}
		}
	}

	private void drawAirspaceNode(GuiGraphicsExtractor g, int x, int y, int radius, int color) {
		g.fill(x - radius - 2, y - radius - 2, x + radius + 3, y + radius + 3, withAlpha(color, 24));
		drawFastLine(g, x, y - radius, x + radius, y, color);
		drawFastLine(g, x + radius, y, x, y + radius, color);
		drawFastLine(g, x, y + radius, x - radius, y, color);
		drawFastLine(g, x - radius, y, x, y - radius, color);
		g.fill(x - 1, y - 1, x + 2, y + 2, color);
	}

	private double square(double value) { return value * value; }

	private ScreenPoint projectWorldPoint(Minecraft client, Vec3 point, int width, int height) {
		Camera camera = client.gameRenderer.mainCamera();
		Vec3 relative = point.subtract(camera.position());
		var forward = camera.forwardVector();
		double depth = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
		Vec3 ndc = client.gameRenderer.projectPointToScreen(point);
		if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y)) return null;
		int x = (int)Math.round((ndc.x + 1.0) * width * 0.5);
		int y = (int)Math.round((1.0 - ndc.y) * height * 0.5);
		return new ScreenPoint(x, y, depth);
	}

	private boolean isPinned(DroneEntity drone) { return pinnedUuid != null && pinnedUuid.equals(drone.getUUID()); }
	private boolean selectedRelationship(DroneEntity drone) {
		if (pinnedUuid != null) return isPinned(drone);
		if (selectedFrontTarget != null) return combatLedger.active().stream()
			.anyMatch(f -> f.target().equals(selectedFrontTarget) && f.attackers().contains(drone.getUUID().toString()));
		return drone.getId() == focusEntityId;
	}

	private void ensureContext(Minecraft client) {
		if (hudLevel != client.level || hudPlayer != client.player) {
			hudLevel = client.level; hudPlayer = client.player;
			visibility.clear(); projections.clear(); tickMemo.clear();
			roster = List.of(); solarStations = List.of(); rosterById = Map.of();
			cacheUntil = 0; memoTick = Long.MIN_VALUE;
			airspaceTerrainLevel = null; airspaceTerrain = List.of();
			airspaceTerrainRefreshAt = Long.MIN_VALUE; airspaceTerrainRangeKey = -1;
			pinnedEntityId = focusEntityId = focusCandidateId = -1;
			focusCandidateTicks = 0; commandGroup = ""; wornLastTick = false;
			combatLedger.clear(); observedTargets.clear(); pinnedUuid = null; selectedFrontTarget = null;
		}
		long tick = client.level == null ? Long.MIN_VALUE : client.level.getGameTime();
		if (tick != memoTick || memoPin != pinnedEntityId || !memoGroup.equals(commandGroup)) {
			if (tick < memoTick) { visibility.clear(); projections.clear(); cacheUntil = 0; }
			memoTick = tick; memoPin = pinnedEntityId; memoGroup = commandGroup;
			tickMemo.clear();
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T memo(String key, java.util.function.Supplier<T> factory) {
		return (T)tickMemo.computeIfAbsent(key, ignored -> factory.get());
	}

	private List<DroneEntity> contactRoster(Minecraft client) {
		return memo("contacts:" + config.proximityRange, () -> roster.stream()
			.filter(d -> d.isAlive() && !d.isRemoved())
			.filter(d -> d.distanceTo(client.player) <= config.proximityRange || isPinned(d))
			.sorted(Comparator.comparingInt(this::markerPriority)
				.thenComparingDouble(d -> d.distanceToSqr(client.player)).thenComparingInt(DroneEntity::getId))
			.limit(MAX_CONTACTS).toList());
	}

	private void updateFocus(Minecraft client) {
		int width = client.getWindow().getWidth();
		int height = client.getWindow().getHeight();
		Projection best = contactRoster(client).stream().map(drone -> project(client, drone, width, height, 1.0f))
			.filter(p -> p.depth > 0 && p.screenDistance < 72)
			.min(Comparator.comparingDouble(Projection::screenDistance)).orElse(null);
		int candidate = best == null ? -1 : best.drone.getId();
		if (candidate == focusCandidateId) focusCandidateTicks++;
		else {
			focusCandidateId = candidate;
			focusCandidateTicks = 0;
		}
		if (focusCandidateTicks >= FOCUS_DWELL_TICKS) focusEntityId = candidate;
	}

	private List<Projection> proximityContacts(Minecraft client, int width, int height,
		float partialTick, VisorHudPolicy.DisplayPlan plan) {
		List<Projection> candidates = contactRoster(client).stream()
			.filter(d -> d.isAlive() && !d.isRemoved())
			.map(d -> project(client, d, width, height, partialTick))
			.sorted(Comparator.comparingInt((Projection p) -> markerPriority(p.drone))
				.thenComparingDouble(Projection::distance)).toList();
		if (plan.detail() == VisorHudPolicy.Detail.UNIT) return candidates;
		Map<String, Projection> representatives = new LinkedHashMap<>();
		List<Projection> selected = new ArrayList<>();
		for (Projection p : candidates) {
			boolean exception = markerPriority(p.drone) <= 1 || isPinned(p.drone);
			boolean selectedLeader = leader(p.drone) && p.drone.groupId().equals(commandGroup);
			if (exception || selectedLeader) selected.add(p);
			else representatives.putIfAbsent(groupKey(p.drone), p);
		}
		for (Projection p : representatives.values()) if (selected.size() < plan.markerBudget()) selected.add(p);
		return selected.stream().distinct().sorted(Comparator
			.comparingInt((Projection p) -> markerPriority(p.drone))
			.thenComparingDouble(Projection::distance)).toList();
	}

	private void drawFleetStrip(GuiGraphicsExtractor g, Minecraft client, int width,
		int contacts, int clusters, VisorHudPolicy.DisplayPlan plan) {
		int y = 28;
		int stripWidth = Math.min(width - 24, VisorHudPolicy.topStripWidth(width));
		int x = (width - stripWidth) / 2;
		g.fill(x, y, x + stripWidth, y + 44, config.panelAlpha(PANEL));
		g.horizontalLine(x, x + stripWidth, y, CYAN);
		int active = (int)roster.stream().filter(d -> !d.isDocked()).count();
		int docked = roster.size() - active;
		int service = (int)roster.stream().filter(DroneEntity::serviceReturnActive).count();
		int recovery = (int)roster.stream().filter(d -> d.recoveryLevel() > 0).count();
		int operations = (int)roster.stream().map(DroneEntity::missionId).filter(id -> !id.isBlank()).distinct().count();
		int alerts = (int)roster.stream().filter(d -> markerPriority(d) <= 1).count();
		int threat = roster.stream().mapToInt(DroneEntity::threatScore).max().orElse(0);
		int solarActive = solarStations.stream().mapToInt(SolarServiceStationEntity::activeRelays).sum();
		int solarWaiting = solarStations.stream().mapToInt(SolarServiceStationEntity::waitingAircraft).sum();
		int solarLow = (int)solarStations.stream().filter(station -> station.energyPercent() < 8).count();
		String summary = "ONLINE " + roster.size() + " / ALERT " + alerts;
		String brand = "MORROWGEAR";
		if (textWidth(client, brand + " / " + summary) <= stripWidth - 20) summary = brand + " / " + summary;
		String[] details = {"ACTIVE " + active, "DOCK " + docked, "RTB " + service,
			"OPS " + operations, "REC " + recovery, threatLabel(threat),
			"GRID " + solarStations.size() + ":" + solarActive + "/" + solarStations.size() * 3
				+ (solarWaiting > 0 ? " Q" + solarWaiting : "") + (solarLow > 0 ? " LOW" + solarLow : ""),
			plan.detail().name(), contacts + " C / " + clusters};
		for (String detail : details) {
			if (textWidth(client, summary + " / " + detail) > stripWidth - 20) break;
			summary += " / " + detail;
		}
		drawText(g, client, fit(client, summary, stripWidth - 20), x + 10, y + 12,
			alerts > 0 ? AMBER : WHITE, true);
	}

	private void drawCommandContext(GuiGraphicsExtractor g, Minecraft client, int width,
		VisorHudPolicy.DisplayPlan plan) {
		DroneEntity pinned = findById(pinnedEntityId);
		String contextGroup = pinned == null ? commandGroup : pinned.groupId();
		List<DroneEntity> members = pinned != null && contextGroup.isBlank() ? List.of(pinned)
			: roster.stream().filter(d -> d.groupId().equals(contextGroup)).toList();
		int x = 42;
		int y = 86;
		int panelWidth = HmiArt.hudSideWidth(width);
		int panelHeight = plan.compactContext() ? 122 : 158;
		g.fill(x, y, x + panelWidth, y + panelHeight, config.panelAlpha(PANEL));
		g.fill(x, y, x + 2, y + panelHeight, CYAN);
		g.horizontalLine(x, x + panelWidth, y, LINE);
		drawText(g, client, fit(client, "COMMAND CONTEXT" + (pinned == null ? "" : " / PINNED"), panelWidth - 26), x + 13, y + 12, CYAN);
		List<String> missions = members.stream().map(d -> d.missionId().isBlank()
			? d.operationalState().name() : d.missionId()).distinct().toList();
		String mission = missions.size() > 1 ? "MIXED " + missions.size() : missions.isEmpty() ? "STANDBY" : missions.getFirst();
		String title = (contextGroup.isBlank() ? "UNASSIGNED" : contextGroup) + " // " + mission;
		drawText(g, client, fit(client, title, panelWidth - 26), x + 13, y + 42, WHITE, true);
		int active = (int)members.stream().filter(d -> !d.isDocked()).count();
		int detached = (int)members.stream().filter(d -> d.combatActive() || d.serviceReturnActive()).count();
		int recovery = (int)members.stream().filter(d -> d.recoveryLevel() > 0).count();
		DroneEntity leader = members.stream().filter(this::leader).findFirst().orElse(members.isEmpty() ? null : members.getFirst());
		String activeText = active + " ACTIVE" + (detached > 0 ? " / " + detached + " DETACHED" : "");
		drawText(g, client, fit(client, activeText, panelWidth - 26), x + 13, y + 80, detached > 0 ? AMBER : WHITE);
		if (plan.compactContext()) return;
		String lead = "LEAD " + (leader == null ? "--" : shortId(leader.unitId()));
		String recoveryText = "REC " + recovery;
		drawText(g, client, fit(client, lead, panelWidth - 38 - textWidth(client, recoveryText)),
			x + 13, y + 116, MUTED);
		drawText(g, client, recoveryText, x + panelWidth - textWidth(client, recoveryText) - 13, y + 116,
			recovery > 0 ? AMBER : MUTED);
	}

	private void drawSafeZone(GuiGraphicsExtractor g, int width, int height) {
		int radius = Math.max(58, (int)(Math.min(width, height) * VisorHudPolicy.SAFE_ZONE_RATIO));
		drawCircle(g, width / 2, height / 2, radius, 0x1E9FD9DD);
	}

	private void drawOperationOverview(GuiGraphicsExtractor g, Minecraft client, int width) {
		DroneEntity source = roster.stream().filter(drone -> !drone.operationSummary().isBlank())
			.findFirst().orElse(null);
		if (source == null) return;
		int panelWidth = HmiArt.hudOverviewWidth(width);
		if (panelWidth < 320) return;
		int x = (width - panelWidth) / 2;
		int y = 86;
		int panelHeight = source.operationFronts().isBlank() ? 44 : 76;
		int color = source.operationSummary().contains("CRITICAL") ? RED : AMBER;
		g.fill(x, y, x + panelWidth, y + panelHeight, config.panelAlpha(PANEL));
		g.fill(x, y, x + 3, y + panelHeight, color);
		g.horizontalLine(x, x + panelWidth, y, color);
		drawText(g, client, fit(client, source.operationSummary(), panelWidth - 26),
			x + 13, y + 10, color, true);
		if (!source.operationFronts().isBlank()) {
			drawText(g, client, fit(client, source.operationFronts(), panelWidth - 26),
				x + 13, y + 42, WHITE);
		}
	}

	private void drawInspectionContext(GuiGraphicsExtractor g, Minecraft client, int width, boolean compact) {
		DroneEntity drone = findById(pinnedUuid != null ? pinnedEntityId : focusEntityId);
		if (pinnedUuid != null && (drone == null || !pinnedUuid.equals(drone.getUUID()))) {
			var missing = VisorCombatLayout.inspection(width, client.getWindow().getHeight(), 36);
			occupiedLabels.add(missing);
			g.fill(missing.x(), missing.y(), missing.x() + missing.width(), missing.y() + missing.height(), config.panelAlpha(PANEL));
			drawText(g, client, "固定機体 / 未受信", missing.x() + 12, 240, AMBER);
			return;
		}
		if (drone == null) return;
		int structure = Math.round(drone.getHealth() * 100.0f / Math.max(1.0f, drone.getMaxHealth()));
		var resources = DroneResourceUiPolicy.assess(drone.role() == DroneRole.SECURITY,
			drone.securityLoadout(), drone.batteryPercent(), drone.weaponPowerPercent(),
			drone.gunAmmo(), drone.gunCapacity(), drone.missiles(), drone.missileCapacity(),
			drone.laserHeat(), structure, drone.lowestSubsystemCondition() / 10,
			drone.serviceReturnActive());
		int desired = 142 + resources.metrics().size() * 22;
		var box = VisorCombatLayout.inspection(width, client.getWindow().getHeight(), compact ? 116 : desired);
		compact |= box.height() < desired;
		if (box.height() < 116) return;
		int panelWidth = box.width(), x = box.x(), y = box.y(), panelHeight = compact ? 116 : desired;
		occupiedLabels.add(new VisorCombatLayout.Box(x, y, panelWidth, panelHeight));
		int stateColor = stateColor(drone);
		g.fill(x, y, x + panelWidth, y + panelHeight, config.panelAlpha(PANEL));
		g.fill(x + panelWidth - 2, y, x + panelWidth, y + panelHeight, stateColor);
		g.horizontalLine(x, x + panelWidth, y, LINE);
		drawText(g, client, fit(client, pinnedEntityId >= 0 ? "INSPECTION / PINNED" : "INSPECTION / FOCUS", panelWidth - 26),
			x + 13, y + 12, stateColor);
		String identity = shortId(drone.unitId()) + " / " + drone.role().displayName();
		drawText(g, client, fit(client, identity, panelWidth - 26), x + 13, y + 42, WHITE, true);
		String state = conciseState(drone) + contextSuffix(drone);
		drawText(g, client, fit(client, state, panelWidth - 26), x + 13, y + 68, stateColor);
		if (compact) {
			drawText(g, client, fit(client, resources.readiness(), panelWidth - 26), x + 13, y + 91,
				resourceColor(resources.tone()));
			return;
		}
		drawText(g, client, fit(client, drone.dataLinkStatus(), panelWidth - 26),
			x + 13, y + 91, drone.dataLinkStatus().contains("WAIT") ? AMBER : MUTED);
		drawText(g, client, fit(client, resources.readiness(), panelWidth - 26), x + 13, y + 115,
			resourceColor(resources.tone()), true);
		int metricY = y + 146;
		for (var metric : resources.metrics()) {
			drawResourceBar(g, client, x + 13, metricY, metric, panelWidth - 26);
			metricY += 22;
		}
	}

	private void drawResourceBar(GuiGraphicsExtractor g, Minecraft client, int x, int y,
		DroneResourceUiPolicy.Metric metric, int width) {
		int color = resourceColor(metric.tone());
		int barX = x + 58;
		int valueWidth = textWidth(client, metric.value());
		int barWidth = Math.max(0, x + width - valueWidth - 12 - barX);
		drawText(g, client, metric.code(), x, y - 7, MUTED);
		g.fill(barX, y, barX + barWidth, y + 6, 0xB3263B3F);
		g.fill(barX, y, barX + Math.round(barWidth * metric.percent() / 100.0f), y + 6, color);
		drawText(g, client, metric.value(), x + width - valueWidth, y - 7, WHITE);
	}

	private int resourceColor(DroneResourceUiPolicy.Tone tone) {
		return switch (tone) {
			case NOMINAL -> CYAN;
			case CAUTION -> AMBER;
			case BLOCKED -> RED;
		};
	}

	private void drawTelemetryBar(GuiGraphicsExtractor g, Minecraft client, int x, int y,
		String label, int value, int color, int width) {
		int clamped = Math.max(0, Math.min(100, value));
		int barX = x + textWidth(client, label) + 12;
		int barWidth = Math.max(0, x + width - textWidth(client, "100%") - 12 - barX);
		drawText(g, client, label, x, y - 7, MUTED);
		g.fill(barX, y, barX + barWidth, y + 6, 0xB3263B3F);
		g.fill(barX, y, barX + Math.round(barWidth * clamped / 100.0f), y + 6,
			clamped <= 10 ? RED : clamped <= 25 ? AMBER : color);
		String text = clamped + "%";
		drawText(g, client, text, x + width - textWidth(client, text), y - 7, WHITE);
	}

	private void drawProximity(GuiGraphicsExtractor g, Minecraft client, List<Projection> projected,
		Map<String, List<Projection>> clusters,
		int width, int height, VisorHudPolicy.DisplayPlan plan) {
		int budget = Math.min(5, plan.markerBudget());
		int ordinaryBudget = config.ordinaryMarkerBudget(budget);
		int ordinaryLabels = config.ordinaryLabelBudget(plan.labelBudget());
		int ordinaryDrawn = 0, ordinaryLabeled = 0;
		int labels = 0;
		int drawn = 0;
		for (List<Projection> group : clusters.values()) {
			Projection p = group.getFirst();
			boolean pinned = group.stream().anyMatch(c -> isPinned(c.drone));
			boolean focused = pinnedUuid == null && group.stream().anyMatch(c -> c.drone.getId() == focusEntityId);
			boolean actionable = group.stream().anyMatch(c -> markerPriority(c.drone) <= 1);
			if (drawn >= budget) continue;
			boolean protectedContact = pinned || focused || actionable;
			if (!protectedContact && ordinaryDrawn >= ordinaryBudget) continue;
			if (!labelFree((int)p.x - 12, (int)p.y - 20, 36, 42)) continue;
			drawn++;
			if (!protectedContact) ordinaryDrawn++;
			boolean visible = group.stream().anyMatch(c -> visibleFromCamera(client, c));
			int alpha = VisorHudPolicy.markerAlpha(p.screenDistance,
				Math.min(width, height) * VisorHudPolicy.SAFE_ZONE_RATIO, pinned, actionable);
			int color = withAlpha(actionable ? stateColor(p.drone) : CYAN, visible ? alpha : Math.min(alpha, 92));
			if (group.size() > 1) drawCluster(g, client, group, p, color);
			else drawMarker(g, (int)p.x, (int)p.y, color, leader(p.drone), visible,
				markerRadius(p.distance, leader(p.drone)));
			boolean selectedLeader = leader(p.drone) && p.drone.groupId().equals(commandGroup);
			boolean automaticLabel = (selectedLeader || actionable) && !p.drone.combatActive();
			if ((pinned || focused || automaticLabel)
				&& labels < plan.labelBudget() && (protectedContact || ordinaryLabeled < ordinaryLabels)) {
				labels++;
				if (!protectedContact) ordinaryLabeled++;
				String role = actionable ? conciseState(p.drone)
					: leader(p.drone) ? "LEAD" : p.drone.role().id().toUpperCase();
				String identity = group.size() > 1 ? groupKey(p.drone) + " x" + group.size() : shortId(p.drone.unitId());
				drawWorldTag(g, client, p, identity + " / " + role + " / "
					+ Math.round(p.distance) + "m", color);
			}
			if (Math.abs(p.relativeY) >= 4.0) {
				int hy = (int)p.y + (p.relativeY > 0 ? -17 : 11);
				if (claimLabel((int)p.x - 3, hy, 12, 20))
					drawText(g, client, p.relativeY > 0 ? "^" : "v", (int)p.x - 3, hy, color);
			}
			if ((pinned || selectedLeader) && group.size() == 1) drawMotionVector(g, client, p, color, width, height);
		}
		drawOffscreen(g, client, projected, width, height, plan.edgeBudget());
	}

	private void drawEngagements(GuiGraphicsExtractor g, Minecraft client, List<Projection> contacts,
		int width, int height, float partialTick, VisorHudPolicy.DisplayPlan plan) {
		Map<Integer, List<DroneEntity>> byTarget = memo("attackers", () -> {
			Map<Integer, List<DroneEntity>> grouped = new LinkedHashMap<>();
			roster.stream().filter(DroneEntity::combatActive).filter(d -> d.combatTargetId() >= 0)
				.sorted(Comparator.comparingInt(DroneEntity::combatTargetId).thenComparing(DroneEntity::unitId))
				.forEach(d -> grouped.computeIfAbsent(d.combatTargetId(), ignored -> new ArrayList<>()).add(d));
			return grouped;
		});
		List<Integer> targets = memo("engagementTargets", () -> byTarget.keySet().stream()
			.filter(id -> client.level.getEntity(id) instanceof LivingEntity target && target.isAlive())
			.sorted(Comparator.<Integer>comparingInt(id -> byTarget.get(id).stream().anyMatch(this::selectedRelationship) ? 0 : 1)
				.thenComparingInt(id -> combatLedger.number(client.level.getEntity(id).getUUID())))
			.limit(6).toList());
		List<Engagement> engagements = new ArrayList<>();
		for (int targetId : targets) {
			Entity entity = client.level.getEntity(targetId);
			if (!(entity instanceof LivingEntity target) || !target.isAlive()) continue;
			TargetProjection projection = projectTarget(client, target, width, height, partialTick);
			engagements.add(new Engagement(target, projection, byTarget.get(targetId)));
		}
		engagements.sort(Comparator.<Engagement>comparingInt(e -> e.attackers.stream().anyMatch(this::selectedRelationship) ? 0 : 1)
			.thenComparingInt(e -> combatLedger.number(e.target.getUUID())));
		int displayLimit = Math.min(3, VisorHudPolicy.engagementDisplayLimit(plan.detail()));
		for (int index = 0; index < Math.min(displayLimit, engagements.size()); index++) {
			drawEngagement(g, client, engagements.get(index), combatLedger.number(engagements.get(index).target.getUUID()), width, height, partialTick);
		}
	}

	private void drawEngagement(GuiGraphicsExtractor g, Minecraft client, Engagement engagement,
		int ordinal, int width, int height, float partialTick) {
		TargetProjection target = engagement.projection;
		if (ordinal <= 0) return;
		boolean selected = engagement.attackers.stream().anyMatch(this::selectedRelationship);
		String assignment = memo("assignment:" + engagement.target.getId(), () -> assignmentLabel(engagement.attackers));
		String targetName = engagement.target.getName().getString().toUpperCase();
		int health = Math.round(engagement.target.getHealth() * 100.0f
			/ Math.max(1.0f, engagement.target.getMaxHealth()));
		String label = String.format("交戦%02d / %s > %s / HP %d%%",
			ordinal, assignment, targetName, Math.max(0, health));
		boolean visible = visibleWorldFromCamera(client, engagement.target, target.anchor, true,
			engagement.target.isAlive());
		int color = visible ? RED : withAlpha(RED, 105);
		if (target.depth <= 0 || !target.onScreen(width, height)) {
			drawTargetEdge(g, client, target, selected ? label : "交戦" + ordinal, width, height, color);
			return;
		}
		int radius = target.distance <= 12 ? 11 : target.distance <= 36 ? 9 : 7;
		if (!labelFree((int)target.x - radius, (int)target.y - radius, radius * 2, radius * 2)) return;
		drawTargetBracket(g, (int)target.x, (int)target.y, radius, color, !visible);
		if (visible && selected) {
			for (DroneEntity attacker : engagement.attackers.stream().limit(8).toList()) {
				Projection drone = project(client, attacker, width, height, partialTick);
				if (drone.depth > 0 && drone.onScreen(width, height)
					&& labelFree((int)Math.min(drone.x, target.x), (int)Math.min(drone.y, target.y),
						Math.max(1, (int)Math.abs(drone.x - target.x)), Math.max(1, (int)Math.abs(drone.y - target.y)))) {
					drawDashedLine(g, (int)drone.x, (int)drone.y, (int)target.x, (int)target.y,
						withAlpha(RED, 72), 7);
				}
			}
		}
		if (!selected) {
			String number = "交戦" + ordinal;
			int nx = (int)target.x + radius + 8, ny = (int)target.y - 10;
			if (claimLabel(nx, ny, textWidth(client, number), 20)) drawText(g, client, number, nx, ny, color);
			return;
		}
		int tagX = (int)target.x + radius + 8;
		int tagY = (int)target.y - 18;
		label = fit(client, label, Math.min(450, width - 32));
		int tagWidth = textWidth(client, label) + 16;
		tagX = Math.clamp(tagX, 8, Math.max(8, width - tagWidth - 8));
		tagY = Math.clamp(tagY, 8, Math.max(8, height - 110));
		if (!claimLabel(tagX, tagY, tagWidth, 32)) return;
		g.fill(tagX, tagY, tagX + tagWidth, tagY + 32, config.panelAlpha(PANEL_SOFT));
		g.fill(tagX, tagY, tagX + 3, tagY + 32, color);
		drawText(g, client, label, tagX + 8, tagY + 7, WHITE, true);
	}

	private void drawTargetBracket(GuiGraphicsExtractor g, int x, int y, int radius,
		int color, boolean estimated) {
		int arm = Math.max(3, radius / 2);
		drawLine(g, x - radius, y - radius, x - radius + arm, y - radius, color);
		drawLine(g, x - radius, y - radius, x - radius, y - radius + arm, color);
		drawLine(g, x + radius, y - radius, x + radius - arm, y - radius, color);
		drawLine(g, x + radius, y - radius, x + radius, y - radius + arm, color);
		drawLine(g, x - radius, y + radius, x - radius + arm, y + radius, color);
		drawLine(g, x - radius, y + radius, x - radius, y + radius - arm, color);
		drawLine(g, x + radius, y + radius, x + radius - arm, y + radius, color);
		drawLine(g, x + radius, y + radius, x + radius, y + radius - arm, color);
		if (estimated) drawDashedLine(g, x - radius, y + radius + 5, x + radius, y + radius + 5,
			color, 3);
	}

	private void drawCarrierMissileLocks(GuiGraphicsExtractor g, Minecraft client, int width,
		int height, float partialTick) {
		Map<Integer, Long> inFlight = client.level.getEntitiesOfClass(MorrowgearMissileEntity.class,
			new AABB(client.player.blockPosition()).inflate(512), missile -> missile.heavy() && missile.isAlive()
				&& missile.ownedBy(client.player.getUUID()) && missile.targetId() >= 0).stream()
			.collect(java.util.stream.Collectors.groupingBy(MorrowgearMissileEntity::targetId,
				LinkedHashMap::new, java.util.stream.Collectors.counting()));
		Map<Integer, Long> queued = new LinkedHashMap<>();
		Map<Integer, String> phases = new LinkedHashMap<>();
		for (CarrierEntity carrier : client.level.getEntitiesOfClass(CarrierEntity.class,
			new AABB(client.player.blockPosition()).inflate(512), carrier -> carrier.isAlive()
				&& carrier.isOwnedBy(client.player.getUUID()))) {
			String phase = switch (carrier.missileLaunchPhase()) {
				case HATCH_OPEN -> "HATCH";
				case RIPPLE_FIRE -> "LAUNCH";
				case HATCH_CLOSE -> "CLOSE";
				default -> "LOCK";
			};
			for (int target : carrier.missileLockedTargetIds()) {
				queued.merge(target, 1L, Long::sum);
				phases.put(target, phase);
			}
		}
		var targetIds = new LinkedHashSet<Integer>();
		targetIds.addAll(queued.keySet()); targetIds.addAll(inFlight.keySet());
		int drawn = 0;
		for (int targetId : targetIds) {
			if (drawn++ >= 4) break;
			Entity entity = client.level.getEntity(targetId);
			if (!(entity instanceof LivingEntity target) || !target.isAlive()) continue;
			TargetProjection projection = projectTarget(client, target, width, height, partialTick);
			long waiting=queued.getOrDefault(targetId,0L), flying=inFlight.getOrDefault(targetId,0L);
			String phase=flying>0?"TRACK":phases.getOrDefault(targetId,"LOCK");
			String label = "母艦 MSL " + phase + " x" + (waiting+flying) + " / "
				+ target.getName().getString().toUpperCase();
			if (projection.depth <= 0 || !projection.onScreen(width, height)) {
				drawTargetEdge(g, client, projection, label, width, height, AMBER);
				continue;
			}
			int x = (int)projection.x, y = (int)projection.y;
			if (!labelFree(x - 13, y - 13, 26, 26)) continue;
			drawTargetBracket(g, x, y, 13, AMBER, false);
			int tx = Math.clamp(x + 20, 8, Math.max(8, width - textWidth(client, label) - 20));
			int ty = Math.clamp(y + 14, 8, Math.max(8, height - 100));
			if (!claimLabel(tx - 4, ty - 3, textWidth(client, label) + 8, 26)) continue;
			g.fill(tx - 4, ty - 3, tx + textWidth(client, label) + 4, ty + 23,
				config.panelAlpha(PANEL_SOFT));
			drawText(g, client, label, tx, ty, AMBER, true);
		}
	}

	private void drawTargetEdge(GuiGraphicsExtractor g, Minecraft client, TargetProjection target,
		String label, int width, int height, int color) {
		double dx = target.x - width / 2.0;
		double dy = target.y - height / 2.0;
		double scale = Math.min((width / 2.0 - 24) / Math.max(1, Math.abs(dx)),
			(height / 2.0 - 72) / Math.max(1, Math.abs(dy)));
		int x = (int)(width / 2.0 + dx * scale);
		int y = (int)(height / 2.0 + dy * scale);
		drawChevron(g, x, y, dx, dy, color);
		String compact = fit(client, label, 420);
		int tx = dx >= 0 ? x - textWidth(client, compact) - 16 : x + 16;
		int ty = Math.max(40, Math.min(height - 108, y - 6));
		if (claimLabel(tx - 4, ty - 3, textWidth(client, compact) + 8, 26)) {
			g.fill(tx - 4, ty - 3, tx + textWidth(client, compact) + 4, ty + 23, config.panelAlpha(PANEL_SOFT));
			drawText(g, client, compact, tx, ty, color, true);
		}
	}

	private String assignmentLabel(List<DroneEntity> attackers) {
		long wings = attackers.stream().map(this::groupKey).distinct().count();
		return switch (VisorHudPolicy.combatAssignment(attackers.size(), (int)wings)) {
			case UNIT_IDS -> attackers.stream().map(d -> shortId(d.unitId()))
				.reduce((left, right) -> left + "+" + right).orElse("UNASSIGNED");
			case WING -> groupKey(attackers.getFirst()) + " x" + attackers.size();
			case TASK_FORCE -> wings + "W / " + attackers.size() + "U";
		};
	}

	private Map<String, List<Projection>> cluster(List<Projection> contacts) {
		Map<String, List<Projection>> clusters = new LinkedHashMap<>();
		for (Projection p : contacts) {
			String group = p.drone.groupId().isBlank() ? "UNASSIGNED" : p.drone.groupId();
			String key = group + ':' + Math.round(p.x / 24.0) + ':' + Math.round(p.y / 24.0);
			clusters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(p);
		}
		return clusters;
	}

	private void drawCluster(GuiGraphicsExtractor g, Minecraft client, List<Projection> group,
		Projection p, int color) {
		drawDiamond(g, (int)p.x, (int)p.y, 5, color, false);
		String name = "x" + group.size();
		if (claimLabel((int)p.x + 12, (int)p.y - 6, textWidth(client, name), 20))
			drawText(g, client, name, (int)p.x + 12, (int)p.y - 6, color);
	}

	private void drawWorldTag(GuiGraphicsExtractor g, Minecraft client, Projection p, String label, int color) {
		label = fit(client, label, 340);
		int x = (int)p.x + 17;
		int y = (int)p.y - 18;
		int tagWidth = textWidth(client, label) + 16;
		if (!claimLabel(x, y, tagWidth, 32)) return;
		g.fill(x, y, x + tagWidth, y + 32, config.panelAlpha(PANEL_SOFT));
		g.fill(x, y, x + 2, y + 32, color);
		drawText(g, client, label, x + 8, y + 7, WHITE);
	}

	private void drawOffscreen(GuiGraphicsExtractor g, Minecraft client, List<Projection> projected,
		int width, int height, int edgeBudget) {
		Map<String, List<Projection>> sectors = new HashMap<>();
		for (Projection p : projected) {
			if (p.depth > 0 && p.onScreen(width, height)) continue;
			double angle = Math.atan2(p.y - height / 2.0, p.x - width / 2.0);
			int sector = Math.floorMod((int)Math.round(angle / (Math.PI / 4.0)), 8);
			String priorityBand = markerPriority(p.drone) <= 1 ? "ACTION" : "NORMAL";
			sectors.computeIfAbsent(sector + "|" + priorityBand, ignored -> new ArrayList<>()).add(p);
		}
		sectors.values().stream().sorted(Comparator.comparingInt(group -> group.stream()
			.mapToInt(p -> markerPriority(p.drone)).min().orElse(3))).limit(edgeBudget)
			.forEach(group -> drawEdgeContact(g, client, group, width, height));
	}

	private void drawEdgeContact(GuiGraphicsExtractor g, Minecraft client, List<Projection> group,
		int width, int height) {
		Projection p = group.getFirst();
		double dx = p.x - width / 2.0;
		double dy = p.y - height / 2.0;
		double scale = Math.min((width / 2.0 - 18) / Math.max(1, Math.abs(dx)),
			(height / 2.0 - 66) / Math.max(1, Math.abs(dy)));
		int x = (int)(width / 2.0 + dx * scale);
		int y = (int)(height / 2.0 + dy * scale);
		int color = group.stream().anyMatch(c -> markerPriority(c.drone) <= 1) ? stateColor(p.drone) : CYAN;
		drawChevron(g, x, y, dx, dy, color);
		String groupName = p.drone.groupId().isBlank() ? "UNASSIGNED" : p.drone.groupId();
		String text = groupName + (group.size() > 1 ? " x" + group.size() : "") + " / "
			+ Math.round(group.stream().mapToDouble(Projection::distance).average().orElse(p.distance)) + "m";
		int tx = dx >= 0 ? x - textWidth(client, text) - 15 : x + 15;
		int ty = Math.max(36, Math.min(height - 94, y - 4));
		if (claimLabel(tx - 4, ty - 3, textWidth(client, text) + 8, 26)) {
			g.fill(tx - 4, ty - 3, tx + textWidth(client, text) + 4, ty + 23, config.panelAlpha(PANEL_SOFT));
			drawText(g, client, text, tx, ty, color);
		}
	}

	private void drawActionRail(GuiGraphicsExtractor g, Minecraft client, int width, int height,
		VisorHudPolicy.DisplayPlan plan) {
		List<PowerLostBeaconStore.Beacon> lostBeacons = PowerLostBeaconStore.snapshot(client.level.getGameTime());
		int critical = (int)roster.stream().filter(d -> d.threatScore() >= 28).count();
		int recovery = (int)roster.stream().filter(d -> d.recoveryLevel() > 0).count();
		int lowPower = (int)roster.stream().filter(d -> d.batteryPercent() <= 20).count();
		int weaponLow = (int)roster.stream().filter(d -> d.weaponPowerPercent() <= 10 && d.combatActive()).count();
		List<DroneEntity> maintenance = roster.stream().filter(d -> d.lowestSubsystemCondition() <= 420).toList();
		int combat = (int)roster.stream().filter(DroneEntity::combatActive).count();
		long combatWings = roster.stream().filter(DroneEntity::combatActive).map(this::groupKey).distinct().count();
		long combatTargets = roster.stream().filter(DroneEntity::combatActive)
			.mapToInt(DroneEntity::combatTargetId).filter(id -> id >= 0).distinct().count();
		List<AlertLine> alerts = new ArrayList<>();
		if (!lostBeacons.isEmpty()) {
			PowerLostBeaconStore.Beacon nearest = lostBeacons.stream().min(Comparator.comparingDouble(beacon ->
				beacon.dimension().equals(client.level.dimension().toString())
					? beacon.position().distanceTo(client.player.position()) : Double.MAX_VALUE)).orElseThrow();
			String location = nearest.dimension().equals(client.level.dimension().toString())
				? Math.round(nearest.position().distanceTo(client.player.position())) + "m / "
					+ blockCoordinates(nearest.position())
				: shortDimension(nearest.dimension()) + " / " + blockCoordinates(nearest.position());
			alerts.add(new AlertLine(RED, "P0 / POWER LOST x" + lostBeacons.size(),
				shortId(nearest.unitId()) + " / " + location));
		}
		if (critical > 0) alerts.add(new AlertLine(RED, "P0 / HOSTILE PRESSURE x" + critical,
			combat > 0 ? combatWings + "W / " + combat + "U / " + combatTargets + "T" : "RESPONSE REQUIRED"));
		if (lowPower > 0) alerts.add(new AlertLine(AMBER, "P1 / FLIGHT RESERVE x" + lowPower,
			"RTB " + roster.stream().filter(d -> d.batteryPercent() <= 20 && d.serviceReturnActive()).count()
				+ " / DOCKED " + roster.stream().filter(d -> d.batteryPercent() <= 20 && d.isDocked()).count()));
		if (recovery > 0) alerts.add(new AlertLine(AMBER, "P1 / RECOVERY x" + recovery,
			wingImpact(roster.stream().filter(d -> d.recoveryLevel() > 0).toList())));
		if (weaponLow > 0) alerts.add(new AlertLine(AMBER, "P1 / WEAPON RESERVE x" + weaponLow, "RELIEF ROTATION"));
		if (!maintenance.isEmpty()) alerts.add(new AlertLine(AMBER,
			"P1 / SUBSYSTEM SERVICE x" + maintenance.size(), wingImpact(maintenance)));
		if (alerts.isEmpty()) return;
		int visibleRows = VisorHudPolicy.visibleAlertCount(alerts.size(), Math.max(1, plan.alertRows()));
		int additional = alerts.size() - visibleRows;
		alerts = alerts.stream().limit(visibleRows).toList();
		if (alerts.isEmpty()) return;
		int railWidth = VisorCombatLayout.sideWidth(width);
		int x = 24;
		boolean stacked = railWidth < 300;
		int rowHeight = stacked ? 48 : 32;
		int railHeight = 12 + alerts.size() * rowHeight;
		int y = height * 81 / 100 - railHeight;
		if (!labelFree(x, y, railWidth, railHeight)) return;
		occupiedLabels.add(new VisorCombatLayout.Box(x, y, railWidth, railHeight));
		g.fill(x, y, x + railWidth, y + railHeight, 0xD20E1312);
		g.fill(x, y, x + 3, y + railHeight, alerts.getFirst().color);
		g.horizontalLine(x, x + railWidth, y, alerts.getFirst().color);
		for (int i = 0; i < alerts.size(); i++) {
			AlertLine line = alerts.get(i);
			int lineY = y + 10 + i * rowHeight;
			drawText(g, client, fit(client, line.label + (additional > 0 ? " / 他" + additional + "件" : ""), railWidth - 24), x + 12, lineY, line.color, i == 0);
			String response = fit(client, line.response, railWidth - 24);
			drawText(g, client, response, stacked ? x + 12 : x + railWidth - textWidth(client, response) - 12,
				lineY + (stacked ? 22 : 0), MUTED);
		}
	}

	private void drawPowerLostBeacons(GuiGraphicsExtractor g, Minecraft client, int width, int height) {
		String currentDimension = client.level.dimension().toString();
		List<PowerLostBeaconStore.Beacon> visibleBeacons = memo("beacons", () ->
			PowerLostBeaconStore.snapshot(client.level.getGameTime()).stream()
				.filter(b -> b.dimension().equals(currentDimension))
				.sorted(Comparator.comparingDouble(b -> b.position().distanceToSqr(client.player.position())))
				.limit(MAX_BEACONS).toList());
		for (PowerLostBeaconStore.Beacon beacon : visibleBeacons) {
			if (!beacon.dimension().equals(currentDimension)) continue;
			Vec3 relative = beacon.position().subtract(client.gameRenderer.mainCamera().position());
			var forward = client.gameRenderer.mainCamera().forwardVector();
			double depth = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
			Vec3 ndc = client.gameRenderer.projectPointToScreen(beacon.position());
			double rawX = (ndc.x + 1.0) * width * 0.5;
			double rawY = (1.0 - ndc.y) * height * 0.5;
			if (depth <= 0) {
				rawX = width - rawX;
				rawY = height - rawY;
			}
			boolean onScreen = depth > 0 && rawX >= 24 && rawX <= width - 24
				&& rawY >= 72 && rawY <= height - 104;
			int x;
			int y;
			if (onScreen) {
				x = (int)rawX;
				y = (int)rawY;
				drawCircle(g, x, y, 9, withAlpha(RED, 200));
				drawDiamond(g, x, y, 5, RED, false);
			} else {
				double dx = rawX - width / 2.0;
				double dy = rawY - height / 2.0;
				double scale = Math.min((width / 2.0 - 24) / Math.max(1, Math.abs(dx)),
					(height / 2.0 - 76) / Math.max(1, Math.abs(dy)));
				x = (int)(width / 2.0 + dx * scale);
				y = (int)(height / 2.0 + dy * scale);
				drawChevron(g, x, y, dx, dy, RED);
			}
			String label = "LOST / " + shortId(beacon.unitId()) + " / "
				+ Math.round(beacon.position().distanceTo(client.player.position())) + "m";
			int tx = x < width / 2 ? x + 14 : x - textWidth(client, label) - 14;
			int ty = Math.max(78, Math.min(height - 112, y - 5));
			if (claimLabel(tx, ty, textWidth(client, label), 20)) drawText(g, client, label, tx, ty, RED, true);
		}
	}

	private String blockCoordinates(Vec3 position) {
		return (int)Math.floor(position.x) + "," + (int)Math.floor(position.y) + "," + (int)Math.floor(position.z);
	}

	private String shortDimension(String dimension) {
		int separator = dimension.lastIndexOf('/');
		return (separator >= 0 ? dimension.substring(separator + 1) : dimension).replace("minecraft:", "").toUpperCase();
	}

	private Projection project(Minecraft client, DroneEntity drone, int width, int height, float partialTick) {
		Camera camera = client.gameRenderer.mainCamera();
		Vec3 anchor = drone.getPosition(partialTick).add(0, drone.getBbHeight() * 0.5, 0);
		var rotation = camera.rotation();
		ProjectionView view = new ProjectionView(width, height, camera.getFov(),
			rotation.x(), rotation.y(), rotation.z(), rotation.w(), visualState(drone));
		return projections.get(drone, client.level.getGameTime(), point(camera.position()), point(anchor),
			view, 1, 0, () -> projectUncached(client, drone, anchor, width, height));
	}

	private Projection projectUncached(Minecraft client, DroneEntity drone, Vec3 anchor, int width, int height) {
		Camera camera = client.gameRenderer.mainCamera();
		Vec3 relative = anchor.subtract(camera.position());
		var cameraForward = camera.forwardVector();
		double depth = relative.x * cameraForward.x() + relative.y * cameraForward.y()
			+ relative.z * cameraForward.z();
		Vec3 ndc = client.gameRenderer.projectPointToScreen(anchor);
		double rawX = (ndc.x + 1.0) * width * 0.5;
		double rawY = (1.0 - ndc.y) * height * 0.5;
		if (depth <= 0) {
			rawX = width - rawX;
			rawY = height - rawY;
		}
		double screenDistance = Math.hypot(rawX - width / 2.0, rawY - height / 2.0);
		return new Projection(drone, anchor, rawX, rawY, depth, ndc.z,
			relative.length(), screenDistance, relative.y);
	}

	private TargetProjection projectTarget(Minecraft client, LivingEntity target, int width,
		int height, float partialTick) {
		Camera camera = client.gameRenderer.mainCamera();
		Vec3 anchor = target.getPosition(partialTick).add(0, target.getBbHeight() * 0.55, 0);
		Vec3 relative = anchor.subtract(camera.position());
		var cameraForward = camera.forwardVector();
		double depth = relative.x * cameraForward.x() + relative.y * cameraForward.y()
			+ relative.z * cameraForward.z();
		Vec3 ndc = client.gameRenderer.projectPointToScreen(anchor);
		double x = (ndc.x + 1.0) * width * 0.5;
		double y = (1.0 - ndc.y) * height * 0.5;
		if (depth <= 0) {
			x = width - x;
			y = height - y;
		}
		return new TargetProjection(anchor, x, y, depth, relative.length());
	}

	private void drawMotionVector(GuiGraphicsExtractor g, Minecraft client, Projection p,
		int color, int width, int height) {
		Vec3 velocity = p.drone.getDeltaMovement();
		if (velocity.lengthSqr() < 0.0025) return;
		Vec3 endpoint = p.anchor.add(velocity.normalize().scale(Math.min(4.0, 1.5 + velocity.length() * 3.0)));
		Vec3 ndc = client.gameRenderer.projectPointToScreen(endpoint);
		int x = (int)Math.round((ndc.x + 1.0) * width * 0.5);
		int y = (int)Math.round((1.0 - ndc.y) * height * 0.5);
		double dx = x - p.x;
		double dy = y - p.y;
		double length = Math.hypot(dx, dy);
		if (!Double.isFinite(length) || length < 2.0) return;
		double scale = Math.min(14.0, length) / length;
		drawLine(g, (int)p.x, (int)p.y, (int)Math.round(p.x + dx * scale),
			(int)Math.round(p.y + dy * scale), withAlpha(color, 150));
	}

	private boolean visibleFromCamera(Minecraft client, Projection projection) {
		DroneEntity drone = projection.drone;
		return visibleWorldFromCamera(client, drone, projection.anchor, markerPriority(drone) <= 1,
			visualState(drone));
	}

	private boolean visibleWorldFromCamera(Minecraft client, Entity entity, Vec3 anchor,
		boolean critical, Object state) {
		if (!entity.isAlive() || entity.isRemoved() || entity.level() != client.level) return false;
		Vec3 camera = client.gameRenderer.mainCamera().position();
		return visibility.get(entity, client.level.getGameTime(), point(camera), point(anchor), state,
			critical ? 1 : 10, 1, () -> {
				HitResult hit = client.level.clip(new ClipContext(camera, anchor,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
				return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(anchor) <= 0.75;
			});
	}

	private VisualState visualState(DroneEntity drone) {
		return new VisualState(markerPriority(drone), drone.combatState().ordinal(), drone.combatTargetId(),
			drone.isAlive(), drone.isRemoved(), drone.recoveryLevel(), drone.isPowerLost());
	}

	private static VisorHudCache.Point point(Vec3 point) {
		return new VisorHudCache.Point(point.x, point.y, point.z);
	}

	private record VisualState(int priority, int combatState, int targetId, boolean alive,
		boolean removed, int recovery, boolean powerLost) {}
	private record AirspaceTerrainPoint(int x, int y, int z, int color, boolean loaded) {}
	private record ProjectionView(int width, int height, float fov, float qx, float qy, float qz,
		float qw, VisualState state) {}

	private int markerRadius(double distance) {
		return markerRadius(distance, false);
	}

	private int markerRadius(double distance, boolean leader) {
		int radius = distance <= 10 ? 4 : distance <= 28 ? 3 : 2;
		return leader ? Math.max(3, radius) : radius;
	}

	private void drawBoot(GuiGraphicsExtractor g, Minecraft client, int width, int height) {
		int x = width / 2 - 126;
		int y = height / 2 + 64;
		g.horizontalLine(x, x + 252, y, CYAN);
		drawText(g, client, "TACTICAL VISOR / LINK ESTABLISHED", x + 28, y + 8, CYAN);
	}

	private void drawMarker(GuiGraphicsExtractor g, int x, int y, int color, boolean leader,
		boolean visible, int radius) {
		if (leader) {
			drawDiamond(g, x, y, radius + 2, color, !visible);
			return;
		}
		if (visible) {
			g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, withAlpha(color, 38));
			g.fill(x - Math.max(1, radius - 1), y - Math.max(1, radius - 1),
				x + Math.max(2, radius), y + Math.max(2, radius), color);
			g.horizontalLine(x - radius - 2, x + radius + 2, y + radius + 6, withAlpha(color, 126));
		} else {
			g.outline(x - radius, y - radius, radius * 2, radius * 2, color);
			for (int i = -radius - 1; i <= radius + 1; i += 4) g.horizontalLine(x + i, x + i + 1,
				y + radius + 6, color);
		}
	}

	private void drawDiamond(GuiGraphicsExtractor g, int x, int y, int radius, int color, boolean faint) {
		if (!labelFree(x - radius, y - radius, radius * 2, radius * 2)) return;
		int actual = faint ? withAlpha(color, 96) : color;
		drawLine(g, x, y - radius, x + radius, y, actual);
		drawLine(g, x + radius, y, x, y + radius, actual);
		drawLine(g, x, y + radius, x - radius, y, actual);
		drawLine(g, x - radius, y, x, y - radius, actual);
	}

	private void drawChevron(GuiGraphicsExtractor g, int x, int y, double dx, double dy, int color) {
		if (!labelFree(x - 10, y - 10, 20, 20)) return;
		double length = Math.max(1.0, Math.hypot(dx, dy));
		double nx = dx / length;
		double ny = dy / length;
		int tipX = x + (int)Math.round(nx * 7);
		int tipY = y + (int)Math.round(ny * 7);
		int sideX = (int)Math.round(-ny * 5);
		int sideY = (int)Math.round(nx * 5);
		drawLine(g, tipX, tipY, x - (int)Math.round(nx * 4) + sideX, y - (int)Math.round(ny * 4) + sideY, color);
		drawLine(g, tipX, tipY, x - (int)Math.round(nx * 4) - sideX, y - (int)Math.round(ny * 4) - sideY, color);
	}

	private void drawCircle(GuiGraphicsExtractor g, int centerX, int centerY, int radius, int color) {
		if (!labelFree(centerX - radius, centerY - radius, radius * 2, radius * 2)) return;
		int previousX = centerX + radius;
		int previousY = centerY;
		for (int step = 1; step <= 96; step++) {
			double angle = step * Math.PI * 2.0 / 96.0;
			int x = centerX + (int)Math.round(Math.cos(angle) * radius);
			int y = centerY + (int)Math.round(Math.sin(angle) * radius);
			drawLine(g, previousX, previousY, x, y, color);
			previousX = x;
			previousY = y;
		}
	}

	private void drawLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		int[] clipped = VisorHudCache.clipLine(x1, y1, x2, y2, renderWidth, renderHeight, 32);
		if (clipped != null) drawFastLine(g, clipped[0], clipped[1], clipped[2], clipped[3], color);
	}

	private void drawFastLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		double length = Math.hypot(x2 - x1, y2 - y1);
		if (length < 0.5) {
			g.fill(x1, y1, x1 + 1, y1 + 1, color);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate((float)x1, (float)y1);
		g.pose().rotate((float)Math.atan2(y2 - y1, x2 - x1));
		g.fill(0, 0, Math.max(1, (int)Math.ceil(length)), 1, color);
		g.pose().popMatrix();
	}

	private void drawDashedLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
		int color, int dashLength) {
		int[] clipped = VisorHudCache.clipLine(x1, y1, x2, y2, renderWidth, renderHeight, 32);
		if (clipped == null) return;
		for (VisorHudCache.Dash dash : VisorHudCache.dashes(clipped[0], clipped[1], clipped[2], clipped[3], dashLength)) {
			g.pose().pushMatrix();
			g.pose().translate((float)dash.x1(), (float)dash.y1());
			g.pose().rotate((float)Math.atan2(dash.y2() - dash.y1(), dash.x2() - dash.x1()));
			g.fill(0, 0, Math.max(1, (int)Math.ceil(Math.hypot(dash.x2() - dash.x1(),
				dash.y2() - dash.y1()))), 1, color);
			g.pose().popMatrix();
		}
	}

	private boolean labelFree(int x, int y, int width, int height) {
		var box = new VisorCombatLayout.Box(x, y, width, height);
		return occupiedLabels.stream().noneMatch(box::intersects);
	}

	private boolean claimLabel(int x, int y, int width, int height) {
		var window = Minecraft.getInstance().getWindow();
		if (worldLabels >= 8 || x < 8 || y < 8 || x + width > window.getWidth() - 8 || y + height > window.getHeight() * 81 / 100
			|| !labelFree(x, y, width, height)) return false;
		occupiedLabels.add(new VisorCombatLayout.Box(x - 3, y - 3, width + 6, height + 6));
		worldLabels++;
		return true;
	}

	private void drawText(GuiGraphicsExtractor g, Minecraft client, String text, int x, int y,
		int color) {
		drawText(g, client, text, x, y, color, false);
	}

	private void drawText(GuiGraphicsExtractor g, Minecraft client, String text, int x, int y,
		int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().scale(TEXT_SCALE, TEXT_SCALE);
		g.text(client.font, text, Math.round(x / TEXT_SCALE), Math.round(y / TEXT_SCALE), color, shadow);
		g.pose().popMatrix();
	}

	private int textWidth(Minecraft client, String text) {
		return Math.round(client.font.width(text) * TEXT_SCALE);
	}

	private DroneEntity findById(int entityId) {
		DroneEntity drone = rosterById.get(entityId);
		if (drone != null && entityId == pinnedEntityId && pinnedUuid != null && !isPinned(drone)) return null;
		return drone == null || !drone.isAlive() || drone.isRemoved() || drone.level() != hudLevel ? null : drone;
	}

	private boolean leader(DroneEntity drone) {
		return !drone.cohortLeaderId().isBlank() && drone.unitId().equals(drone.cohortLeaderId());
	}

	private int markerPriority(DroneEntity drone) {
		return VisorHudPolicy.informationPriority(isPinned(drone),
			drone.groupId().equals(commandGroup), drone.combatActive(), drone.threatScore(),
			drone.recoveryLevel(), drone.batteryPercent(), drone.weaponPowerPercent(),
			drone.serviceReturnActive());
	}

	private String groupKey(DroneEntity drone) {
		if (!drone.groupId().isBlank()) return drone.groupId();
		if (!drone.missionId().isBlank()) return "MISSION/" + drone.missionId() + "/W" + drone.wingIndex();
		return "UNASSIGNED";
	}

	private String threatLabel(int threat) {
		return threat >= 28 ? "THREAT CRITICAL" : threat >= 14 ? "THREAT HIGH"
			: threat >= 8 ? "THREAT ELEVATED" : "THREAT NOMINAL";
	}

	private String conciseState(DroneEntity drone) {
		if (drone.batteryPercent() <= 0) return "POWER LOSS";
		if (drone.serviceReturnActive()) return "RTB";
		if (drone.combatActive()) return drone.combatState().label();
		if (drone.recoveryLevel() > 0) return "RECOVERY " + drone.recoveryLevel();
		if (drone.weaponPowerPercent() <= 10) return "WEAPON LOW";
		if (drone.batteryPercent() <= 20) return "FLIGHT LOW";
		if (drone.lowestSubsystemCondition() <= 420) return "MAINTENANCE DUE";
		return drone.operationalState().name();
	}

	private String contextSuffix(DroneEntity drone) {
		if (drone.combatActive()) {
			return " / " + drone.combatWeapon().name() + " / HEAT " + Math.round(drone.laserHeat() / 10.0f) + "%";
		}
		if (drone.hasActiveFieldOperation()) {
			return " / " + drone.fieldOperationType().name() + " " + drone.fieldProgress() + "%";
		}
		if (drone.role() == jp.morrowgear.drone.DroneRole.CARGO) return " / " + drone.cargoStatusLabel();
		if (drone.role() == jp.morrowgear.drone.DroneRole.ENGINEER) return " / " + drone.engineerStatusLabel();
		if (drone.role() == jp.morrowgear.drone.DroneRole.SALVAGE) return " / " + drone.salvageStatusLabel();
		if (drone.hasSecurityPatrol()) return " / " + drone.securityStatusLabel();
		return "";
	}

	private String wingImpact(List<DroneEntity> affected) {
		long wings = affected.stream().map(this::groupKey).distinct().count();
		return wings + (wings == 1 ? " WING AFFECTED" : " WINGS AFFECTED");
	}

	private int stateColor(DroneEntity drone) {
		if (drone.combatActive() || drone.threatScore() >= 28) return RED;
		if (drone.serviceReturnActive() || drone.recoveryLevel() > 0 || drone.batteryPercent() <= 20) return AMBER;
		return drone.isDocked() ? MUTED : GREEN;
	}

	private int withAlpha(int color, int alpha) {
		return color & 0x00FFFFFF | Math.max(0, Math.min(255, alpha)) << 24;
	}

	private String fit(Minecraft client, String text, int width) {
		return HmiArt.fitText(text, width, value -> textWidth(client, value));
	}

	private String shortId(String id) {
		return id.startsWith("MG-DRN-") ? id.substring(7) : id;
	}

	private boolean visorWorn(Minecraft client) {
		return client.player != null && client.player.getItemBySlot(EquipmentSlot.HEAD).is(MorrowgearDrone.TACTICAL_VISOR);
	}

	private record AlertLine(int color, String label, String response) {}
	private record Engagement(LivingEntity target, TargetProjection projection,
		List<DroneEntity> attackers) {}
	private record ScreenPoint(int x, int y, double depth) {}
	private record TargetProjection(Vec3 anchor, double x, double y, double depth, double distance) {
		boolean onScreen(int width, int height) {
			return x >= 24 && x <= width - 24 && y >= 72 && y <= height - 96;
		}
	}

	private record Projection(DroneEntity drone, Vec3 anchor, double x, double y, double depth,
		double ndcDepth,
		double distance, double screenDistance, double relativeY) {
		boolean onScreen(int width, int height) {
			return x >= 18 && x <= width - 18 && y >= 66 && y <= height - 86;
		}
	}
}

// Kept client-local; the focused test compiles this dependency-free helper directly.
final class VisorHudCache<V> {
	private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
	private final int capacity;
	private final java.util.IdentityHashMap<Object, Sample<V>> entries = new java.util.IdentityHashMap<>();
	VisorHudCache(int capacity) {
		if (capacity < 1) throw new IllegalArgumentException("capacity");
		this.capacity = capacity;
	}
	void clear() { entries.clear(); }
	int size() { return entries.size(); }
	V get(Object entity, long tick, Point camera, Point target, Object state, int ttl,
		double movementTolerance, java.util.function.Supplier<V> calculate) {
		Sample<V> previous = entries.get(entity);
		double limit = movementTolerance * movementTolerance;
		if (previous != null && tick >= previous.tick && tick - previous.tick < ttl
			&& java.util.Objects.equals(state, previous.state)
			&& camera.distanceSquared(previous.camera) <= limit
			&& target.distanceSquared(previous.target) <= limit) return previous.value;
		V value = calculate.get();
		if (!entries.containsKey(entity) && entries.size() >= capacity) entries.remove(entries.keySet().iterator().next());
		entries.put(entity, new Sample<>(tick, camera, target, state, value));
		return value;
	}
	static java.util.List<Dash> dashes(int x1, int y1, int x2, int y2, int dashLength) {
		double dx = (double)x2 - x1, dy = (double)y2 - y1, length = Math.hypot(dx, dy);
		int count = (int)Math.max(1, Math.min(64, Math.ceil((length + Math.max(1, dashLength))
			/ (2.0 * Math.max(1, dashLength)))));
		var result = new java.util.ArrayList<Dash>(count);
		for (int i = 0; i < count; i++) {
			double start = 2.0 * i / (2 * count - 1), end = (2.0 * i + 1) / (2 * count - 1);
			result.add(new Dash(x1 + dx * start, y1 + dy * start, x1 + dx * end, y1 + dy * end));
		}
		return java.util.List.copyOf(result);
	}
	static int[] clipLine(int x1, int y1, int x2, int y2, int width, int height, int margin) {
		double left = -Math.max(0, margin), top = left;
		double right = Math.max(1, width) + Math.max(0, margin);
		double bottom = Math.max(1, height) + Math.max(0, margin);
		double ax = x1, ay = y1, bx = x2, by = y2;
		for (int iteration = 0; iteration < 8; iteration++) {
			int a = outCode(ax, ay, left, top, right, bottom);
			int b = outCode(bx, by, left, top, right, bottom);
			if ((a | b) == 0) return new int[] {(int)Math.round(ax), (int)Math.round(ay),
				(int)Math.round(bx), (int)Math.round(by)};
			if ((a & b) != 0) return null;
			int outside = a != 0 ? a : b;
			double x, y;
			if ((outside & TOP) != 0) {
				if (by == ay) return null;
				x = ax + (bx - ax) * (top - ay) / (by - ay); y = top;
			} else if ((outside & BOTTOM) != 0) {
				if (by == ay) return null;
				x = ax + (bx - ax) * (bottom - ay) / (by - ay); y = bottom;
			} else if ((outside & RIGHT) != 0) {
				if (bx == ax) return null;
				y = ay + (by - ay) * (right - ax) / (bx - ax); x = right;
			} else {
				if (bx == ax) return null;
				y = ay + (by - ay) * (left - ax) / (bx - ax); x = left;
			}
			if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
			if (outside == a) { ax = x; ay = y; }
			else { bx = x; by = y; }
		}
		return null;
	}
	private static int outCode(double x, double y, double left, double top, double right, double bottom) {
		int code = 0;
		if (x < left) code |= LEFT; else if (x > right) code |= RIGHT;
		if (y < top) code |= TOP; else if (y > bottom) code |= BOTTOM;
		return code;
	}
	record Point(double x, double y, double z) {
		double distanceSquared(Point other) {
			double dx = x - other.x, dy = y - other.y, dz = z - other.z;
			return dx * dx + dy * dy + dz * dz;
		}
	}
	record Dash(double x1, double y1, double x2, double y2) {}
	private record Sample<V>(long tick, Point camera, Point target, Object state, V value) {}
}
