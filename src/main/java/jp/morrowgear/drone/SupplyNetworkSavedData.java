package jp.morrowgear.drone;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.SupplyNetworkRegistry.Configuration;
import jp.morrowgear.drone.SupplyNetworkRegistry.Job;
import jp.morrowgear.drone.SupplyNetworkRegistry.Route;
import jp.morrowgear.drone.SupplyNetworkRegistry.Stage;
import jp.morrowgear.drone.SupplyNetworkRegistry.Token;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class SupplyNetworkSavedData extends SavedData {
	private static final Codec<SupplyKind> KIND = enumCodec(SupplyKind.class);
	private static final Codec<Status> STATUS = enumCodec(Status.class);
	private static final Codec<Stage> STAGE = enumCodec(Stage.class);
	public static final Codec<Token> TOKEN_CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Token::id),
		Codec.LONG.validate(value -> value > 0 ? DataResult.success(value)
			: DataResult.error(() -> "Supply generation must be positive")).fieldOf("generation").forGetter(Token::generation)
	).apply(i, Token::new));
	private static final Codec<Route> ROUTE = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Route::owner),
		Codec.STRING.fieldOf("dimension").forGetter(Route::dimension),
		Codec.LONG.fieldOf("source").forGetter(Route::source),
		Codec.LONG.fieldOf("dock").forGetter(Route::dock)
	).apply(i, Route::new));
	private static final Codec<Rule> RULE = RecordCodecBuilder.create(i -> i.group(
		KIND.fieldOf("kind").forGetter(Rule::kind),
		Codec.intRange(0, SupplyNetworkPolicy.MAX_STOCK).fieldOf("minimum").forGetter(Rule::minimum),
		Codec.intRange(0, 100).fieldOf("priority").forGetter(Rule::priority)
	).apply(i, Rule::new));
	private static final Codec<Configuration> CONFIG = RecordCodecBuilder.create(i -> i.group(
		ROUTE.fieldOf("route").forGetter(Configuration::route),
		RULE.listOf().fieldOf("rules").forGetter(Configuration::rules),
		Codec.BOOL.fieldOf("enabled").forGetter(Configuration::enabled),
		Codec.LONG.fieldOf("revision").forGetter(Configuration::revision)
	).apply(i, Configuration::new));
	private static final Codec<Job> JOB = RecordCodecBuilder.create(i -> i.group(
		TOKEN_CODEC.fieldOf("token").forGetter(Job::token),
		ROUTE.fieldOf("route").forGetter(Job::route),
		Codec.LONG.fieldOf("revision").forGetter(Job::revision),
		UUIDUtil.STRING_CODEC.fieldOf("drone").forGetter(Job::drone),
		KIND.fieldOf("kind").forGetter(Job::kind),
		Codec.STRING.fieldOf("item").forGetter(Job::item),
		Codec.INT.fieldOf("requested").forGetter(Job::requested),
		Codec.INT.fieldOf("loaded").forGetter(Job::loaded),
		Codec.INT.fieldOf("delivered").forGetter(Job::delivered),
		STAGE.fieldOf("stage").forGetter(Job::stage),
		STATUS.fieldOf("status").forGetter(Job::status),
		Codec.LONG.fieldOf("assigned_tick").forGetter(Job::assignedTick),
		Codec.LONG.fieldOf("seen_tick").forGetter(Job::seenTick),
		Codec.LONG.optionalFieldOf("fallback", Long.MIN_VALUE).forGetter(Job::fallback)
	).apply(i, Job::new));
	public static final Codec<SupplyNetworkSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG.optionalFieldOf("generation", 0L).forGetter(d -> d.registry.generation()),
		CONFIG.listOf().optionalFieldOf("routes", List.of()).forGetter(d -> d.registry.configurations()),
		JOB.listOf().optionalFieldOf("jobs", List.of()).forGetter(d -> d.registry.jobs())
	).apply(i, SupplyNetworkSavedData::new));
	public static final SavedDataType<SupplyNetworkSavedData> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath("morrowgear_drone", "supply_network"),
		SupplyNetworkSavedData::new, CODEC, null);

	private final SupplyNetworkRegistry registry = new SupplyNetworkRegistry(this::setDirty);
	final java.util.Map<Route, Notice> notices = new java.util.HashMap<>();
	final java.util.Map<java.util.UUID, Long> ownerNoticeTicks = new java.util.HashMap<>();
	final java.util.LinkedHashMap<java.util.UUID, java.lang.ref.WeakReference<DroneEntity>> candidates = new java.util.LinkedHashMap<>();
	long lastTick = Long.MIN_VALUE;

	public SupplyNetworkSavedData() {}

	private SupplyNetworkSavedData(long generation, List<Configuration> configurations, List<Job> jobs) {
		registry.restore(generation, configurations, jobs);
	}

	public SupplyNetworkRegistry registry() { return registry; }
	public static SupplyNetworkSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
		return Codec.STRING.comapFlatMap(value -> {
			try { return DataResult.success(Enum.valueOf(type, value)); }
			catch (IllegalArgumentException invalid) { return DataResult.error(() -> "Unknown supply value: " + value); }
		}, Enum::name);
	}

	record Notice(Status status, long tick) {}
}
