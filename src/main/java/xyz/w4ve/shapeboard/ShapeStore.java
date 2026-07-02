package xyz.w4ve.shapeboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shapes + preferencias, persistidos en world/shapeboard/ como JSON. */
public final class ShapeStore {
	private static final Gson GSON = new GsonBuilder().create();

	private final List<Shape> shapes = new CopyOnWriteArrayList<>();
	private final Set<UUID> hidden = new HashSet<>();

	public List<Shape> all() {
		return shapes;
	}

	public Shape byId(String id) {
		for (Shape s : shapes) {
			if (s.id.equals(id)) return s;
		}
		return null;
	}

	/** Primera shape que contiene la columna (x,z) en esa dimensión. */
	public Shape shapeAt(String dimension, int x, int z) {
		for (Shape s : shapes) {
			if (s.dimension.equals(dimension) && s.contains(x, z)) return s;
		}
		return null;
	}

	public void add(Shape s) {
		shapes.add(s);
	}

	public boolean remove(String id) {
		return shapes.removeIf(s -> s.id.equals(id));
	}

	public boolean isHidden(UUID uuid) {
		return hidden.contains(uuid);
	}

	public void setHidden(UUID uuid, boolean value) {
		if (value) hidden.add(uuid);
		else hidden.remove(uuid);
	}

	private static Path dir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("shapeboard");
	}

	public void load(MinecraftServer server) {
		shapes.clear();
		hidden.clear();
		Path dir = dir(server);
		try {
			Path shapesFile = dir.resolve("shapes.json");
			if (Files.exists(shapesFile)) {
				JsonObject root = JsonParser.parseString(Files.readString(shapesFile)).getAsJsonObject();
				for (JsonElement el : root.getAsJsonArray("shapes")) {
					shapes.add(fromJson(el.getAsJsonObject()));
				}
			}
			Path hiddenFile = dir.resolve("hidden.json");
			if (Files.exists(hiddenFile)) {
				for (JsonElement el : JsonParser.parseString(Files.readString(hiddenFile)).getAsJsonArray()) {
					hidden.add(UUID.fromString(el.getAsString()));
				}
			}
			ShapeBoard.LOGGER.info("Loaded {} shape(s)", shapes.size());
		} catch (Exception e) {
			ShapeBoard.LOGGER.error("Failed to load shapeboard data", e);
		}
	}

	public void save(MinecraftServer server) {
		Path dir = dir(server);
		try {
			Files.createDirectories(dir);
			JsonObject root = new JsonObject();
			JsonArray arr = new JsonArray();
			for (Shape s : shapes) {
				arr.add(toJson(s));
			}
			root.add("shapes", arr);
			Files.writeString(dir.resolve("shapes.json"), GSON.toJson(root));

			JsonArray hid = new JsonArray();
			for (UUID u : hidden) {
				hid.add(u.toString());
			}
			Files.writeString(dir.resolve("hidden.json"), GSON.toJson(hid));
		} catch (IOException e) {
			ShapeBoard.LOGGER.error("Failed to save shapeboard data", e);
		}
	}

	private static JsonObject toJson(Shape s) {
		JsonObject o = new JsonObject();
		o.addProperty("id", s.id);
		o.addProperty("name", s.displayName);
		o.addProperty("marker", s.marker);
		o.addProperty("y", s.yLines);
		o.addProperty("dim", s.dimension);
		o.addProperty("xMin", s.xMin);
		o.addProperty("xMax", s.xMax);
		o.addProperty("zMin", s.zMin);
		o.addProperty("zMax", s.zMax);
		JsonObject cols = new JsonObject();
		for (Map.Entry<Integer, int[]> e : s.cols.entrySet()) {
			JsonArray iv = new JsonArray();
			for (int v : e.getValue()) {
				iv.add(v);
			}
			cols.add(String.valueOf(e.getKey()), iv);
		}
		o.add("cols", cols);
		return o;
	}

	private static Shape fromJson(JsonObject o) {
		Map<Integer, int[]> cols = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cols").entrySet()) {
			JsonArray arr = e.getValue().getAsJsonArray();
			int[] iv = new int[arr.size()];
			for (int i = 0; i < iv.length; i++) {
				iv[i] = arr.get(i).getAsInt();
			}
			cols.put(Integer.parseInt(e.getKey()), iv);
		}
		return new Shape(
				o.get("id").getAsString(),
				o.get("name").getAsString(),
				o.get("marker").getAsString(),
				o.get("y").getAsInt(),
				o.get("dim").getAsString(),
				o.get("xMin").getAsInt(),
				o.get("xMax").getAsInt(),
				o.get("zMin").getAsInt(),
				o.get("zMax").getAsInt(),
				cols);
	}
}
