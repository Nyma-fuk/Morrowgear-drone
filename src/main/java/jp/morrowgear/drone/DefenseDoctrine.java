package jp.morrowgear.drone;

import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class DefenseDoctrine {
	private static volatile List<DefenseProtocol> protocolOrder = List.of(
		DefenseProtocol.PLAYER_PROTECTION,
		DefenseProtocol.FORMATION_COHESION,
		DefenseProtocol.SELF_PRESERVATION
	);

	private DefenseDoctrine() {
	}

	static List<DefenseProtocol> protocolOrder() {
		return protocolOrder;
	}

	static void setProtocolOrder(List<DefenseProtocol> order) {
		if (order == null || order.size() != DefenseProtocol.values().length
			|| new LinkedHashSet<>(order).size() != DefenseProtocol.values().length) {
			throw new IllegalArgumentException("Every defense protocol must appear exactly once");
		}
		protocolOrder = List.copyOf(order);
	}

	static boolean requiresDefensiveMovement(ThreatAssessment.Snapshot threat) {
		return threat.directThreat()
			|| threat.enemyCount() > 0 && threat.band().ordinal() >= ThreatBand.HIGH.ordinal();
	}

	static boolean requiresFormationMovement(ThreatAssessment.Snapshot threat) {
		return threat.enemyCount() > 0 && threat.band().ordinal() >= ThreatBand.GUARDED.ordinal();
	}

	static Vec3 apply(Vec3 nominal, ServerPlayer owner, DroneEntity drone,
		ThreatAssessment.Snapshot threat, Vec3 threatPosition, Vec3 interceptPosition,
		boolean protectionActive, int index, int count, long tick) {
		Vec3 target = nominal;
		boolean formationApplied = false;
		for (DefenseProtocol protocol : protocolOrder) {
			switch (protocol) {
				case PLAYER_PROTECTION -> {
					if (protectionActive) {
						Vec3 intercept = interceptPosition != null
							? interceptPosition
							: DefensiveFormation.shieldAnchor(owner.position(), threatPosition);
						target = intercept;
					}
				}
				case FORMATION_COHESION -> {
					if (protectionActive) {
						target = target.add(DefensiveFormation.wallOffset(index, count, owner.position(), threatPosition));
						formationApplied = true;
					} else if (requiresFormationMovement(threat)) {
						target = drone.mode() == DroneMode.WAYPOINT
							? target.add(DefensiveFormation.escortOffset(index, count, owner.position(), threatPosition))
							: DefensiveFormation.guardArc(owner.position(), threatPosition, index, count);
						formationApplied = true;
					}
				}
				case SELF_PRESERVATION -> {
					boolean criticalHealth = drone.getHealth() / drone.getMaxHealth() < 0.35f;
					boolean immediateDanger = drone.position().distanceTo(threatPosition) < 4.0;
					if (criticalHealth && immediateDanger) {
						Vec3 evasion = DefensiveFormation.evasionOffset(owner.position(), threatPosition, index, tick);
						// Evasion remains bounded around the higher-priority protection or formation anchor.
						target = target.add(evasion.scale(formationApplied ? 0.65 : 1.0));
					}
				}
			}
		}
		return target;
	}
}
