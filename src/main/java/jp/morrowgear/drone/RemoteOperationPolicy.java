package jp.morrowgear.drone;

final class RemoteOperationPolicy {
	static final int ENTITY_TICKING_TICKET_RADIUS = 2;
	// Radius 2 is the minimum level that keeps a remote entity ticking. Radius 1
	// can keep data loaded but leaves an accepted controller command unable to move.
	static final int DATA_LINK_TICKET_RADIUS = 2;

	private RemoteOperationPolicy() {}

	static boolean keepsChunkActive(boolean ownerOnlineInDimension, boolean docked,
		boolean serviceReturn, boolean combatFlight, boolean fieldOperation,
		boolean securityPatrol, boolean patrolRoute, DroneMode mode) {
		if (!ownerOnlineInDimension || docked) return false;
		return serviceReturn || combatFlight || fieldOperation || securityPatrol || patrolRoute
			|| mode == DroneMode.WAYPOINT || mode == DroneMode.FOLLOW || mode == DroneMode.DOCK;
	}

	static boolean keepsDataLinkChunkActive(boolean ownerOnlineInDimension, int relayHops) {
		return ownerOnlineInDimension && relayHops > 0;
	}
}
