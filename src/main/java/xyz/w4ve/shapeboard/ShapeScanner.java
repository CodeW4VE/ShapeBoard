package xyz.w4ve.shapeboard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Escanea el contorno de bloques marcadores a una Y fija: camina el componente
 * conectado (8 vecinos) desde una semilla, puentea huecos pequeños, y saca la
 * máscara interior con un flood fill 4-conexo desde afuera (un muro 8-conexo
 * bloquea un flood 4-conexo, así que las diagonales no filtran).
 */
public final class ShapeScanner {
	/** Radio de búsqueda de la semilla alrededor del punto dado. */
	public static final int SEED_RADIUS = 64;
	/** Tope de bloques de línea (protección de memoria/chunks). */
	public static final int MAX_WALL = 2_000_000;
	/** Tope de celdas del grid del flood fill (~8k x 8k). */
	public static final long MAX_GRID = 64_000_000L;
	/** Hueco máximo (en bloques, distancia chebyshev) que se puentea solo. */
	public static final int MAX_BRIDGE = 6;

	public record ScanResult(Map<Integer, int[]> cols, int xMin, int xMax, int zMin, int zMax,
			int wallBlocks, long area, List<int[]> bridged, List<int[]> openEnds) {
	}

	public static class ScanException extends Exception {
		public ScanException(String message) {
			super(message);
		}
	}

	public static ScanResult scan(ServerLevel level, Block marker, int y, int seedX, int seedZ) throws ScanException {
		ChunkCache cache = new ChunkCache(level, y, marker);

		long seed = findSeed(cache, seedX, seedZ);

		// 1) caminar el componente 8-conexo de la línea
		Set<Long> wall = new HashSet<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		wall.add(seed);
		queue.add(seed);
		while (!queue.isEmpty()) {
			long cur = queue.poll();
			int cx = unpackX(cur), cz = unpackZ(cur);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					long n = pack(cx + dx, cz + dz);
					if (!wall.contains(n) && cache.isMarker(cx + dx, cz + dz)) {
						wall.add(n);
						queue.add(n);
						if (wall.size() > MAX_WALL) {
							throw new ScanException("Marker line is too big (>" + MAX_WALL + " blocks)");
						}
					}
				}
			}
		}

		// 2) extremos de línea (<=1 vecino) y puenteo de huecos pequeños
		List<long[]> endpoints = new ArrayList<>();
		for (long p : wall) {
			int px = unpackX(p), pz = unpackZ(p);
			int neighbors = 0;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					if (wall.contains(pack(px + dx, pz + dz))) neighbors++;
				}
			}
			if (neighbors <= 1) endpoints.add(new long[]{p, 0});
		}
		List<int[]> bridged = new ArrayList<>();
		for (int i = 0; i < endpoints.size(); i++) {
			if (endpoints.get(i)[1] != 0) continue;
			long a = endpoints.get(i)[0];
			int ax = unpackX(a), az = unpackZ(a);
			int best = -1, bestDist = Integer.MAX_VALUE;
			for (int j = i + 1; j < endpoints.size(); j++) {
				if (endpoints.get(j)[1] != 0) continue;
				long b = endpoints.get(j)[0];
				int d = Math.max(Math.abs(ax - unpackX(b)), Math.abs(az - unpackZ(b)));
				if (d > 1 && d <= MAX_BRIDGE && d < bestDist) {
					bestDist = d;
					best = j;
				}
			}
			if (best >= 0) {
				long b = endpoints.get(best)[0];
				int bx = unpackX(b), bz = unpackZ(b);
				for (int t = 1; t < bestDist; t++) {
					int nx = Math.round(ax + (bx - ax) * (float) t / bestDist);
					int nz = Math.round(az + (bz - az) * (float) t / bestDist);
					wall.add(pack(nx, nz));
					bridged.add(new int[]{nx, nz});
				}
				endpoints.get(i)[1] = 1;
				endpoints.get(best)[1] = 1;
			}
		}
		List<int[]> openEnds = new ArrayList<>();
		for (long[] e : endpoints) {
			if (e[1] == 0) openEnds.add(new int[]{unpackX(e[0]), unpackZ(e[0])});
		}

		// 3) flood fill 4-conexo desde afuera del bounding box (+1 de margen)
		int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
		for (long p : wall) {
			int px = unpackX(p), pz = unpackZ(p);
			if (px < xMin) xMin = px;
			if (px > xMax) xMax = px;
			if (pz < zMin) zMin = pz;
			if (pz > zMax) zMax = pz;
		}
		xMin--; xMax++; zMin--; zMax++;
		int w = xMax - xMin + 1, h = zMax - zMin + 1;
		if ((long) w * h > MAX_GRID) {
			throw new ScanException("Shape bounding box is too big (" + w + "x" + h + " columns)");
		}
		BitSet wallBits = new BitSet(w * h);
		for (long p : wall) {
			wallBits.set((unpackX(p) - xMin) * h + (unpackZ(p) - zMin));
		}
		BitSet outside = new BitSet(w * h);
		ArrayDeque<Integer> flood = new ArrayDeque<>();
		outside.set(0);
		flood.add(0);
		while (!flood.isEmpty()) {
			int cur = flood.poll();
			int ci = cur / h, cj = cur % h;
			for (int d = 0; d < 4; d++) {
				int ni = ci + (d == 0 ? 1 : d == 1 ? -1 : 0);
				int nj = cj + (d == 2 ? 1 : d == 3 ? -1 : 0);
				if (ni < 0 || ni >= w || nj < 0 || nj >= h) continue;
				int idx = ni * h + nj;
				if (!outside.get(idx) && !wallBits.get(idx)) {
					outside.set(idx);
					flood.add(idx);
				}
			}
		}

		long area = (long) w * h - outside.cardinality();
		if (area <= wall.size()) {
			StringBuilder where = new StringBuilder();
			for (int i = 0; i < Math.min(4, openEnds.size()); i++) {
				where.append(i > 0 ? ", " : "").append("(").append(openEnds.get(i)[0]).append(", ").append(openEnds.get(i)[1]).append(")");
			}
			throw new ScanException("The outline is not closed: the flood fill leaked. Open line ends at: "
					+ (where.isEmpty() ? "none found (gap wider than " + MAX_BRIDGE + " blocks?)" : where));
		}

		// 4) intervalos de z por columna x (dentro = todo lo que no es "outside")
		Map<Integer, int[]> cols = new HashMap<>();
		List<Integer> row = new ArrayList<>();
		for (int i = 0; i < w; i++) {
			row.clear();
			int start = -1;
			for (int j = 0; j < h; j++) {
				boolean in = !outside.get(i * h + j);
				if (in && start < 0) start = j;
				if (!in && start >= 0) {
					row.add(zMin + start);
					row.add(zMin + j - 1);
					start = -1;
				}
			}
			if (start >= 0) {
				row.add(zMin + start);
				row.add(zMin + h - 1);
			}
			if (!row.isEmpty()) {
				int[] iv = new int[row.size()];
				for (int k = 0; k < iv.length; k++) {
					iv[k] = row.get(k);
				}
				cols.put(xMin + i, iv);
			}
		}

		return new ScanResult(cols, xMin, xMax, zMin, zMax, wall.size(), area, bridged, openEnds);
	}

	/** Busca el bloque marcador más cercano a la semilla en anillos crecientes. */
	private static long findSeed(ChunkCache cache, int seedX, int seedZ) throws ScanException {
		if (cache.isMarker(seedX, seedZ)) return pack(seedX, seedZ);
		for (int r = 1; r <= SEED_RADIUS; r++) {
			for (int dx = -r; dx <= r; dx++) {
				if (cache.isMarker(seedX + dx, seedZ - r)) return pack(seedX + dx, seedZ - r);
				if (cache.isMarker(seedX + dx, seedZ + r)) return pack(seedX + dx, seedZ + r);
			}
			for (int dz = -r + 1; dz <= r - 1; dz++) {
				if (cache.isMarker(seedX - r, seedZ + dz)) return pack(seedX - r, seedZ + dz);
				if (cache.isMarker(seedX + r, seedZ + dz)) return pack(seedX + r, seedZ + dz);
			}
		}
		throw new ScanException("No marker block found within " + SEED_RADIUS + " blocks of the start point (at that exact Y)");
	}

	private static long pack(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static int unpackX(long p) {
		return (int) (p >> 32);
	}

	private static int unpackZ(long p) {
		return (int) p;
	}

	/** Acceso a bloques cacheando el último chunk (el escaneo va por vecinos). */
	private static final class ChunkCache {
		private final ServerLevel level;
		private final int y;
		private final Block marker;
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		private ChunkAccess chunk;
		private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;

		ChunkCache(ServerLevel level, int y, Block marker) {
			this.level = level;
			this.y = y;
			this.marker = marker;
		}

		boolean isMarker(int x, int z) {
			int cx = x >> 4, cz = z >> 4;
			if (cx != chunkX || cz != chunkZ) {
				chunk = level.getChunk(cx, cz);
				chunkX = cx;
				chunkZ = cz;
			}
			return chunk.getBlockState(pos.set(x, y, z)).is(marker);
		}
	}
}
