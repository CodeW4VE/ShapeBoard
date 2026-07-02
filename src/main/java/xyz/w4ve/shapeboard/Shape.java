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
