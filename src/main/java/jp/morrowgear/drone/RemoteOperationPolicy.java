package jp.morrowgear.drone;

final class RemoteOperationPolicy {
	static final int ENTITY_TICKING_TICKET_RADIUS = 2;

	private RemoteOperationPolicy() {}

	static boolean keepsChunkActive(boolean ownerOnlineInDimension, boolean docked,
		boolean serviceReturn, boolean combatFlight, boolean fieldOperation,
		boolean securityPatrol, boolean patrolRoute, DroneMode mode) {
		if (!ownerOnlineInDimension || docked) return false;
		return serviceReturn || combatFlight || fieldOperation || securityPatrol || patrolRoute
			|| mode == DroneMode.WAYPOINT || mode == DroneMode.FOLLOW || mode == DroneMode.DOCK;
	}
}
