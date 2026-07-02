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
 * Sidebar por jugador de verdad, sin teams ni colores: se manda un objetivo
 * FALSO (solo existe en el cliente de ese jugador) con paquetes de scoreboard
 * dirigidos. Al ocultarlo se restaura el objetivo real del slot SIDEBAR.
 * Así dos jugadores con el mismo color de team no se filtran el board.
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
		// devolverle el sidebar global real que el mod tapó
		Objective real = player.server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, real));
	}

	/** Olvido sin paquetes (desconexión). */
	public void forget(UUID uuid) {
		viewing.remove(uuid);
		lastSent.remove(uuid);
	}

	/** Refresco periódico: solo manda lo que cambió. */
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
	 * Top 15 del objetivo _break de la shape; si el que mira tiene score y no
	 * entra en el top, se le hace hueco en la última línea para que se vea.
	 */
	private Map<String, Integer> buildLines(ServerPlayer player, Shape shape) {
		Scoreboard sb = player.server.getScoreboard();
		Objective obj = sb.getObjective(shape.breakObjective());
		Map<String, Integer> lines = new LinkedHashMap<>();
		if (obj == null) return lines;

		List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(obj));
		entries.removeIf(e -> e.owner().startsWith("#"));
		entries.sort((a, b) -> Integer.compare(b.value(), a.value()));

		String self = player.getScoreboardName();
		boolean selfInTop = false;
		int limit = Math.min(MAX_LINES, entries.size());
		for (int i = 0; i < limit; i++) {
			PlayerScoreEntry e = entries.get(i);
			lines.put(e.owner(), e.value());
			if (e.owner().equals(self)) selfInTop = true;
		}
		if (!selfInTop) {
			for (PlayerScoreEntry e : entries) {
				if (e.owner().equals(self)) {
					if (lines.size() >= MAX_LINES) {
						String last = null;
						for (String k : lines.keySet()) last = k;
						lines.remove(last);
					}
					lines.put(self, e.value());
					break;
				}
			}
		}
		return lines;
	}

	/** Objetivo falso desechable, solo para serializar los paquetes. */
	private static Objective fakeObjective(Shape shape) {
		Component title = shape == null
				? Component.empty()
				: Component.literal(shape.displayName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		return new Scoreboard().addObjective(FAKE_OBJ, ObjectiveCriteria.DUMMY, title,
				ObjectiveCriteria.RenderType.INTEGER, false, null);
	}
}
