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

public final class ShapeBoardCommand {
	private static final SuggestionProvider<CommandSourceStack> SHAPE_IDS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(
					ShapeBoard.INSTANCE.store.all().stream().map(s -> s.id), builder);
	private static final SuggestionProvider<CommandSourceStack> BLOCKS = (ctx, builder) ->
			SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);

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

	private static int create(CommandContext<CommandSourceStack> ctx, Integer seedX, Integer seedZ) {
		CommandSourceStack source = ctx.getSource();
		ShapeBoard mod = ShapeBoard.INSTANCE;
		String id = StringArgumentType.getString(ctx, "id").toLowerCase();
		ResourceLocation markerId = ResourceLocationArgument.getId(ctx, "marker");
		int y = IntegerArgumentType.getInteger(ctx, "y");

		if (!id.matches("[a-z0-9_-]{1,24}")) {
			source.sendFailure(Component.literal("Shape id must be 1-24 chars of [a-z0-9_-]"));
			return 0;
		}
		if (mod.store.byId(id) != null) {
			source.sendFailure(Component.literal("A shape with id '" + id + "' already exists"));
			return 0;
		}
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
						+ "], counts below y" + s.yLines + " in " + s.dimension + ". Objectives: "
						+ s.breakObjective() + " / " + s.placeObjective()).withStyle(ChatFormatting.WHITE)), false);
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
		Objective br = sb.getObjective(shape.breakObjective());
		Objective pl = sb.getObjective(shape.placeObjective());
		List<PlayerScoreEntry> entries = br == null ? List.of() : new ArrayList<>(sb.listPlayerScores(br));
		entries.removeIf(e -> e.owner().startsWith("#"));
		entries.sort((a, b) -> Integer.compare(b.value(), a.value()));

		final Shape fs = shape;
		source.sendSuccess(() -> Component.literal("— " + fs.displayName + " —")
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		if (entries.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Nobody has dug here yet.")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
		}
		long total = 0;
		for (int i = 0; i < entries.size(); i++) {
			PlayerScoreEntry e = entries.get(i);
			total += e.value();
			if (i < 10) {
				final int rank = i + 1;
				source.sendSuccess(() -> Component.literal(" #" + rank + " ").withStyle(ChatFormatting.YELLOW)
						.append(Component.literal(e.owner() + "  ").withStyle(ChatFormatting.WHITE))
						.append(Component.literal(String.format("%,d", e.value())).withStyle(ChatFormatting.GREEN)),
						false);
			}
		}
		long placed = 0;
		if (pl != null) {
			for (PlayerScoreEntry e : sb.listPlayerScores(pl)) {
				if (!e.owner().startsWith("#")) placed += e.value();
			}
		}
		final long ftotal = total, fplaced = placed;
		final int diggers = entries.size();
		source.sendSuccess(() -> Component.literal("Total: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.format("%,d", ftotal)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
				.append(Component.literal(" blocks dug by " + diggers + " players, "
						+ String.format("%,d", fplaced) + " placed").withStyle(ChatFormatting.GRAY)), false);
		return 1;
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
