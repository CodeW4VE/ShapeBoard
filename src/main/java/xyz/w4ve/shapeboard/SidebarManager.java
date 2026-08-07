package xyz.w4ve.shapeboard;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A truly per-player sidebar, no teams or colors involved: a FAKE objective
 * (existing only on that player's client) is sent through targeted scoreboard
 * packets. Hiding it restores the real objective of the SIDEBAR slot. This
 * way two players sharing a team color never leak the board to each other.
 */
public final class SidebarManager {
	private static final String FAKE_OBJ = "shapeboard_view";
	private static final int MAX_LINES = 15;
	/** Owner key for the aggregate line. '$' can't appear in a player name, so it never collides. */
	private static final String TOTAL_KEY = "sb$total";
	/** Placeholder line: a sidebar with zero lines is not drawn by the client at all. */
	private static final String EMPTY_KEY = "sb$empty";
	private static final Component TOTAL_LABEL =
			Component.literal("» Total").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	private static final Component EMPTY_LABEL =
			Component.literal("nothing counted yet").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	/** The Total line renders with its own label; player lines use their name (empty display). */
	private static Optional<Component> displayFor(String owner) {
		if (owner.equals(TOTAL_KEY)) return Optional.of(TOTAL_LABEL);
		if (owner.equals(EMPTY_KEY)) return Optional.of(EMPTY_LABEL);
		return Optional.empty();
	}

	private final Map<UUID, String> viewing = new HashMap<>();          // uuid -> shapeId
	/** Metric the title was rendered with, so a view change re-sends the objective. */
	private final Map<UUID, String> viewingMetric = new HashMap<>();
	private final Map<UUID, Map<String, Integer>> lastSent = new HashMap<>();

	public boolean isViewing(ServerPlayer player) {
		return viewing.containsKey(player.getUUID());
	}

	public void show(ServerPlayer player, Shape shape) {
		if (shape.id.equals(viewing.get(player.getUUID()))) return;
		hide(player);
		String metric = viewMetric(player, shape);
		Objective fake = fakeObjective(shape, metric);
		player.connection.send(new ClientboundSetObjectivePacket(fake, ClientboundSetObjectivePacket.METHOD_ADD));
		player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, fake));
		viewing.put(player.getUUID(), shape.id);
		viewingMetric.put(player.getUUID(), metric);
		lastSent.put(player.getUUID(), new HashMap<>());
		refresh(player, shape);
	}

	/** Re-send the objective so a new title (view or prefix/suffix change) lands. */
	private void retitle(ServerPlayer player, Shape shape, String metric) {
		Objective fake = fakeObjective(shape, metric);
		player.connection.send(new ClientboundSetObjectivePacket(fake, ClientboundSetObjectivePacket.METHOD_CHANGE));
		viewingMetric.put(player.getUUID(), metric);
	}

	/** Metric this player is watching on that shape. */
	private static String viewMetric(ServerPlayer player, Shape shape) {
		return ShapeBoard.INSTANCE.store.viewFor(player.getUUID(), shape);
	}

	public void hide(ServerPlayer player) {
		viewingMetric.remove(player.getUUID());
		if (viewing.remove(player.getUUID()) == null) return;
		lastSent.remove(player.getUUID());
		Objective fake = fakeObjective(null, "break");
		player.connection.send(new ClientboundSetObjectivePacket(fake, ClientboundSetObjectivePacket.METHOD_REMOVE));
		// give back the real global sidebar the mod was covering
		Objective real = player.level().getServer().getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, real));
	}

	/** Forget without sending packets (disconnect). */
	public void forget(UUID uuid) {
		viewing.remove(uuid);
		viewingMetric.remove(uuid);
		lastSent.remove(uuid);
	}

	/**
	 * Immediate refresh when a block is counted: the vanilla sidebar feels
	 * instant because every score change travels right away; this replicates
	 * that for whoever is watching that shape (refresh only sends diffs, so
	 * it costs one packet per block per viewer).
	 */
	public void onScoreChange(MinecraftServer server, ShapeStore store, String shapeId) {
		if (viewing.isEmpty()) return;
		Shape shape = store.byId(shapeId);
		if (shape == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (shapeId.equals(viewing.get(player.getUUID()))) refresh(player, shape);
		}
	}

	/** Re-send the title to a player if they happen to be looking at that shape. */
	public void retitleIfViewing(ServerPlayer player, Shape shape) {
		if (!shape.id.equals(viewing.get(player.getUUID()))) return;
		retitle(player, shape, viewMetric(player, shape));
	}

	/** Push an update to one viewer right now (after they change their view). */
	public void refreshNow(ServerPlayer player) {
		String shapeId = viewing.get(player.getUUID());
		if (shapeId == null) return;
		Shape shape = ShapeBoard.INSTANCE.store.byId(shapeId);
		if (shape != null) refresh(player, shape);
	}

	/** Periodic fallback refresh: only sends what changed. */
	public void tick(MinecraftServer server, ShapeStore store) {
		if (viewing.isEmpty()) return;
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			String shapeId = viewing.get(player.getUUID());
			if (shapeId == null) continue;
			Shape shape = store.byId(shapeId);
			if (shape == null) {
				hide(player);
				continue;
			}
			refresh(player, shape);
		}
	}

	private void refresh(ServerPlayer player, Shape shape) {
		String metric = viewMetric(player, shape);
		if (!metric.equals(viewingMetric.get(player.getUUID()))) retitle(player, shape, metric);
		Map<String, Integer> lines = buildLines(player, shape, metric);
		Map<String, Integer> sent = lastSent.get(player.getUUID());
		if (sent == null || sent.equals(lines)) return;

		for (String owner : sent.keySet()) {
			if (!lines.containsKey(owner)) {
				player.connection.send(new ClientboundResetScorePacket(owner, FAKE_OBJ));
			}
		}
		for (Map.Entry<String, Integer> e : lines.entrySet()) {
			Integer prev = sent.get(e.getKey());
			if (prev == null || !prev.equals(e.getValue())) {
				player.connection.send(new ClientboundSetScorePacket(
						e.getKey(), FAKE_OBJ, e.getValue(), displayFor(e.getKey()), Optional.empty()));
			}
		}
		lastSent.put(player.getUUID(), lines);
	}

	/**
	 * Top 15 for the shape's metric (breaks, places or the sum of both); if
	 * the viewer has a score but is not in the top, the last slot is given to
	 * them so they always see where they stand.
	 */
	private Map<String, Integer> buildLines(ServerPlayer player, Shape shape, String metric) {
		Map<String, Integer> totals = metricTotals(player.level().getServer().getScoreboard(), shape, metric);
		Map<String, Integer> lines = new LinkedHashMap<>();
		if (totals.isEmpty()) {
			// A brand new shape has no scores at all, and an empty sidebar is
			// invisible: show the zeroed board so it is obvious it is working.
			if (shape.showTotal) lines.put(TOTAL_KEY, 0);
			lines.put(EMPTY_KEY, 0);
			return lines;
		}

		List<Map.Entry<String, Integer>> entries = new ArrayList<>(totals.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		// Aggregate line first; its score = sum, so the client sorts it to the top.
		if (shape.showTotal) {
			int sum = 0;
			for (int v : totals.values()) sum += v;
			lines.put(TOTAL_KEY, sum);
		}
		int playerLimit = shape.showTotal ? MAX_LINES - 1 : MAX_LINES;

		String self = player.getScoreboardName();
		boolean selfInTop = false;
		int limit = Math.min(playerLimit, entries.size());
		for (int i = 0; i < limit; i++) {
			Map.Entry<String, Integer> e = entries.get(i);
			lines.put(e.getKey(), e.getValue());
			if (e.getKey().equals(self)) selfInTop = true;
		}
		if (!selfInTop && totals.containsKey(self)) {
			if (lines.size() >= MAX_LINES) {
				// evict the lowest player line, never the Total
				String last = null;
				for (String k : lines.keySet()) if (!k.equals(TOTAL_KEY)) last = k;
				if (last != null) lines.remove(last);
			}
			lines.put(self, totals.get(self));
		}
		return lines;
	}

	/** Per-player totals for whatever the shape's metric counts. */
	public static Map<String, Integer> metricTotals(Scoreboard sb, Shape shape) {
		return metricTotals(sb, shape, shape.metric);
	}

	/** Same, but for an explicit metric (a viewer may be watching another one). */
	public static Map<String, Integer> metricTotals(Scoreboard sb, Shape shape, String metric) {
		Map<String, Integer> totals = new HashMap<>();
		if (Shape.countsBreaks(metric)) accumulate(sb, shape.breakObjective(), totals);
		if (Shape.countsPlaces(metric)) accumulate(sb, shape.placeObjective(), totals);
		return totals;
	}

	private static void accumulate(Scoreboard sb, String objectiveName, Map<String, Integer> totals) {
		Objective obj = sb.getObjective(objectiveName);
		if (obj == null) return;
		for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
			if (e.owner().startsWith("#")) continue;
			totals.merge(e.owner(), e.value(), Integer::sum);
		}
	}

	/** Disposable fake objective, only used to serialize the packets. */
	private static Objective fakeObjective(Shape shape, String metric) {
		Component title = shape == null
				? Component.empty()
				: Component.literal(shape.title(metric)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		return new Scoreboard().addObjective(FAKE_OBJ, ObjectiveCriteria.DUMMY, title,
				ObjectiveCriteria.RenderType.INTEGER, false, null);
	}
}
