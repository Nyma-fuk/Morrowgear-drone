package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.SolarServiceStationEntity;
import jp.morrowgear.drone.VisorHudPolicy;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class VisorHudOverlay implements HudElement {
	private static final int CYAN = 0xFF2DE7EB;
	private static final int GREEN = 0xFF63EFB0;
	private static final int AMBER = 0xFFFFB94E;
	private static final int RED = 0xFFFF5C68;
	private static final int WHITE = 0xFFF2FDFF;
	private static final int MUTED = 0xFF8EB3BA;
	private static final int PANEL = 0xC7071317;
	private static final int PANEL_SOFT = 0xBD051115;
	private static final int LINE = 0xB9345B62;
	private static final int CACHE_TICKS = 5;
	private static final int FOCUS_DWELL_TICKS = 12;
	private static final float TEXT_SCALE = 2.0f;

	private final VisorClientConfig config = VisorClientConfig.load();
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

	void tick(Minecraft client) {
		if (client.player == null || client.level == null) return;
		boolean worn = visorWorn(client);
		if (worn && !wornLastTick) bootTicks = 7;
		wornLastTick = worn;
		if (bootTicks > 0) bootTicks--;
		if (!worn || !config.enabled) return;
		refreshRoster(client);
		updateFocus(client);
	}

	void toggleEnabled(Minecraft client) {
		config.enabled = !config.enabled;
		config.save();
	}

	void togglePin(Minecraft client) {
		if (!visorWorn(client)) return;
		pinnedEntityId = pinnedEntityId >= 0 ? -1 : focusEntityId;
	}

	void cycleContext(Minecraft client) {
		if (!visorWorn(client)) return;
		refreshRoster(client);
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
		if (client.player == null || client.level == null || !config.enabled || !visorWorn(client)) return;
		refreshRoster(client);
		int width = client.getWindow().getWidth();
		int height = client.getWindow().getHeight();
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		int wingCount = (int)roster.stream().map(this::groupKey).distinct().count();
		int actionable = (int)roster.stream().filter(d -> markerPriority(d) <= 1).count();
		int combat = (int)roster.stream().filter(DroneEntity::combatActive).count();
		VisorHudPolicy.DisplayPlan plan = VisorHudPolicy.displayPlan(roster.size(), wingCount,
			actionable, combat, width, height);
		float pixelScale = 1.0f / Math.max(1, client.getWindow().getGuiScale());
		g.pose().pushMatrix();
		g.pose().scale(pixelScale, pixelScale);
		try {
			List<Projection> contacts = proximityContacts(client, width, height, partialTick, plan);
			int clusters = clusterCount(contacts, width, height);
			if (bootTicks > 0) drawBoot(g, client, width, height);
			drawFleetStrip(g, client, width, contacts.size(), clusters, plan);
			drawCommandContext(g, client, width, plan);
			drawInspectionContext(g, client, width);
			drawOperationOverview(g, client, width);
			drawSafeZone(g, width, height);
			drawEngagements(g, client, contacts, width, height, partialTick, plan);
			drawProximity(g, client, contacts, width, height, plan);
			drawPowerLostBeacons(g, client, width, height);
			drawActionRail(g, client, width, height, plan);
		} finally {
			g.pose().popMatrix();
		}
	}

	private void refreshRoster(Minecraft client) {
		if (client.level == null || client.player == null || client.player.tickCount < cacheUntil) return;
		cacheUntil = client.player.tickCount + CACHE_TICKS;
		roster = List.copyOf(client.level.getEntitiesOfClass(DroneEntity.class,
			new AABB(client.player.blockPosition()).inflate(256),
			drone -> drone.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())));
		solarStations = List.copyOf(client.level.getEntitiesOfClass(SolarServiceStationEntity.class,
			new AABB(client.player.blockPosition()).inflate(512),
			station -> station.ownerId().equals(client.player.getUUID())));
		if (commandGroup.isBlank() || roster.stream().noneMatch(d -> d.groupId().equals(commandGroup))) {
			commandGroup = roster.stream().map(DroneEntity::groupId).filter(id -> !id.isBlank()).sorted()
				.findFirst().orElse("");
		}
		if (pinnedEntityId >= 0 && roster.stream().noneMatch(d -> d.getId() == pinnedEntityId)) pinnedEntityId = -1;
	}

	private void updateFocus(Minecraft client) {
		int width = client.getWindow().getWidth();
		int height = client.getWindow().getHeight();
		Projection best = roster.stream().map(drone -> project(client, drone, width, height, 1.0f))
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
		List<Projection> candidates = roster.stream()
			.filter(d -> d.distanceTo(client.player) <= config.proximityRange || d.getId() == pinnedEntityId)
			.map(d -> project(client, d, width, height, partialTick))
			.sorted(Comparator.comparingInt((Projection p) -> markerPriority(p.drone))
				.thenComparingDouble(Projection::distance)).toList();
		if (plan.detail() == VisorHudPolicy.Detail.UNIT) return candidates;
		Map<String, Projection> representatives = new LinkedHashMap<>();
		List<Projection> selected = new ArrayList<>();
		for (Projection p : candidates) {
			boolean exception = markerPriority(p.drone) <= 1 || p.drone.getId() == pinnedEntityId;
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
		int x = 42;
		int y = 28;
		int stripWidth = VisorHudPolicy.topStripWidth(width);
		g.fill(x, y, x + stripWidth, y + 44, PANEL);
		g.horizontalLine(x, x + stripWidth, y, CYAN);
		drawText(g, client, "MORROWGEAR", x + 10, y + 12, WHITE, true);
		int cursor = x + 128;
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
		drawText(g, client, "ONLINE " + roster.size(), cursor + 18, y + 12, CYAN);
		drawText(g, client, "ACTIVE " + active, cursor + 152, y + 12, GREEN);
		drawText(g, client, "DOCK " + docked, cursor + 290, y + 12, MUTED);
		drawText(g, client, "RTB " + service, cursor + 396, y + 12, service > 0 ? AMBER : MUTED);
		String right = (width < 1280 ? "OPS " + operations + " / ALERT " + alerts
			: "OPS " + operations + "  /  REC " + recovery + "  /  ALERT " + alerts
				+ "  /  GRID " + solarStations.size() + ":" + solarActive + "/"
				+ solarStations.size() * 3 + (solarWaiting > 0 ? " Q" + solarWaiting : "")
				+ (solarLow > 0 ? " LOW" + solarLow : "") + "  /  " + threatLabel(threat)
				+ "  /  " + plan.detail().name())
			+ "  /  " + contacts + (clusters > 0 ? " C / " + clusters : " C");
		drawText(g, client, right, x + stripWidth - textWidth(client, right) - 10, y + 12, MUTED);
	}

	private void drawCommandContext(GuiGraphicsExtractor g, Minecraft client, int width,
		VisorHudPolicy.DisplayPlan plan) {
		DroneEntity pinned = findById(pinnedEntityId);
		String contextGroup = pinned == null ? commandGroup : pinned.groupId();
		List<DroneEntity> members = roster.stream().filter(d -> d.groupId().equals(contextGroup)).toList();
		int x = 42;
		int y = 86;
		int panelWidth = VisorHudPolicy.sidePanelWidth(width);
		int panelHeight = plan.compactContext() ? 122 : 158;
		g.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
		g.fill(x, y, x + 2, y + panelHeight, CYAN);
		g.horizontalLine(x, x + panelWidth, y, LINE);
		drawText(g, client, "COMMAND CONTEXT" + (pinned == null ? "" : " / PINNED"), x + 13, y + 12, CYAN);
		String mission = members.stream().map(DroneEntity::missionId).filter(id -> !id.isBlank())
			.findFirst().orElseGet(() -> members.stream().map(d -> d.operationalState().name())
				.findFirst().orElse("STANDBY"));
		String title = (contextGroup.isBlank() ? "UNASSIGNED" : contextGroup) + " // " + mission;
		drawText(g, client, fit(client, title, panelWidth - 26), x + 13, y + 42, WHITE, true);
		int active = (int)members.stream().filter(d -> !d.isDocked()).count();
		int detached = (int)members.stream().filter(d -> d.combatActive() || d.serviceReturnActive()).count();
		int recovery = (int)members.stream().filter(d -> d.recoveryLevel() > 0).count();
		DroneEntity leader = members.stream().filter(this::leader).findFirst().orElse(members.isEmpty() ? null : members.getFirst());
		int healthy = (int)members.stream().filter(d -> d.recoveryLevel() <= 0 && d.batteryPercent() > 20).count();
		int link = members.isEmpty() ? 0 : healthy * 100 / members.size();
		String formation = members.size() <= 1 ? "SOLO" : members.size() <= 4 ? "FORMATION DELTA" : "FORMATION LINKED";
		String activeText = active + " ACTIVE" + (detached > 0 ? " / " + detached + " DETACHED" : "");
		drawText(g, client, activeText, x + 13, y + 80, detached > 0 ? AMBER : WHITE);
		drawText(g, client, formation, x + panelWidth - textWidth(client, formation) - 13, y + 80, MUTED);
		if (plan.compactContext()) return;
		String lead = "LEAD " + (leader == null ? "--" : shortId(leader.unitId()));
		drawText(g, client, lead + (recovery > 0 ? " / REC " + recovery : ""), x + 13, y + 116,
			recovery > 0 ? AMBER : MUTED);
		String linkText = "LINK " + link + "%";
		drawText(g, client, linkText, x + panelWidth - textWidth(client, linkText) - 13, y + 116,
			link >= 90 ? GREEN : link >= 60 ? AMBER : RED);
	}

	private void drawSafeZone(GuiGraphicsExtractor g, int width, int height) {
		int radius = Math.max(58, (int)(Math.min(width, height) * VisorHudPolicy.SAFE_ZONE_RATIO));
		drawCircle(g, width / 2, height / 2, radius, 0x1E9FD9DD);
	}

	private void drawOperationOverview(GuiGraphicsExtractor g, Minecraft client, int width) {
		DroneEntity source = roster.stream().filter(drone -> !drone.operationSummary().isBlank())
			.findFirst().orElse(null);
		if (source == null) return;
		int sideWidth = VisorHudPolicy.sidePanelWidth(width);
		int available = Math.max(420, width - (sideWidth + 84) * 2);
		int panelWidth = Math.min(920, available);
		int x = (width - panelWidth) / 2;
		int y = 86;
		int panelHeight = source.operationFronts().isBlank() ? 44 : 76;
		int color = source.operationSummary().contains("CRITICAL") ? RED : AMBER;
		g.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
		g.fill(x, y, x + 3, y + panelHeight, color);
		g.horizontalLine(x, x + panelWidth, y, color);
		drawText(g, client, fit(client, source.operationSummary(), panelWidth - 26),
			x + 13, y + 10, color, true);
		if (!source.operationFronts().isBlank()) {
			drawText(g, client, fit(client, source.operationFronts(), panelWidth - 26),
				x + 13, y + 42, WHITE);
		}
	}

	private void drawInspectionContext(GuiGraphicsExtractor g, Minecraft client, int width) {
		DroneEntity drone = findById(pinnedEntityId >= 0 ? pinnedEntityId : focusEntityId);
		if (drone == null) return;
		int panelWidth = Math.min(380, Math.max(300, width * 380 / 1920));
		int x = width - 42 - panelWidth;
		int y = 86;
		int panelHeight = 210;
		int stateColor = stateColor(drone);
		g.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
		g.fill(x + panelWidth - 2, y, x + panelWidth, y + panelHeight, stateColor);
		g.horizontalLine(x, x + panelWidth, y, LINE);
		drawText(g, client, pinnedEntityId >= 0 ? "INSPECTION / PINNED" : "INSPECTION / FOCUS",
			x + 13, y + 12, stateColor);
		String identity = shortId(drone.unitId()) + " / " + drone.role().displayName();
		drawText(g, client, fit(client, identity, panelWidth - 26), x + 13, y + 42, WHITE, true);
		String state = conciseState(drone) + contextSuffix(drone);
		drawText(g, client, fit(client, state, panelWidth - 26), x + 13, y + 68, stateColor);
		drawText(g, client, fit(client, drone.dataLinkStatus(), panelWidth - 26),
			x + 13, y + 91, drone.dataLinkStatus().contains("WAIT") ? AMBER : MUTED);
		drawTelemetryBar(g, client, x + 13, y + 120, "FLT", drone.batteryPercent(), CYAN, panelWidth - 26);
		drawTelemetryBar(g, client, x + 13, y + 142, "WPN", drone.weaponPowerPercent(), AMBER, panelWidth - 26);
		int structure = Math.round(drone.getHealth() * 100.0f / Math.max(1.0f, drone.getMaxHealth()));
		drawTelemetryBar(g, client, x + 13, y + 164, "STR", structure, GREEN, panelWidth - 26);
		drawTelemetryBar(g, client, x + 13, y + 186, "SYS", drone.lowestSubsystemCondition() / 10,
			CYAN, panelWidth - 26);
	}

	private void drawTelemetryBar(GuiGraphicsExtractor g, Minecraft client, int x, int y,
		String label, int value, int color, int width) {
		int clamped = Math.max(0, Math.min(100, value));
		int barX = x + 48;
		int barWidth = Math.max(40, width - 96);
		drawText(g, client, label, x, y - 7, MUTED);
		g.fill(barX, y, barX + barWidth, y + 6, 0xB3263B3F);
		g.fill(barX, y, barX + Math.round(barWidth * clamped / 100.0f), y + 6,
			clamped <= 10 ? RED : clamped <= 25 ? AMBER : color);
		String text = clamped + "%";
		drawText(g, client, text, x + width - textWidth(client, text), y - 7, WHITE);
	}

	private void drawProximity(GuiGraphicsExtractor g, Minecraft client, List<Projection> projected,
		int width, int height, VisorHudPolicy.DisplayPlan plan) {
		List<Projection> onScreen = projected.stream().filter(p -> p.depth > 0 && p.onScreen(width, height)).toList();
		Map<String, List<Projection>> clusters = cluster(onScreen);
		int budget = plan.markerBudget();
		int labels = 0;
		int drawn = 0;
		for (List<Projection> group : clusters.values()) {
			Projection p = group.getFirst();
			boolean pinned = group.stream().anyMatch(c -> c.drone.getId() == pinnedEntityId);
			boolean focused = group.stream().anyMatch(c -> c.drone.getId() == focusEntityId);
			boolean actionable = group.stream().anyMatch(c -> markerPriority(c.drone) <= 1);
			if (!actionable && drawn >= budget) continue;
			if (!actionable) drawn++;
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
				&& labels++ < plan.labelBudget()) {
				String role = actionable ? conciseState(p.drone)
					: leader(p.drone) ? "LEAD" : p.drone.role().id().toUpperCase();
				drawWorldTag(g, client, p, shortId(p.drone.unitId()) + " / " + role + " / "
					+ Math.round(p.distance) + "m", color);
			}
			if (Math.abs(p.relativeY) >= 4.0) {
				drawText(g, client, p.relativeY > 0 ? "^" : "v", (int)p.x - 3,
					(int)p.y + (p.relativeY > 0 ? -17 : 11), color);
			}
			if ((pinned || selectedLeader) && group.size() == 1) drawMotionVector(g, client, p, color, width, height);
		}
		drawOffscreen(g, client, projected, width, height, plan.edgeBudget());
	}

	private void drawEngagements(GuiGraphicsExtractor g, Minecraft client, List<Projection> contacts,
		int width, int height, float partialTick, VisorHudPolicy.DisplayPlan plan) {
		Map<Integer, List<DroneEntity>> byTarget = new LinkedHashMap<>();
		roster.stream().filter(DroneEntity::combatActive).filter(d -> d.combatTargetId() >= 0)
			.sorted(Comparator.comparingInt(DroneEntity::combatTargetId).thenComparing(DroneEntity::unitId))
			.forEach(d -> byTarget.computeIfAbsent(d.combatTargetId(), ignored -> new ArrayList<>()).add(d));
		List<Engagement> engagements = new ArrayList<>();
		for (Map.Entry<Integer, List<DroneEntity>> entry : byTarget.entrySet()) {
			Entity entity = client.level.getEntity(entry.getKey());
			if (!(entity instanceof LivingEntity target) || !target.isAlive()) continue;
			TargetProjection projection = projectTarget(client, target, width, height, partialTick);
			engagements.add(new Engagement(target, projection, entry.getValue()));
		}
		engagements.sort(Comparator.comparingDouble(e -> e.projection.distance));
		int displayLimit = VisorHudPolicy.engagementDisplayLimit(plan.detail());
		for (int index = 0; index < Math.min(displayLimit, engagements.size()); index++) {
			drawEngagement(g, client, engagements.get(index), index + 1, width, height, partialTick);
		}
	}

	private void drawEngagement(GuiGraphicsExtractor g, Minecraft client, Engagement engagement,
		int ordinal, int width, int height, float partialTick) {
		TargetProjection target = engagement.projection;
		String assignment = assignmentLabel(engagement.attackers);
		String targetName = engagement.target.getName().getString().toUpperCase();
		int health = Math.round(engagement.target.getHealth() * 100.0f
			/ Math.max(1.0f, engagement.target.getMaxHealth()));
		String label = String.format("ENG-%02d / %s > %s / HP %d%%",
			ordinal, assignment, targetName, Math.max(0, health));
		boolean visible = visibleWorldFromCamera(client, target.anchor);
		int color = visible ? RED : withAlpha(RED, 105);
		if (target.depth <= 0 || !target.onScreen(width, height)) {
			drawTargetEdge(g, client, target, label, width, height, color);
			return;
		}
		int radius = target.distance <= 12 ? 11 : target.distance <= 36 ? 9 : 7;
		drawTargetBracket(g, (int)target.x, (int)target.y, radius, color, !visible);
		if (visible) {
			for (DroneEntity attacker : engagement.attackers.stream().limit(8).toList()) {
				Projection drone = project(client, attacker, width, height, partialTick);
				if (drone.depth > 0 && drone.onScreen(width, height)) {
					drawDashedLine(g, (int)drone.x, (int)drone.y, (int)target.x, (int)target.y,
						withAlpha(RED, 72), 7);
				}
			}
		}
		int tagX = (int)target.x + radius + 8;
		int tagY = (int)target.y - 18;
		int tagWidth = textWidth(client, label) + 16;
		g.fill(tagX, tagY, tagX + tagWidth, tagY + 32, PANEL_SOFT);
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
		drawText(g, client, compact, tx, Math.max(40, Math.min(height - 108, y - 6)), color, true);
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

	private int clusterCount(List<Projection> contacts, int width, int height) {
		return (int)cluster(contacts.stream().filter(p -> p.depth > 0 && p.onScreen(width, height)).toList())
			.values().stream().filter(group -> group.size() > 1).count();
	}

	private void drawCluster(GuiGraphicsExtractor g, Minecraft client, List<Projection> group,
		Projection p, int color) {
		drawDiamond(g, (int)p.x, (int)p.y, 5, color, false);
		String name = (p.drone.groupId().isBlank() ? "UNASSIGNED" : p.drone.groupId()) + " x" + group.size()
			+ "  " + Math.round(group.stream().mapToDouble(Projection::distance).average().orElse(p.distance)) + "m";
		drawText(g, client, name, (int)p.x + 12, (int)p.y - 6, color);
	}

	private void drawWorldTag(GuiGraphicsExtractor g, Minecraft client, Projection p, String label, int color) {
		int x = (int)p.x + 17;
		int y = (int)p.y - 18;
		int tagWidth = textWidth(client, label) + 16;
		g.fill(x, y, x + tagWidth, y + 32, PANEL_SOFT);
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
		drawText(g, client, text, tx, ty, color);
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
		if (lowPower > 0) alerts.add(new AlertLine(AMBER, "P1 / FLIGHT RESERVE x" + lowPower, "RTB ASSIGNED"));
		if (recovery > 0) alerts.add(new AlertLine(AMBER, "P1 / RECOVERY x" + recovery,
			wingImpact(roster.stream().filter(d -> d.recoveryLevel() > 0).toList())));
		if (weaponLow > 0) alerts.add(new AlertLine(AMBER, "P1 / WEAPON RESERVE x" + weaponLow, "RELIEF ROTATION"));
		if (!maintenance.isEmpty()) alerts.add(new AlertLine(AMBER,
			"P1 / SUBSYSTEM SERVICE x" + maintenance.size(), wingImpact(maintenance)));
		if (alerts.isEmpty()) return;
		alerts = alerts.stream().limit(VisorHudPolicy.visibleAlertCount(alerts.size(), plan.alertRows())).toList();
		if (alerts.isEmpty()) return;
		int railWidth = VisorHudPolicy.actionRailWidth(width);
		int x = (width - railWidth) / 2;
		int railHeight = 12 + alerts.size() * 32;
		int y = height - 96 - railHeight;
		g.fill(x, y, x + railWidth, y + railHeight, 0xD20E1312);
		g.fill(x, y, x + 3, y + railHeight, alerts.getFirst().color);
		g.horizontalLine(x, x + railWidth, y, alerts.getFirst().color);
		for (int i = 0; i < alerts.size(); i++) {
			AlertLine line = alerts.get(i);
			int lineY = y + 10 + i * 32;
			drawText(g, client, line.label, x + 12, lineY, line.color, i == 0);
			drawText(g, client, line.response, x + railWidth - textWidth(client, line.response) - 12,
				lineY, MUTED);
		}
	}

	private void drawPowerLostBeacons(GuiGraphicsExtractor g, Minecraft client, int width, int height) {
		String currentDimension = client.level.dimension().toString();
		for (PowerLostBeaconStore.Beacon beacon : PowerLostBeaconStore.snapshot(client.level.getGameTime())) {
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
			drawText(g, client, label, tx, Math.max(78, Math.min(height - 112, y - 5)), RED, true);
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
		return visibleWorldFromCamera(client, projection.anchor);
	}

	private boolean visibleWorldFromCamera(Minecraft client, Vec3 anchor) {
		Vec3 camera = client.gameRenderer.mainCamera().position();
		HitResult hit = client.level.clip(new ClipContext(camera, anchor,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
		return hit.getType() == HitResult.Type.MISS
			|| hit.getLocation().distanceToSqr(anchor) <= 0.75;
	}

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
		int actual = faint ? withAlpha(color, 96) : color;
		drawLine(g, x, y - radius, x + radius, y, actual);
		drawLine(g, x + radius, y, x, y + radius, actual);
		drawLine(g, x, y + radius, x - radius, y, actual);
		drawLine(g, x - radius, y, x, y - radius, actual);
	}

	private void drawChevron(GuiGraphicsExtractor g, int x, int y, double dx, double dy, int color) {
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
		int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
		for (int i = 0; i <= Math.max(1, steps); i++) {
			int x = x1 + (x2 - x1) * i / Math.max(1, steps);
			int y = y1 + (y2 - y1) * i / Math.max(1, steps);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	private void drawDashedLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
		int color, int dashLength) {
		int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
		for (int i = 0; i <= Math.max(1, steps); i++) {
			if ((i / Math.max(1, dashLength)) % 2 != 0) continue;
			int x = x1 + (x2 - x1) * i / Math.max(1, steps);
			int y = y1 + (y2 - y1) * i / Math.max(1, steps);
			g.fill(x, y, x + 1, y + 1, color);
		}
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
		return roster.stream().filter(d -> d.getId() == entityId).findFirst().orElse(null);
	}

	private boolean leader(DroneEntity drone) {
		return !drone.cohortLeaderId().isBlank() && drone.unitId().equals(drone.cohortLeaderId());
	}

	private int markerPriority(DroneEntity drone) {
		return VisorHudPolicy.informationPriority(drone.getId() == pinnedEntityId,
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
		if (textWidth(client, text) <= width) return text;
		String value = text;
		while (value.length() > 3 && textWidth(client, value + "...") > width) value = value.substring(0, value.length() - 1);
		return value + "...";
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
