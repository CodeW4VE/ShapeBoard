package xyz.w4ve.shapeboard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Counts the mineable blocks still standing inside a shape, so we can tell how
 * much of a perimeter is left to dig.
 *
 * Reads chunk NBT straight off the chunk storage on a worker thread and walks
 * the section palettes, so a 5,000 chunk zone costs seconds and never touches
 * the server thread. Callers must flush the world to disk first (see
 * {@link #scanAsync}) or freshly dug chunks would still read as full.
 */
public final class VolumeScanner {
	/** Only one scan at a time: they are cheap but hammer the IO worker. */
	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
	private static final long[] EMPTY_LONGS = new long[0];

	/** Snapshot of one completed scan, persisted with the shape. */
	public record Snapshot(long remaining, long volume, long baseline, int chunks, int missingChunks,
			long millis, long epochSeconds, List<Map.Entry<String, Long>> topBlocks,
			int yMin, int yMax, long columns, long[] perLayer) {

		/** Blocks left on one Y layer, out of {@link #columns} it started with. */
		public long layer(int y) {
			if (perLayer == null || y < yMin || y > yMax) return 0;
			return perLayer[y - yMin];
		}

		/**
		 * Highest layer that still has real work in it. World Eaters go top
		 * down, so this is "where the crew is at" (-1 when nothing is left).
		 */
		public int workingLayer() {
			if (perLayer == null) return -1;
			for (int y = yMax; y >= yMin; y--) {
				if (perLayer[y - yMin] > 0) return y;
			}
			return -1;
		}

		/** Layers with nothing left to dig at all. */
		public int clearedLayers() {
			if (perLayer == null) return 0;
			int n = 0;
			for (long v : perLayer) {
				if (v == 0) n++;
			}
			return n;
		}

		public int layerCount() {
			return yMax - yMin + 1;
		}

		/** Blocks already cleared, against whichever denominator is in use. */
		public long cleared() {
			return Math.max(0, baseline - remaining);
		}

		public double fraction() {
			if (baseline <= 0) return 0;
			return Math.min(1.0, Math.max(0.0, (double) cleared() / baseline));
		}
	}

	public static boolean isRunning() {
		return RUNNING.get();
	}

	/**
	 * Saves the world, then scans off-thread. {@code onDone} runs back on the
	 * server thread with either a snapshot or an error message.
	 */
	public static boolean scanAsync(MinecraftServer server, ServerLevel level, Shape shape,
			Consumer<Snapshot> onDone, Consumer<String> onError) {
		if (!RUNNING.compareAndSet(false, true)) return false;

		// The palettes we are about to read come from disk, so anything still
		// held in memory has to be flushed or we would count blocks that are
		// already gone.
		level.save(null, true, false);

		// Block properties are resolved here, on the server thread, so the
		// worker only ever touches plain strings and numbers.
		Set<String> mineable = mineableBlockIds();
		int yMin = shape.scanYMin(level.dimensionType().minY());
		int yMax = shape.scanYMax();
		long baseline = shape.baselineFor(yMin, yMax);

		CompletableFuture.supplyAsync(() -> run(level, shape, mineable, yMin, yMax, baseline))
				.whenComplete((snap, err) -> {
					RUNNING.set(false);
					server.execute(() -> {
						if (err != null) {
							ShapeBoard.LOGGER.error("Volume scan of '{}' failed", shape.id, err);
							onError.accept(err.getMessage() == null ? err.toString() : err.getMessage());
						} else {
							onDone.accept(snap);
						}
					});
				});
		return true;
	}

	/** Blocks that count as "still to be dug": solid, collidable, breakable. */
	private static Set<String> mineableBlockIds() {
		Set<String> ids = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			BlockState state = block.defaultBlockState();
			if (state.isAir()) continue;
			if (!state.getFluidState().isEmpty()) continue;          // water, lava
			if (state.is(BlockTags.LEAVES)) continue;                 // asked for: leaves don't count
			if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
				continue;                                             // grass, flowers, torches, rails...
			}
			if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) {
				continue;                                             // bedrock, barriers: unbreakable
			}
			ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
		}
		return ids;
	}

	private static Snapshot run(ServerLevel level, Shape shape, Set<String> mineable,
			int yMin, int yMax, long baseline) {
		long t0 = System.currentTimeMillis();
		long remaining = 0;
		int chunks = 0;
		int missing = 0;
		Map<String, Long> byBlock = new HashMap<>();
		long[] perLayer = new long[yMax - yMin + 1];

		int cxMin = shape.xMin >> 4, cxMax = shape.xMax >> 4;
		int czMin = shape.zMin >> 4, czMax = shape.zMax >> 4;
		int secMin = yMin >> 4, secMax = yMax >> 4;
		boolean[] inside = new boolean[256];

		for (int cx = cxMin; cx <= cxMax; cx++) {
			for (int cz = czMin; cz <= czMax; cz++) {
				int columns = columnMask(shape, cx, cz, inside);
				if (columns == 0) continue;
				chunks++;

				CompoundTag root = readChunk(level, new ChunkPos(cx, cz));
				if (root == null) {
					missing++;
					continue;
				}
				ListTag sections = root.getListOrEmpty("sections");
				for (int i = 0; i < sections.size(); i++) {
					CompoundTag section = sections.getCompoundOrEmpty(i);
					int sectionY = section.getByteOr("Y", (byte) 0);
					if (sectionY < secMin || sectionY > secMax) continue;
					remaining += countSection(section, sectionY, inside, columns, yMin, yMax,
							mineable, byBlock, perLayer);
				}
			}
		}

		List<Map.Entry<String, Long>> top = new ArrayList<>(byBlock.entrySet());
		top.sort(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed());
		if (top.size() > 5) top = new ArrayList<>(top.subList(0, 5));

		return new Snapshot(remaining, shape.volume(yMin, yMax), baseline, chunks, missing,
				System.currentTimeMillis() - t0, System.currentTimeMillis() / 1000L, top,
				yMin, yMax, shape.area(), perLayer);
	}

	/** Marks which of the chunk's 256 columns fall inside the shape. */
	private static int columnMask(Shape shape, int cx, int cz, boolean[] inside) {
		int count = 0;
		int baseX = cx << 4, baseZ = cz << 4;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				boolean in = shape.contains(baseX + x, baseZ + z);
				inside[z * 16 + x] = in;
				if (in) count++;
			}
		}
		return count;
	}

	/**
	 * Walks one 16^3 section's palette container. The bit-packed format is
	 * 1.16+: entries never straddle a long.
	 */
	private static long countSection(CompoundTag section, int sectionY, boolean[] inside, int columns,
			int yMin, int yMax, Set<String> mineable, Map<String, Long> byBlock, long[] perLayer) {
		if (!section.contains("block_states")) return 0;
		CompoundTag states = section.getCompoundOrEmpty("block_states");
		ListTag palette = states.getListOrEmpty("palette");
		if (palette.isEmpty()) return 0;

		String[] names = new String[palette.size()];
		boolean[] counts = new boolean[palette.size()];
		boolean any = false;
		for (int i = 0; i < palette.size(); i++) {
			names[i] = palette.getCompoundOrEmpty(i).getStringOr("Name", "");
			counts[i] = mineable.contains(names[i]);
			any |= counts[i];
		}
		if (!any) return 0;

		int baseY = sectionY << 4;
		int loY = Math.max(0, yMin - baseY);
		int hiY = Math.min(15, yMax - baseY);
		if (loY > hiY) return 0;

		// Uniform section: no data array, every block is palette entry 0.
		if (palette.size() == 1) {
			if (!counts[0]) return 0;
			long n = (long) columns * (hiY - loY + 1);
			byBlock.merge(names[0], n, Long::sum);
			for (int y = loY; y <= hiY; y++) {
				perLayer[baseY + y - yMin] += columns;
			}
			return n;
		}

		long[] data = states.getLongArray("data").orElse(EMPTY_LONGS);
		if (data.length == 0) return 0;
		int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
		int perLong = 64 / bits;
		long mask = (1L << bits) - 1;

		long found = 0;
		for (int y = loY; y <= hiY; y++) {
			int yBase = y << 8;
			for (int idx = 0; idx < 256; idx++) {
				if (!inside[idx]) continue;
				int i = yBase + idx;
				int longIdx = i / perLong;
				if (longIdx >= data.length) continue;
				int entry = (int) ((data[longIdx] >>> ((i % perLong) * bits)) & mask);
				if (entry >= counts.length || !counts[entry]) continue;
				found++;
				perLayer[baseY + y - yMin]++;
				byBlock.merge(names[entry], 1L, Long::sum);
			}
		}
		return found;
	}

	/** Chunk NBT straight from storage (pending writes included), or null. */
	private static CompoundTag readChunk(ServerLevel level, ChunkPos pos) {
		try {
			Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(pos).join();
			return tag.orElse(null);
		} catch (Exception e) {
			ShapeBoard.LOGGER.warn("Could not read chunk {}: {}", pos, e.toString());
			return null;
		}
	}

	private VolumeScanner() {
	}

	/** Short block name for display: "minecraft:deepslate" -> "deepslate". */
	public static String shortName(String id) {
		int i = id.indexOf(':');
		return i < 0 ? id : id.substring(i + 1);
	}

	/** Text progress bar, e.g. "██████░░░░░░░░░░░░░░". */
	public static String bar(double fraction, int width) {
		int filled = (int) Math.round(fraction * width);
		StringBuilder sb = new StringBuilder(width);
		for (int i = 0; i < width; i++) {
			sb.append(i < filled ? '█' : '░');
		}
		return sb.toString();
	}
}
