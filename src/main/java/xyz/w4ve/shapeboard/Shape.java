package xyz.w4ve.shapeboard;

import java.util.Map;

/**
 * An arbitrarily shaped zone: the 2D mask (x column -> z intervals) comes
 * from scanning the marker block lines at a fixed Y. Everything that happens
 * INSIDE the shape and below that Y is counted.
 */
public final class Shape {
	public final String id;
	public String displayName;
	public final String marker;    // marker block id, e.g. "minecraft:black_concrete"
	public final int yLines;       // Y level of the marker lines
	public final String dimension; // e.g. "minecraft:overworld"
	public final int xMin, xMax, zMin, zMax;
	/** x column -> flat pairs [z1a,z1b, z2a,z2b, ...] (inclusive) */
	public final Map<Integer, int[]> cols;
	/** what the sidebar/top ranks by: "break" (digs), "place" or "both" (sum) */
	public String metric = "break";
	/** show a "Total" line (sum of everyone) at the top of the sidebar */
	public boolean showTotal = true;

	/** Y range the volume scan looks at; null means "world bottom .. yLines - 1". */
	public Integer yMinScan = null;
	public Integer yMaxScan = null;
	/**
	 * Blocks that were there before digging started. 0 means "unknown": the
	 * progress bar then falls back to the raw volume of the Y range, which
	 * counts natural caves as already dug.
	 */
	public long baselineSolids = 0;
	/**
	 * Same idea per Y layer, indexed from {@link #baselineYMin}. Without it a
	 * layer is measured against the shape's full column count, which overstates
	 * surface layers that were never solid to begin with.
	 */
	public long[] baselinePerLayer = null;
	public int baselineYMin = 0;
	/** Last completed volume scan, or null if it was never run. */
	public VolumeScanner.Snapshot lastScan = null;
	/**
	 * Fake score holder that absorbs everything the counters never saw: blocks
	 * dug before the shape existed, explosions (the game credits those to
	 * nobody) and world edits. Resynced after each scan, so the board always
	 * adds up to the measured hole. Null disables it.
	 */
	public String untrackedName = null;

	public Shape(String id, String displayName, String marker, int yLines, String dimension,
			int xMin, int xMax, int zMin, int zMax, Map<Integer, int[]> cols) {
		this.id = id;
		this.displayName = displayName;
		this.marker = marker;
		this.yLines = yLines;
		this.dimension = dimension;
		this.xMin = xMin;
		this.xMax = xMax;
		this.zMin = zMin;
		this.zMax = zMax;
		this.cols = cols;
	}

	public boolean contains(int x, int z) {
		if (x < xMin || x > xMax || z < zMin || z > zMax) return false;
		int[] iv = cols.get(x);
		if (iv == null) return false;
		for (int i = 0; i < iv.length; i += 2) {
			if (z >= iv[i] && z <= iv[i + 1]) return true;
		}
		return false;
	}

	public long area() {
		long total = 0;
		for (int[] iv : cols.values()) {
			for (int i = 0; i < iv.length; i += 2) {
				total += iv[i + 1] - iv[i] + 1;
			}
		}
		return total;
	}

	/** Bedrock layers at the bottom of a dimension: nobody is clearing those. */
	public static final int BEDROCK_LAYERS = 5;

	/**
	 * Bottom of the scanned slice. By default it starts above the bedrock
	 * floor, since those layers can never be emptied.
	 */
	public int scanYMin(int worldBottom) {
		return yMinScan != null ? yMinScan : worldBottom + BEDROCK_LAYERS;
	}

	/** Top of the scanned slice (default: just below the marker lines). */
	public int scanYMax() {
		return yMaxScan != null ? yMaxScan : yLines - 1;
	}

	/** Raw block count of the Y slice: columns * height. */
	public long volume(int yMin, int yMax) {
		if (yMax < yMin) return 0;
		return area() * (yMax - yMin + 1);
	}

	/** Denominator for the progress bar: the measured baseline, or the raw volume. */
	public long baselineFor(int yMin, int yMax) {
		return baselineSolids > 0 ? baselineSolids : volume(yMin, yMax);
	}

	/** How many blocks one Y layer started with. Falls back to the column count. */
	public long baselineLayer(int y) {
		if (baselinePerLayer != null) {
			int i = y - baselineYMin;
			if (i >= 0 && i < baselinePerLayer.length) return baselinePerLayer[i];
		}
		return area();
	}

	public boolean hasLayerBaseline() {
		return baselinePerLayer != null;
	}

	public String breakObjective() {
		return id + "_break";
	}

	public String placeObjective() {
		return id + "_place";
	}

	public boolean countsBreaks() {
		return !metric.equals("place");
	}

	public boolean countsPlaces() {
		return !metric.equals("break");
	}
}
