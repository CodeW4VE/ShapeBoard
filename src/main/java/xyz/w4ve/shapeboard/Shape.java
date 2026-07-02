package xyz.w4ve.shapeboard;

import java.util.Map;

/**
 * Una zona con forma arbitraria: la máscara 2D (columna x -> intervalos de z)
 * sale de escanear las líneas de bloques marcadores a una Y fija. Se cuenta
 * todo lo que pase DENTRO de la forma y por debajo de esa Y.
 */
public final class Shape {
	public final String id;
	public String displayName;
	public final String marker;    // id del bloque marcador, ej. "minecraft:black_concrete"
	public final int yLines;       // Y de las líneas marcadoras
	public final String dimension; // ej. "minecraft:overworld"
	public final int xMin, xMax, zMin, zMax;
	/** columna x -> pares planos [z1a,z1b, z2a,z2b, ...] (inclusivos) */
	public final Map<Integer, int[]> cols;

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
}
