package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;

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

    public void set(int x, int y, int z, Material mat) {
        if (in(x, z) && y > minY && y < maxY) region.setType(x, y, z, mat);
    }

    public void set(int x, int y, int z, String data) {
        if (in(x, z) && y > minY && y < maxY) {
            region.setBlockData(x, y, z, DATA.computeIfAbsent(data, Bukkit::createBlockData));
        }
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
