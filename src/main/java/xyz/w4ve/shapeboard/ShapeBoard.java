package xyz.w4ve.shapeboard;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ShapeBoard: trackea bloques rotos/colocados dentro de zonas con forma
 * ARBITRARIA (dibujada con líneas de bloques marcadores en el cielo) y
 * muestra un leaderboard en el sidebar, por jugador, al entrar a la zona.
 * 100% server-side: los jugadores no instalan nada.
 */
public class ShapeBoard implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("shapeboard");
	public static ShapeBoard INSTANCE;

	public MinecraftServer server;
	public final ShapeStore store = new ShapeStore();
	public final SidebarManager sidebar = new SidebarManager();
	private final Map<UUID, String> insideShape = new HashMap<>();
	private int tickCounter;

	@Override
	public void onInitialize() {
		INSTANCE = this;

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			store.load(s);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(store::save);
		ServerTickEvents.END_SERVER_TICK.register(this::tick);

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
				count(world, pos, player, true));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			insideShape.remove(handler.player.getUUID());
			sidebar.forget(handler.player.getUUID());
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				ShapeBoardCommand.register(dispatcher));

		LOGGER.info("ShapeBoard ready");
	}

	/** Punto común de conteo (break desde el evento, place desde el mixin). */
	public void count(Level world, BlockPos pos, Player player, boolean isBreak) {
		if (server == null || world.isClientSide()) return;
		Shape shape = store.shapeAt(world.dimension().location().toString(), pos.getX(), pos.getZ());
		if (shape == null || pos.getY() >= shape.yLines) return;
		Objective obj = getOrCreateObjective(shape, isBreak);
		server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), obj).add(1);
	}

	public Objective getOrCreateObjective(Shape shape, boolean isBreak) {
		Scoreboard sb = server.getScoreboard();
		String name = isBreak ? shape.breakObjective() : shape.placeObjective();
		Objective obj = sb.getObjective(name);
		if (obj == null) {
			Component display = Component.literal(shape.displayName + (isBreak ? " Digs" : " Placed"))
					.withStyle(ChatFormatting.GOLD);
			obj = sb.addObjective(name, ObjectiveCriteria.DUMMY, display,
					ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		return obj;
	}

	private void tick(MinecraftServer server) {
		if (++tickCounter % 20 != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Shape shape = store.shapeAt(player.level().dimension().location().toString(),
					Mth.floor(player.getX()), Mth.floor(player.getZ()));
			String prev = insideShape.get(player.getUUID());
			String cur = shape == null ? null : shape.id;
			if (!Objects.equals(prev, cur)) {
				if (prev != null) onExit(player, store.byId(prev));
				if (shape != null) onEnter(player, shape);
				if (cur == null) insideShape.remove(player.getUUID());
				else insideShape.put(player.getUUID(), cur);
			}
		}
		sidebar.tick(server, store);
	}

	private void onEnter(ServerPlayer player, Shape shape) {
		if (isFakePlayer(player)) return;
		player.sendSystemMessage(prefix()
				.append(Component.literal("You entered ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(shape.displayName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
				.append(Component.literal(". Blocks you mine or place here count toward the leaderboard.")
						.withStyle(ChatFormatting.WHITE)));
		if (store.isHidden(player.getUUID())) {
			player.sendSystemMessage(prefix()
					.append(Component.literal("Sidebar is off. Run ").withStyle(ChatFormatting.GRAY))
					.append(command("/shapeboard show"))
					.append(Component.literal(" to see the leaderboard.").withStyle(ChatFormatting.GRAY)));
		} else {
			sidebar.show(player, shape);
			player.sendSystemMessage(prefix()
					.append(Component.literal("Sidebar enabled. Run ").withStyle(ChatFormatting.GRAY))
					.append(command("/shapeboard hide"))
					.append(Component.literal(" to hide it.").withStyle(ChatFormatting.GRAY)));
		}
	}

	private void onExit(ServerPlayer player, Shape shape) {
		if (isFakePlayer(player)) return;
		sidebar.hide(player);
		if (shape != null) {
			player.sendSystemMessage(prefix()
					.append(Component.literal("You left " + shape.displayName + ".").withStyle(ChatFormatting.GRAY)));
		}
	}

	/** Shape en la que está parado ahora mismo el jugador (o null). */
	public Shape currentShape(ServerPlayer player) {
		return store.shapeAt(player.level().dimension().location().toString(),
				Mth.floor(player.getX()), Mth.floor(player.getZ()));
	}

	/** El sidebar/los mensajes no aplican a fake players (bots de carpet). */
	public static boolean isFakePlayer(ServerPlayer player) {
		return player.getClass() != ServerPlayer.class;
	}

	public static MutableComponent prefix() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("ShapeBoard").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	public static Component command(String cmd) {
		return Component.literal(cmd).withStyle(style -> style
				.withColor(ChatFormatting.YELLOW)
				.withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to run"))));
	}
}
