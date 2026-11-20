package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live world map on the wall of the city hall: nine maps (3 x 3) that together show the whole
 * island world, drawn by the plugin from the island shapes (no need to explore first), with a
 * marker for every online player (with their name) and for the village, harbour, settlements and
 * the fortress. The map ids are kept in worldmap.yml so the same maps come back after a restart.
 */
public final class WorldMap implements Listener {

    public static final int TILE = 2048;           // blocks per map (largest map scale)
    public static final int ORIGIN = -3 * TILE / 2; // west / north edge of the map wall

    private final SettlerIslands plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<String, int[]> pixels = new java.util.concurrent.ConcurrentHashMap<>();   // world name -> 384 x 384 colours

    public WorldMap(SettlerIslands plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "worldmap.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    /** Re-attaches our renderer to saved maps after a restart. */
    public void restore() {
        for (String worldName : data.getKeys(false)) {
            for (int id : data.getIntegerList(worldName)) {
                MapView view = Bukkit.getMap(id);
                if (view != null) attach(view);
            }
        }
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        MapView view = event.getMap();
        for (String worldName : data.getKeys(false)) {
            if (data.getIntegerList(worldName).contains(view.getId())) attach(view);
        }
    }

    /** The map item for one tile of the wall (creates the map the first time). */
    public ItemStack item(World world, int col, int row) {
        List<Integer> ids = new ArrayList<>(data.getIntegerList(world.getName()));
        while (ids.size() < 9) ids.add(-1);
        int index = row * 3 + col;
        MapView view = ids.get(index) >= 0 ? Bukkit.getMap(ids.get(index)) : null;
        if (view == null) {
            view = Bukkit.createMap(world);
            ids.set(index, view.getId());
            data.set(world.getName(), ids);
            try {
                data.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save worldmap.yml: " + e.getMessage());
            }
        }
        attach(view);
        ItemStack stack = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) stack.getItemMeta();
        meta.setMapView(view);
        stack.setItemMeta(meta);
        return stack;
    }

    private void attach(MapView view) {
        World world = view.getWorld();
        if (world == null) return;
        int index = data.getIntegerList(world.getName()).indexOf(view.getId());
        if (index < 0) return;
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            if (r instanceof Tile) return;
            view.removeRenderer(r);
        }
        int col = index % 3, row = index / 3;
        view.setScale(MapView.Scale.FARTHEST);
        view.setCenterX(ORIGIN + col * TILE + TILE / 2);
        view.setCenterZ(ORIGIN + row * TILE + TILE / 2);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(new Tile(world, col, row));
    }

    // ---------------------------------------------------------------- drawing

    /** Colours of the whole world, 384 x 384 pixels (16 blocks each), computed once per world. */
    private int[] picture(World world) {
        return pixels.computeIfAbsent(world.getName(), n -> draw(WorldModel.get(world.getSeed()), plugin.borderSize() / 2));
    }

    /** Draws the picture in the background so the first look at the map does not lag the server. */
    public void prepare(World world) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> picture(world));
    }

    private static int[] draw(WorldModel m, int half) {
        int n = 384, step = TILE * 3 / n;
        int[] heights = new int[n * n];
        boolean[] land = new boolean[n * n];
        int[] out = new int[n * n];
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                int x = ORIGIN + px * step + step / 2, z = ORIGIN + py * step + step / 2;
                WorldModel.Sample sm = m.sample(x, z);
                int i = py * n + px;
                land[i] = sm.land();
                if (!sm.land()) {
                    double depth = Math.min(1, -sm.s() / 160.0);
                    Color c = blend(new Color(78, 140, 214), new Color(34, 70, 150), depth);
                    if (Math.abs(x) > half || Math.abs(z) > half) c = blend(c, new Color(20, 24, 40), 0.7);
                    out[i] = c.getRGB();
                    continue;
                }
                int h = m.height(x, z, sm);
                heights[i] = h;
                Material top = m.surface(x, z, sm, h).top();
                if (sm.zone() == Zone.NETHER && h < WorldModel.LAVA_LEVEL) top = Material.LAVA;
                out[i] = colour(top, h).getRGB();
            }
        }
        // hill shading: light from the north-west
        for (int py = 1; py < n; py++) {
            for (int px = 1; px < n; px++) {
                int i = py * n + px;
                if (!land[i]) continue;
                int d = heights[i] - heights[(py - 1) * n + px - 1];
                if (!land[(py - 1) * n + px - 1]) d = 0;
                double f = Math.max(-0.35, Math.min(0.35, d * 0.06));
                Color c = new Color(out[i]);
                out[i] = (f > 0 ? blend(c, Color.WHITE, f) : blend(c, Color.BLACK, -f)).getRGB();
            }
        }
        return out;
    }

    private static Color colour(Material top, int h) {
        Color c = switch (top) {
            case GRASS_BLOCK -> h > 110 ? new Color(96, 140, 70) : new Color(108, 162, 70);
            case PODZOL -> new Color(80, 98, 50);
            case COARSE_DIRT, DIRT, MUD, ROOTED_DIRT -> new Color(122, 92, 62);
            case SAND -> new Color(219, 207, 158);
            case RED_SAND -> new Color(190, 102, 40);
            case TERRACOTTA -> new Color(160, 90, 60);
            case GRAVEL -> new Color(140, 132, 128);
            case STONE, ANDESITE, TUFF, COBBLESTONE, MOSSY_COBBLESTONE -> new Color(128, 128, 128);
            case SNOW_BLOCK, SNOW, POWDER_SNOW -> new Color(240, 244, 248);
            case BLACKSTONE, BASALT, POLISHED_BLACKSTONE -> new Color(44, 40, 46);
            case NETHERRACK -> new Color(110, 40, 40);
            case CRIMSON_NYLIUM -> new Color(140, 28, 30);
            case WARPED_NYLIUM -> new Color(40, 110, 100);
            case SOUL_SAND, SOUL_SOIL -> new Color(84, 64, 52);
            case MAGMA_BLOCK, LAVA -> new Color(230, 110, 20);
            case MOSS_BLOCK -> new Color(90, 120, 50);
            case PALE_MOSS_BLOCK -> new Color(170, 176, 160);
            default -> new Color(120, 150, 90);
        };
        return c;
    }

    private static Color blend(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Draws one tile of the wall and its markers. */
    private final class Tile extends MapRenderer {
        private final World world;
        private final int col, row;
        private boolean painted;
        private long lastCursors;

        Tile(World world, int col, int row) {
            super(false);
            this.world = world;
            this.col = col;
            this.row = row;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (!painted) {
                int[] pic = picture(world);
                for (int y = 0; y < 128; y++)
                    for (int x = 0; x < 128; x++)
                        canvas.setPixelColor(x, y, new Color(pic[(row * 128 + y) * 384 + col * 128 + x]));
                painted = true;
            }
            long now = System.currentTimeMillis();
            if (now - lastCursors < 500) return;
            lastCursors = now;
            MapCursorCollection cursors = new MapCursorCollection();
            long seed = world.getSeed();
            StructurePlan plan = StructurePlan.get(WorldModel.get(seed), seed);
            marker(cursors, plan.village.cx, plan.village.cz, MapCursor.Type.VILLAGE_PLAINS, "Village");
            int[] h = plan.harbour.spot();
            marker(cursors, h[0], h[1], MapCursor.Type.BANNER_BLUE, "Harbour");
            marker(cursors, plan.fortress.fx, plan.fortress.fz, MapCursor.Type.RED_X, "Fortress");
            for (Settlement st : plan.settlements) {
                int[] s = st.spot();
                String name = Character.toUpperCase(st.zone.charAt(0)) + st.zone.substring(1);
                marker(cursors, s[0], s[2], MapCursor.Type.BANNER_YELLOW, name);
            }
            if (plugin.ships() != null) {
                for (var e : plugin.ships().markers(world)) {
                    marker(cursors, (int) e.getKey()[0], (int) e.getKey()[1], MapCursor.Type.BANNER_WHITE, e.getValue());
                }
            }
            for (Player p : world.getPlayers()) {
                float yaw = p.getLocation().getYaw();
                byte dir = (byte) (((int) Math.floor(yaw * 16 / 360.0 + 0.5)) & 15);
                cursor(cursors, p.getLocation().getBlockX(), p.getLocation().getBlockZ(), dir, MapCursor.Type.PLAYER, p.getName());
            }
            canvas.setCursors(cursors);
        }

        private void marker(MapCursorCollection c, int x, int z, MapCursor.Type type, String caption) {
            cursor(c, x, z, (byte) 8, type, caption);
        }

        private void cursor(MapCursorCollection c, int x, int z, byte dir, MapCursor.Type type, String caption) {
            int minX = ORIGIN + col * TILE, minZ = ORIGIN + row * TILE;
            double px = (x - minX) / (double) TILE * 256 - 128, pz = (z - minZ) / (double) TILE * 256 - 128;
            if (px < -128 || px > 127 || pz < -128 || pz > 127) return;
            c.addCursor(new MapCursor((byte) px, (byte) pz, dir, type, true, caption));
        }
    }
}
