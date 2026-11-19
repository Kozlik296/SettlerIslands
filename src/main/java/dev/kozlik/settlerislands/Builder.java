package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Wall;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes structure blocks into one chunk. Structures describe themselves in world coordinates;
 * anything outside the chunk being populated is skipped, so each chunk gets exactly its own part.
 */
public final class Builder {

    private static final Map<String, BlockData> DATA = new ConcurrentHashMap<>();

    public final LimitedRegion region;
    public final WorldModel model;
    public final int minX, minZ, minY, maxY;

    public Builder(LimitedRegion region, WorldModel model, int chunkX, int chunkZ, int minY, int maxY) {
        this.region = region;
        this.model = model;
        this.minX = chunkX << 4;
        this.minZ = chunkZ << 4;
        this.minY = minY;
        this.maxY = maxY;
    }

    public boolean in(int x, int z) {
        return x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16;
    }

    /** True if the box (inclusive) touches this chunk. */
    public boolean touches(int x1, int z1, int x2, int z2) {
        return Math.max(x1, x2) >= minX && Math.min(x1, x2) < minX + 16 && Math.max(z1, z2) >= minZ && Math.min(z1, z2) < minZ + 16;
    }

    private final List<int[]> connectables = new ArrayList<>();

    public void set(int x, int y, int z, Material mat) {
        if (in(x, z) && y > minY && y < maxY) {
            region.setType(x, y, z, mat);
            if (connectable(mat)) connectables.add(new int[]{x, y, z});
        }
    }

    public void set(int x, int y, int z, String data) {
        if (in(x, z) && y > minY && y < maxY) {
            set(x, y, z, Template.safeData(data));
        }
    }

    public void set(int x, int y, int z, BlockData data) {
        if (in(x, z) && y > minY && y < maxY) {
            region.setBlockData(x, y, z, data);
            if (data instanceof Fence || data instanceof Wall) connectables.add(new int[]{x, y, z});
        }
    }

    // --------------------------------------------------------- fence connections

    private static boolean connectable(Material m) {
        String n = m.name();
        return (n.endsWith("_FENCE") || n.endsWith("_PANE") || n.equals("IRON_BARS")
                || (n.endsWith("_WALL") && !n.contains("SIGN") && !n.contains("BANNER") && !n.contains("TORCH")
                && !n.contains("HEAD") && !n.contains("SKULL") && !n.contains("FAN")));
    }

    /** 0 wooden fence, 1 nether brick fence, 2 pane / bars, 3 wall, -1 none. */
    private static int family(Material m) {
        String n = m.name();
        if (n.equals("NETHER_BRICK_FENCE")) return 1;
        if (n.endsWith("_FENCE")) return 0;
        if (n.endsWith("_PANE") || n.equals("IRON_BARS")) return 2;
        if (connectable(m) && n.endsWith("_WALL")) return 3;
        return -1;
    }

    private boolean joins(int fam, int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) return false;
        BlockData nb = region.getBlockData(x, y, z);
        Material m = nb.getMaterial();
        if (m.isOccluding()) return true;
        int f = family(m);
        if (nb instanceof Gate) return fam == 0 || fam == 3;
        return switch (fam) {
            case 0 -> f == 0;
            case 1 -> f == 1;
            case 2, 3 -> f == 2 || f == 3;
            default -> false;
        };
    }

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private void connect(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) return;
        BlockData bd = region.getBlockData(x, y, z);
        int fam = family(bd.getMaterial());
        if (fam < 0) return;
        if (bd instanceof Fence fence) {
            for (BlockFace f : SIDES) {
                if (fence.getAllowedFaces().contains(f)) fence.setFace(f, joins(fam, x + f.getModX(), y, z + f.getModZ()));
            }
            region.setBlockData(x, y, z, fence);
        } else if (bd instanceof Wall wall) {
            boolean[] j = new boolean[4];
            for (int i = 0; i < 4; i++) {
                BlockFace f = SIDES[i];
                j[i] = joins(fam, x + f.getModX(), y, z + f.getModZ());
                wall.setHeight(f, j[i] ? Wall.Height.LOW : Wall.Height.NONE);
            }
            boolean straight = (j[0] && j[2] && !j[1] && !j[3]) || (j[1] && j[3] && !j[0] && !j[2]);
            wall.setUp(!straight || !region.getType(x, y + 1, z).isAir());
            region.setBlockData(x, y, z, wall);
        }
    }

    /** Joins every fence, pane and wall placed in this chunk to its neighbours (and updates the neighbours). */
    public void connectAll() {
        for (int[] p : connectables) {
            connect(p[0], p[1], p[2]);
            for (BlockFace f : SIDES) connect(p[0] + f.getModX(), p[1], p[2] + f.getModZ());
        }
        connectables.clear();
    }

    // ------------------------------------------------------------- tree clearing

    private static boolean trunk(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")
                || m == Material.MUSHROOM_STEM || m == Material.MANGROVE_ROOTS || m == Material.MUDDY_MANGROVE_ROOTS;
    }

    private static boolean crown(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || m == Material.NETHER_WART_BLOCK || m == Material.WARPED_WART_BLOCK
                || m == Material.SHROOMLIGHT || n.startsWith("WEEPING_VINES") || n.startsWith("TWISTING_VINES")
                || m == Material.VINE || m == Material.RED_MUSHROOM_BLOCK || m == Material.BROWN_MUSHROOM_BLOCK
                || m == Material.COCOA || m == Material.BEE_NEST || n.contains("HANGING_MOSS");
    }

    /**
     * Removes trees and giant fungi above the ground in a box (only in this chunk) before something is
     * built there, including the crowns around removed trunks, so nothing is left floating.
     */
    public void clearTrees(int x1, int z1, int x2, int z2) {
        int ax = Math.max(Math.min(x1, x2), minX), bx = Math.min(Math.max(x1, x2), minX + 15);
        int az = Math.max(Math.min(z1, z2), minZ), bz = Math.min(Math.max(z1, z2), minZ + 15);
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                int g = ground(x, z);
                for (int y = g + 1; y <= Math.min(maxY - 1, g + 45); y++) {
                    Material m = region.getType(x, y, z);
                    if (trunk(m)) {
                        region.setType(x, y, z, Material.AIR);
                        if (y > g + 2) clearCrown(x, y, z);
                    } else if (crown(m)) region.setType(x, y, z, Material.AIR);
                }
            }
        }
    }

    private void clearCrown(int cx, int cy, int cz) {
        for (int x = cx - 3; x <= cx + 3; x++)
            for (int z = cz - 3; z <= cz + 3; z++)
                for (int y = cy - 3; y <= cy + 4; y++)
                    if (region.isInRegion(x, y, z) && crown(region.getType(x, y, z))) region.setType(x, y, z, Material.AIR);
    }

    /**
     * Levels the ground in a box to height y: cuts away anything above (up to y + clearUp),
     * fills dirt below and puts the top block at y. Only for columns in this chunk.
     */
    public void level(int x1, int z1, int x2, int z2, int y, Material top, int clearUp) {
        int ax = Math.max(Math.min(x1, x2), minX), bx = Math.min(Math.max(x1, x2), minX + 15);
        int az = Math.max(Math.min(z1, z2), minZ), bz = Math.min(Math.max(z1, z2), minZ + 15);
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                foundation(x, z, y, Material.DIRT);
                set(x, y, z, top);
                clear(x, z, y + 1, y + clearUp);
            }
        }
    }

    /**
     * Levels a box to height y and blends the ground around it back into the natural terrain over
     * 'falloff' blocks, so a yard sits in the landscape instead of on a cube of dirt.
     */
    public void terraform(int x1, int z1, int x2, int z2, int y, Material coreTop, int falloff, int clearUp) {
        for (int x = Math.max(x1 - falloff, minX); x <= Math.min(x2 + falloff, minX + 15); x++) {
            for (int z = Math.max(z1 - falloff, minZ); z <= Math.min(z2 + falloff, minZ + 15); z++) {
                double ox = Math.max(0, Math.max(x1 - x, x - x2)), oz = Math.max(0, Math.max(z1 - z, z - z2));
                double d = Math.hypot(ox, oz);
                if (d >= falloff) continue;
                WorldModel.Sample sm = model.sample(x, z);
                int natural = model.height(x, z, sm);
                if (d > 0 && natural < WorldModel.WATER_TOP) continue;
                double t = d / falloff;
                t = t * t * (3 - 2 * t);
                int target = (int) Math.round(y * (1 - t) + natural * t);
                Material top = d == 0 ? coreTop : model.surface(x, z, sm, natural).top();
                Material fill = top == Material.STONE || top == Material.ANDESITE || top == Material.TUFF || top == Material.GRAVEL
                        ? Material.STONE : top == Material.SAND ? Material.SAND : top == Material.BLACKSTONE || top == Material.NETHERRACK
                        ? Material.NETHERRACK : Material.DIRT;
                for (int yy = Math.min(natural, target - 1) + 1; yy < target; yy++) set(x, yy, z, fill);
                set(x, target, z, top);
                clear(x, z, target + 1, Math.max(natural, target) + clearUp);
            }
        }
    }

    /** Deterministic 0..999 value for a position, for random-looking but stable choices. */
    public static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);
        return Math.floorMod(h, 1000);
    }

    public Material get(int x, int y, int z) {
        return region.getType(x, y, z);
    }

    /** Terrain height (top solid block) as generated. */
    public int ground(int x, int z) {
        return model.height(x, z, model.sample(x, z));
    }

    /** Fills a box (inclusive), clipped to this chunk. */
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material mat) {
        int ax = Math.max(Math.min(x1, x2), minX), bx = Math.min(Math.max(x1, x2), minX + 15);
        int az = Math.max(Math.min(z1, z2), minZ), bz = Math.min(Math.max(z1, z2), minZ + 15);
        int ay = Math.max(Math.min(y1, y2), minY + 1), by = Math.min(Math.max(y1, y2), maxY - 1);
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++)
                for (int y = ay; y <= by; y++) region.setType(x, y, z, mat);
    }

    /** A pillar from the ground up to y (exclusive), for each column of the box. */
    public void pillar(int x1, int z1, int x2, int z2, int y, Material mat) {
        int ax = Math.max(Math.min(x1, x2), minX), bx = Math.min(Math.max(x1, x2), minX + 15);
        int az = Math.max(Math.min(z1, z2), minZ), bz = Math.min(Math.max(z1, z2), minZ + 15);
        for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) foundation(x, z, y, mat);
    }

    /** Clears from y1 to y2 (air), only in this chunk. */
    public void clear(int x, int z, int y1, int y2) {
        if (!in(x, z)) return;
        for (int y = y1; y <= y2; y++) set(x, y, z, Material.AIR);
    }

    /** Fills from the ground (or sea floor) up to y-1 with the material, so a structure never floats. */
    public void foundation(int x, int z, int y, Material mat) {
        if (!in(x, z)) return;
        int g = ground(x, z);
        for (int yy = g + 1; yy < y; yy++) set(x, yy, z, mat);
    }
}
