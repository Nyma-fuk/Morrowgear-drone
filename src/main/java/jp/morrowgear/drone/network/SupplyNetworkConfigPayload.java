package jp.morrowgear.drone.network;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Fixed-size ownerless request; the authenticated sender is always the configuration owner. */
public record SupplyNetworkConfigPayload(long source, long dock, boolean enabled, List<Rule> rules)
	implements CustomPacketPayload {
	public static final Type<SupplyNetworkConfigPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath("morrowgear_drone", "supply_network_config"));
	public static final StreamCodec<ByteBuf, SupplyNetworkConfigPayload> CODEC = new StreamCodec<>() {
		@Override
		public SupplyNetworkConfigPayload decode(ByteBuf buffer) {
			long source = buffer.readLong(), dock = buffer.readLong();
			boolean enabled = buffer.readBoolean();
			List<Rule> rules = new ArrayList<>();
			for (SupplyKind kind : wireKinds()) rules.add(new Rule(kind, buffer.readInt(), buffer.readInt()));
			return new SupplyNetworkConfigPayload(source, dock, enabled, rules);
		}

		@Override
		public void encode(ByteBuf buffer, SupplyNetworkConfigPayload payload) {
			buffer.writeLong(payload.source()).writeLong(payload.dock()).writeBoolean(payload.enabled());
			for (SupplyKind kind : wireKinds()) {
				Rule rule = payload.rules().stream().filter(r -> r.kind() == kind).findFirst().orElseThrow();
				buffer.writeInt(rule.minimum()).writeInt(rule.priority());
			}
		}
	};

	public SupplyNetworkConfigPayload {
		rules = List.copyOf(rules);
		if (rules.size() != SupplyKind.values().length || rules.stream().map(Rule::kind).distinct().count() != rules.size())
			throw new IllegalArgumentException("Exactly one rule per supply kind is required");
	}

	private static SupplyKind[] wireKinds() {
		return new SupplyKind[] { SupplyKind.FUEL, SupplyKind.GUN, SupplyKind.LASER, SupplyKind.MISSILE, SupplyKind.REPAIR };
	}

	/** Parent receiver must call on the server thread, never on the network IO thread. */
	public void apply(ServerPlayer player) {
		try {
			if (!enabled) {
				if (!SupplyNetworkRuntime.disable(player, BlockPos.of(dock)))
					throw new IllegalArgumentException("No owned supply route");
			} else SupplyNetworkRuntime.configure(player, BlockPos.of(source), BlockPos.of(dock), rules, true);
		} catch (IllegalArgumentException | IllegalStateException rejected) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] SUPPLY / " + rejected.getMessage()));
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
