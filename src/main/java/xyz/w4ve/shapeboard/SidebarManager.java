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

	private final Map<UUID, String> viewing = new HashMap<>();          // uuid -> shapeId
	private final Map<UUID, Map<String, Integer>> lastSent = new HashMap<>();

	public boolean isViewing(ServerPlayer player) {
		return viewing.containsKey(player.getUUID());
	}

	public void show(ServerPlayer player, Shape shape) {
		if (shape.id.equals(viewing.get(player.getUUID()))) return;
		hide(player);
		Objective fake = fakeObjective(shape);
		player.connection.send(new ClientboundSetObjectivePacket(fake, ClientboundSetObjectivePacket.METHOD_ADD));
		player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, fake));
		viewing.put(player.getUUID(), shape.id);
		lastSent.put(player.getUUID(), new HashMap<>());
		refresh(player, shape);
	}

	public void hide(ServerPlayer player) {
		if (viewing.remove(player.getUUID()) == null) return;
		lastSent.remove(player.getUUID());
		Objective fake = fakeObjective(null);
		player.connection.send(new ClientboundSetObjectivePacket(fake, ClientboundSetObjectivePacket.METHOD_REMOVE));
		// give back the real global sidebar the mod was covering
		Objective real = player.server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, real));
	}

	/** Forget without sending packets (disconnect). */
	public void forget(UUID uuid) {
		viewing.remove(uuid);
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
		Map<String, Integer> lines = buildLines(player, shape);
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
						e.getKey(), FAKE_OBJ, e.getValue(), Optional.empty(), Optional.empty()));
			}
		}
		lastSent.put(player.getUUID(), lines);
	}

	/**
	 * Top 15 for the shape's metric (breaks, places or the sum of both); if
	 * the viewer has a score but is not in the top, the last slot is given to
	 * them so they always see where they stand.
	 */
	private Map<String, Integer> buildLines(ServerPlayer player, Shape shape) {
		Map<String, Integer> totals = metricTotals(player.server.getScoreboard(), shape);
		Map<String, Integer> lines = new LinkedHashMap<>();
		if (totals.isEmpty()) return lines;

		List<Map.Entry<String, Integer>> entries = new ArrayList<>(totals.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		String self = player.getScoreboardName();
		boolean selfInTop = false;
		int limit = Math.min(MAX_LINES, entries.size());
		for (int i = 0; i < limit; i++) {
			Map.Entry<String, Integer> e = entries.get(i);
			lines.put(e.getKey(), e.getValue());
			if (e.getKey().equals(self)) selfInTop = true;
		}
		if (!selfInTop && totals.containsKey(self)) {
			if (lines.size() >= MAX_LINES) {
				String last = null;
				for (String k : lines.keySet()) last = k;
				lines.remove(last);
			}
			lines.put(self, totals.get(self));
		}
		return lines;
	}

	/** Per-player totals for whatever the shape's metric counts. */
	public static Map<String, Integer> metricTotals(Scoreboard sb, Shape shape) {
		Map<String, Integer> totals = new HashMap<>();
		if (shape.countsBreaks()) accumulate(sb, shape.breakObjective(), totals);
		if (shape.countsPlaces()) accumulate(sb, shape.placeObjective(), totals);
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
	private static Objective fakeObjective(Shape shape) {
		Component title = shape == null
				? Component.empty()
				: Component.literal(shape.displayName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		return new Scoreboard().addObjective(FAKE_OBJ, ObjectiveCriteria.DUMMY, title,
				ObjectiveCriteria.RenderType.INTEGER, false, null);
	}
}
