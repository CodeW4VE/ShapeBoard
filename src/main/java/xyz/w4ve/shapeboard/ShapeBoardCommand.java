package xyz.w4ve.shapeboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShapeBoardCommand {
	private static final SuggestionProvider<CommandSourceStack> SHAPE_IDS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(
					ShapeBoard.INSTANCE.store.all().stream().map(s -> s.id), builder);
	private static final SuggestionProvider<CommandSourceStack> BLOCKS = (ctx, builder) ->
			SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);
	private static final List<String> METRIC_VALUES = List.of("break", "place", "both");
	private static final SuggestionProvider<CommandSourceStack> METRICS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(METRIC_VALUES, builder);
	private static final SuggestionProvider<CommandSourceStack> ON_OFF = (ctx, builder) ->
			SharedSuggestionProvider.suggest(List.of("on", "off"), builder);

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("shapeboard")
				.then(Commands.literal("create").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word())
								.then(Commands.argument("marker", ResourceLocationArgument.id()).suggests(BLOCKS)
										.then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
												.executes(ctx -> create(ctx, null, null))
												.then(Commands.argument("x", IntegerArgumentType.integer())
														.then(Commands.argument("z", IntegerArgumentType.integer())
																.executes(ctx -> create(ctx,
																		IntegerArgumentType.getInteger(ctx, "x"),
																		IntegerArgumentType.getInteger(ctx, "z")))))))))
				.then(Commands.literal("rename").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(ShapeBoardCommand::rename))))
				.then(Commands.literal("createbox").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word())
								.then(Commands.argument("x1", IntegerArgumentType.integer())
										.then(Commands.argument("z1", IntegerArgumentType.integer())
												.then(Commands.argument("x2", IntegerArgumentType.integer())
														.then(Commands.argument("z2", IntegerArgumentType.integer())
																.then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
																		.executes(ShapeBoardCommand::createBox))))))))
				.then(Commands.literal("metric").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.then(Commands.argument("value", StringArgumentType.word()).suggests(METRICS)
										.executes(ShapeBoardCommand::metric))))
				.then(Commands.literal("total").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.then(Commands.argument("value", StringArgumentType.word()).suggests(ON_OFF)
										.executes(ShapeBoardCommand::total))))
				.then(Commands.literal("delete").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.executes(ShapeBoardCommand::delete)))
				.then(Commands.literal("contains").requires(s -> s.hasPermission(2))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.then(Commands.argument("x", IntegerArgumentType.integer())
										.then(Commands.argument("z", IntegerArgumentType.integer())
												.executes(ShapeBoardCommand::contains)))))
				.then(Commands.literal("list").executes(ShapeBoardCommand::list))
				.then(Commands.literal("info")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.executes(ShapeBoardCommand::info)))
				.then(Commands.literal("top")
						.executes(ctx -> top(ctx, null))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(SHAPE_IDS)
								.executes(ctx -> top(ctx, StringArgumentType.getString(ctx, "id")))))
				.then(Commands.literal("hide").executes(ctx -> setHidden(ctx, true)))
				.then(Commands.literal("show").executes(ctx -> setHidden(ctx, false))));
	}

	/** Shared id validation for create/createbox. */
	private static boolean validNewId(CommandSourceStack source, String id) {
		if (!id.matches("[a-z0-9_-]{1,24}")) {
			source.sendFailure(Component.literal("Shape id must be 1-24 chars of [a-z0-9_-]"));
			return false;
		}
		if (ShapeBoard.INSTANCE.store.byId(id) != null) {
			source.sendFailure(Component.literal("A shape with id '" + id + "' already exists"));
			return false;
		}
		return true;
	}

	/** Rectangular zone with no marker blocks: two corners + ceiling Y. */
	private static int createBox(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ShapeBoard mod = ShapeBoard.INSTANCE;
		String id = StringArgumentType.getString(ctx, "id").toLowerCase();
		if (!validNewId(source, id)) return 0;
		int x1 = IntegerArgumentType.getInteger(ctx, "x1"), z1 = IntegerArgumentType.getInteger(ctx, "z1");
		int x2 = IntegerArgumentType.getInteger(ctx, "x2"), z2 = IntegerArgumentType.getInteger(ctx, "z2");
		int y = IntegerArgumentType.getInteger(ctx, "y");
		int xMin = Math.min(x1, x2), xMax = Math.max(x1, x2);
		int zMin = Math.min(z1, z2), zMax = Math.max(z1, z2);
		long area = (long) (xMax - xMin + 1) * (zMax - zMin + 1);
		if (area > ShapeScanner.MAX_GRID) {
			source.sendFailure(Component.literal("Box is too big (" + String.format("%,d", area) + " columns)"));
			return 0;
		}
		java.util.Map<Integer, int[]> cols = new java.util.HashMap<>();
		for (int x = xMin; x <= xMax; x++) {
			cols.put(x, new int[]{zMin, zMax});
		}
		Shape shape = new Shape(id, id, "box", y, source.getLevel().dimension().location().toString(),
				xMin, xMax, zMin, zMax, cols);
		mod.store.add(shape);
		mod.store.save(source.getServer());
		mod.getOrCreateObjective(shape, true);
		mod.getOrCreateObjective(shape, false);
		final String out = "Box shape '" + id + "' created: " + String.format("%,d", area)
				+ " columns, x[" + xMin + ".." + xMax + "] z[" + zMin + ".." + zMax + "], counts below y" + y
				+ ". Objectives: " + shape.breakObjective() + " / " + shape.placeObjective()
				+ ". Rename it with /shapeboard rename " + id + " <display name>";
		source.sendSuccess(() -> ShapeBoard.prefix().append(Component.literal(out).withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int create(CommandContext<CommandSourceStack> ctx, Integer seedX, Integer seedZ) {
		CommandSourceStack source = ctx.getSource();
		ShapeBoard mod = ShapeBoard.INSTANCE;
		String id = StringArgumentType.getString(ctx, "id").toLowerCase();
		ResourceLocation markerId = ResourceLocationArgument.getId(ctx, "marker");
		int y = IntegerArgumentType.getInteger(ctx, "y");

		if (!validNewId(source, id)) return 0;
		Block marker = BuiltInRegistries.BLOCK.getOptional(markerId).orElse(null);
		if (marker == null) {
			source.sendFailure(Component.literal("Unknown block: " + markerId));
			return 0;
		}
		ServerLevel level = source.getLevel();
		int sx = seedX != null ? seedX : Mth.floor(source.getPosition().x);
		int sz = seedZ != null ? seedZ : Mth.floor(source.getPosition().z);

		source.sendSystemMessage(ShapeBoard.prefix().append(Component.literal(
				"Scanning " + markerId.getPath() + " lines at y=" + y + " from (" + sx + ", " + sz + ")...")
				.withStyle(ChatFormatting.GRAY)));
		ShapeScanner.ScanResult result;
		long t0 = System.currentTimeMillis();
		try {
			result = ShapeScanner.scan(level, marker, y, sx, sz);
		} catch (ShapeScanner.ScanException e) {
			source.sendFailure(Component.literal("Scan failed: " + e.getMessage()));
			return 0;
		}

		Shape shape = new Shape(id, id, markerId.toString(), y, level.dimension().location().toString(),
				result.xMin(), result.xMax(), result.zMin(), result.zMax(), result.cols());
		mod.store.add(shape);
		mod.store.save(source.getServer());
		mod.getOrCreateObjective(shape, true);
		mod.getOrCreateObjective(shape, false);

		long ms = System.currentTimeMillis() - t0;
		StringBuilder msg = new StringBuilder("Shape '" + id + "' created in " + ms + " ms: "
				+ String.format("%,d", result.area()) + " columns inside, outline of "
				+ String.format("%,d", result.wallBlocks()) + " blocks");
		if (!result.bridged().isEmpty()) {
			int[] g = result.bridged().get(0);
			msg.append("; auto-bridged ").append(result.bridged().size())
					.append(" gap block(s), first at (").append(g[0]).append(", ").append(g[1]).append(")");
		}
		msg.append(". Objectives: ").append(shape.breakObjective()).append(" / ").append(shape.placeObjective())
				.append(". Rename it with /shapeboard rename ").append(id).append(" <display name>");
		final String out = msg.toString();
		source.sendSuccess(() -> ShapeBoard.prefix().append(Component.literal(out).withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int rename(CommandContext<CommandSourceStack> ctx) {
		Shape shape = ShapeBoard.INSTANCE.store.byId(StringArgumentType.getString(ctx, "id"));
		if (shape == null) return unknownShape(ctx);
		String name = StringArgumentType.getString(ctx, "name");
		shape.displayName = name;
		ShapeBoard.INSTANCE.store.save(ctx.getSource().getServer());
		Scoreboard sb = ctx.getSource().getServer().getScoreboard();
		Objective br = sb.getObjective(shape.breakObjective());
		if (br != null) br.setDisplayName(Component.literal(name + " Digs").withStyle(ChatFormatting.GOLD));
		Objective pl = sb.getObjective(shape.placeObjective());
		if (pl != null) pl.setDisplayName(Component.literal(name + " Placed").withStyle(ChatFormatting.GOLD));
		ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
				.append(Component.literal("Shape '" + shape.id + "' is now displayed as \"" + name + "\"")
						.withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int metric(CommandContext<CommandSourceStack> ctx) {
		Shape shape = ShapeBoard.INSTANCE.store.byId(StringArgumentType.getString(ctx, "id"));
		if (shape == null) return unknownShape(ctx);
		String value = StringArgumentType.getString(ctx, "value").toLowerCase();
		if (!METRIC_VALUES.contains(value)) {
			ctx.getSource().sendFailure(Component.literal("Metric must be one of: break, place, both"));
			return 0;
		}
		shape.metric = value;
		ShapeBoard.INSTANCE.store.save(ctx.getSource().getServer());
		String what = switch (value) {
			case "place" -> "blocks placed";
			case "both" -> "blocks broken + placed";
			default -> "blocks broken";
		};
		ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
				.append(Component.literal("'" + shape.id + "' now ranks by " + what
						+ ". Sidebar and /shapeboard top follow it.").withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int total(CommandContext<CommandSourceStack> ctx) {
		Shape shape = ShapeBoard.INSTANCE.store.byId(StringArgumentType.getString(ctx, "id"));
		if (shape == null) return unknownShape(ctx);
		String value = StringArgumentType.getString(ctx, "value").toLowerCase();
		boolean on;
		if (value.equals("on")) on = true;
		else if (value.equals("off")) on = false;
		else {
			ctx.getSource().sendFailure(Component.literal("Use: on or off"));
			return 0;
		}
		shape.showTotal = on;
		ShapeBoard.INSTANCE.store.save(ctx.getSource().getServer());
		// re-render the sidebar for anyone currently watching this shape
		ShapeBoard.INSTANCE.sidebar.onScoreChange(ctx.getSource().getServer(), ShapeBoard.INSTANCE.store, shape.id);
		ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
				.append(Component.literal("Total line " + (on ? "shown" : "hidden") + " on '" + shape.id
						+ "' sidebar.").withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!ShapeBoard.INSTANCE.store.remove(id)) return unknownShape(ctx);
		ShapeBoard.INSTANCE.store.save(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
				.append(Component.literal("Shape '" + id + "' deleted. Scoreboard objectives were kept; "
						+ "remove them with /scoreboard objectives remove if you want them gone.")
						.withStyle(ChatFormatting.GREEN)), true);
		return 1;
	}

	private static int contains(CommandContext<CommandSourceStack> ctx) {
		Shape shape = ShapeBoard.INSTANCE.store.byId(StringArgumentType.getString(ctx, "id"));
		if (shape == null) return unknownShape(ctx);
		int x = IntegerArgumentType.getInteger(ctx, "x");
		int z = IntegerArgumentType.getInteger(ctx, "z");
		boolean in = shape.contains(x, z);
		ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
				.append(Component.literal("(" + x + ", " + z + ") is " + (in ? "INSIDE" : "outside") + " of "
						+ shape.displayName).withStyle(in ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);
		return in ? 1 : 0;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		List<Shape> shapes = ShapeBoard.INSTANCE.store.all();
		if (shapes.isEmpty()) {
			ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
					.append(Component.literal("No shapes yet. Create one with /shapeboard create")
							.withStyle(ChatFormatting.GRAY)), false);
			return 0;
		}
		for (Shape s : shapes) {
			ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
					.append(Component.literal(s.id).withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" \"" + s.displayName + "\" — " + String.format("%,d", s.area())
							+ " columns, " + s.marker + " @ y" + s.yLines + ", " + s.dimension)
							.withStyle(ChatFormatting.WHITE)), false);
		}
		return shapes.size();
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		Shape s = ShapeBoard.INSTANCE.store.byId(StringArgumentType.getString(ctx, "id"));
		if (s == null) return unknownShape(ctx);
		ctx.getSource().sendSuccess(() -> ShapeBoard.prefix()
				.append(Component.literal(s.id + " \"" + s.displayName + "\": " + String.format("%,d", s.area())
						+ " columns inside, bounds x[" + s.xMin + ".." + s.xMax + "] z[" + s.zMin + ".." + s.zMax
						+ "], counts below y" + s.yLines + " in " + s.dimension + ", ranks by " + s.metric
						+ ". Objectives: " + s.breakObjective() + " / " + s.placeObjective())
						.withStyle(ChatFormatting.WHITE)), false);
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> ctx, String idArg) {
		CommandSourceStack source = ctx.getSource();
		ShapeBoard mod = ShapeBoard.INSTANCE;
		Shape shape = null;
		if (idArg != null) {
			shape = mod.store.byId(idArg);
			if (shape == null) return unknownShape(ctx);
		} else if (source.getEntity() instanceof ServerPlayer player) {
			shape = mod.currentShape(player);
		}
		if (shape == null && mod.store.all().size() == 1) shape = mod.store.all().get(0);
		if (shape == null) {
			source.sendFailure(Component.literal("Which shape? Use /shapeboard top <id> (see /shapeboard list)"));
			return 0;
		}

		Scoreboard sb = source.getServer().getScoreboard();
		// ranked by the shape's metric (break, place or both)
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(SidebarManager.metricTotals(sb, shape).entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		final Shape fs = shape;
		source.sendSuccess(() -> Component.literal("— " + fs.displayName + " —")
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		if (entries.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Nothing counted here yet.")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
		}
		for (int i = 0; i < Math.min(10, entries.size()); i++) {
			Map.Entry<String, Integer> e = entries.get(i);
			final int rank = i + 1;
			source.sendSuccess(() -> Component.literal(" #" + rank + " ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(e.getKey() + "  ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(String.format("%,d", e.getValue())).withStyle(ChatFormatting.GREEN)),
					false);
		}
		// keep this exact wording: external tools (our Discord bot) parse it
		final long dug = objectiveSum(sb, shape.breakObjective());
		final long placed = objectiveSum(sb, shape.placeObjective());
		final int players = entries.size();
		source.sendSuccess(() -> Component.literal("Total: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.format("%,d", dug)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
				.append(Component.literal(" blocks dug by " + players + " players, "
						+ String.format("%,d", placed) + " placed").withStyle(ChatFormatting.GRAY)), false);
		return 1;
	}

	private static long objectiveSum(Scoreboard sb, String objectiveName) {
		Objective obj = sb.getObjective(objectiveName);
		if (obj == null) return 0;
		long total = 0;
		for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
			if (!e.owner().startsWith("#")) total += e.value();
		}
		return total;
	}

	private static int setHidden(CommandContext<CommandSourceStack> ctx, boolean hidden) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (CommandSyntaxException e) {
			source.sendFailure(Component.literal("Players only"));
			return 0;
		}
		ShapeBoard mod = ShapeBoard.INSTANCE;
		mod.store.setHidden(player.getUUID(), hidden);
		mod.store.save(source.getServer());
		if (hidden) {
			mod.sidebar.hide(player);
			player.sendSystemMessage(ShapeBoard.prefix()
					.append(Component.literal("Sidebar hidden. Run ").withStyle(ChatFormatting.GRAY))
					.append(ShapeBoard.command("/shapeboard show"))
					.append(Component.literal(" to enable it again.").withStyle(ChatFormatting.GRAY)));
		} else {
			Shape here = mod.currentShape(player);
			if (here != null) mod.sidebar.show(player, here);
			player.sendSystemMessage(ShapeBoard.prefix()
					.append(Component.literal("Sidebar enabled.").withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int unknownShape(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendFailure(Component.literal("Unknown shape id (see /shapeboard list)"));
		return 0;
	}
}
