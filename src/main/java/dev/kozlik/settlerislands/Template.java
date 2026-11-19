package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A building stored as blocks (structures/*.txt, made with the design scripts).
 * Palette entry 0 = leave the world as it is, 1 = air. Entries may contain variables such as
 * {roof} or {wall} that are filled in per building, so one design gives many looks.
 * The design's front faces +z (south); it can be turned in steps of 90 degrees.
 */
public final class Template {

    private static final Map<String, Template> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BlockData> DATA = new ConcurrentHashMap<>();
    private static final String[] DIRS = {"north", "east", "south", "west"};
    private static final Pattern DIR_VALUE = Pattern.compile("(facing)=(north|east|south|west)");
    private static final Pattern DIR_KEY = Pattern.compile("(?<=[\\[,])(north|east|south|west)=");
    private static final Pattern AXIS = Pattern.compile("axis=(x|z)");

    public final int w, h, d;
    private final String[] palette;
    private final short[] data;

    private Template(int w, int h, int d, String[] palette, short[] data) {
        this.w = w;
        this.h = h;
        this.d = d;
        this.palette = palette;
        this.data = data;
    }

    public static Template get(String name) {
        return CACHE.computeIfAbsent(name, Template::load);
    }

    private static Template load(String name) {
        try (InputStream in = Template.class.getResourceAsStream("/structures/" + name + ".txt")) {
            if (in == null) throw new IllegalStateException("Missing template " + name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String[] size = r.readLine().trim().split(" ");
            int w = Integer.parseInt(size[0]), h = Integer.parseInt(size[1]), d = Integer.parseInt(size[2]);
            int n = Integer.parseInt(r.readLine().trim());
            String[] pal = new String[n];
            for (int i = 0; i < n; i++) pal[i] = r.readLine().trim();
            short[] data = new short[w * h * d];
            int pos = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                for (String tok : line.trim().split(",")) {
                    int star = tok.indexOf('*');
                    int count = star < 0 ? 1 : Integer.parseInt(tok.substring(0, star));
                    short v = Short.parseShort(star < 0 ? tok : tok.substring(star + 1));
                    for (int k = 0; k < count; k++) data[pos++] = v;
                }
            }
            return new Template(w, h, d, pal, data);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read template " + name, e);
        }
    }

    public int index(int x, int y, int z) {
        return data[(y * d + z) * w + x];
    }

    /** Width and depth of the placed box for a rotation. */
    public int sizeX(int rot) {
        return (rot & 1) == 0 ? w : d;
    }

    public int sizeZ(int rot) {
        return (rot & 1) == 0 ? d : w;
    }

    /** World offset of template cell (x, z) for a rotation: returns {dx, dz}. */
    public int[] offset(int x, int z, int rot) {
        return switch (rot & 3) {
            case 0 -> new int[]{x, z};
            case 1 -> new int[]{d - 1 - z, x};
            case 2 -> new int[]{w - 1 - x, d - 1 - z};
            default -> new int[]{z, w - 1 - x};
        };
    }

    /** Template cell for a world offset (u, v): returns {x, z}. */
    private int[] cell(int u, int v, int rot) {
        return switch (rot & 3) {
            case 0 -> new int[]{u, v};
            case 1 -> new int[]{v, d - 1 - u};
            case 2 -> new int[]{w - 1 - u, d - 1 - v};
            default -> new int[]{w - 1 - v, u};
        };
    }

    /** Rotation that makes the design's front (+z) face the direction (dx, dz), rounded to an axis. */
    public static int rotationFacing(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 3 : 1;
        return dz > 0 ? 0 : 2;
    }

    /** All cells whose block starts with the given name, e.g. "minecraft:spawner": {x, y, z} in template space. */
    public List<int[]> find(String prefix) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < palette.length; i++) if (palette[i].startsWith(prefix)) ids.add(i);
        List<int[]> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++)
                    if (ids.contains(index(x, y, z))) out.add(new int[]{x, y, z});
        return out;
    }

    /**
     * Places the part of this design that falls in the builder's chunk.
     * (ox, oy, oz) is the world position of the placed box's lowest corner.
     * extendDown: blocks in the bottom layer continue down to the ground, so nothing floats.
     */
    public void place(Builder b, int ox, int oy, int oz, int rot, Map<String, String> vars, boolean extendDown) {
        int sx = sizeX(rot), sz = sizeZ(rot);
        if (!b.touches(ox, oz, ox + sx - 1, oz + sz - 1)) return;
        int ax = Math.max(ox, b.minX), bx = Math.min(ox + sx - 1, b.minX + 15);
        int az = Math.max(oz, b.minZ), bz = Math.min(oz + sz - 1, b.minZ + 15);
        BlockData[] resolved = new BlockData[palette.length];
        for (int wx = ax; wx <= bx; wx++) {
            for (int wz = az; wz <= bz; wz++) {
                int[] c = cell(wx - ox, wz - oz, rot);
                for (int y = 0; y < h; y++) {
                    int idx = index(c[0], y, c[1]);
                    if (idx == 0) continue;
                    BlockData bd = resolved[idx];
                    if (bd == null) bd = resolved[idx] = resolve(palette[idx], rot, vars);
                    b.set(wx, oy + y, wz, bd);
                    if (y == 0 && extendDown && idx != 1) {
                        int g = b.ground(wx, wz);
                        for (int yy = g + 1; yy < oy; yy++) b.set(wx, yy, wz, bd);
                    }
                }
            }
        }
    }

    private static BlockData resolve(String raw, int rot, Map<String, String> vars) {
        String s = raw;
        if (vars != null && s.indexOf('{') >= 0) {
            for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        if (rot != 0) s = rotate(s, rot);
        return safeData(s);
    }

    /** Never lets an unknown block name crash the server: it logs once and places air instead. */
    static BlockData safeData(String s) {
        BlockData bd = DATA.get(s);
        if (bd != null) return bd;
        try {
            bd = Bukkit.createBlockData(s);
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[SettlerIslands] Unknown block '" + s + "', placing air instead");
            bd = Bukkit.createBlockData(org.bukkit.Material.AIR);
        }
        DATA.put(s, bd);
        return bd;
    }

    private static String turn(String dir, int rot) {
        for (int i = 0; i < 4; i++) if (DIRS[i].equals(dir)) return DIRS[(i + rot) & 3];
        return dir;
    }

    /** Turns facing values, side connections (north=true ...) and axes clockwise by rot * 90 degrees. */
    static String rotate(String s, int rot) {
        if (s.indexOf('[') < 0) return s;
        Matcher m = DIR_VALUE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, m.group(1) + "=" + turn(m.group(2), rot));
        m.appendTail(sb);
        s = sb.toString();
        m = DIR_KEY.matcher(s);
        sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, turn(m.group(1), rot) + "=");
        m.appendTail(sb);
        s = sb.toString();
        if ((rot & 1) == 1) {
            m = AXIS.matcher(s);
            sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, "axis=" + (m.group(1).equals("x") ? "z" : "x"));
            m.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }
}
