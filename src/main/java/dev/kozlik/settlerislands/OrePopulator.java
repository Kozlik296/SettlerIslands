package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Removes every vanilla ore and places the zone's own ores instead (amounts from config.yml).
 * Also scatters glowstone clusters on the Nether zone.
 */
public final class OrePopulator extends BlockPopulator {

    private static final Set<Material> STRIP = EnumSet.noneOf(Material.class);
    private static final Set<Material> HOST = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT);

    static {
        for (Material mat : Material.values()) {
            String n = mat.name();
            if (n.startsWith("LEGACY_")) continue;
            if (n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS") || n.equals("RAW_IRON_BLOCK") || n.equals("RAW_COPPER_BLOCK")
                    || n.equals("RAW_GOLD_BLOCK")) {
                STRIP.add(mat);
            }
        }
    }

    private final SettlerIslands plugin;

    public OrePopulator(SettlerIslands plugin) {
        this.plugin = plugin;
    }

    private static Material replacement(Material ore) {
        String n = ore.name();
        if (n.startsWith("DEEPSLATE_")) return Material.DEEPSLATE;
        if (n.startsWith("NETHER_") || n.equals("ANCIENT_DEBRIS")) return Material.NETHERRACK;
        return Material.STONE;
    }

    @Override
    public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        WorldModel m = WorldModel.get(info.getSeed());
        int bx = chunkX << 4, bz = chunkZ << 4;
        int minY = info.getMinHeight();
        int maxY = info.getMaxHeight() - 1;

        // 1. strip vanilla ores
        int[][] heights = new int[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = bx + lx, z = bz + lz;
                WorldModel.Sample sm = m.sample(x, z);
                int h = m.height(x, z, sm);
                int top = Math.min(maxY, h + 4);
                heights[lx][lz] = h;
                // close any air gaps in the sea that decorations left behind
                if (sm.zone() != Zone.NETHER && h < WorldModel.WATER_TOP) {
                    for (int y = h + 1; y <= WorldModel.WATER_TOP; y++) {
                        if (region.getType(x, y, z).isAir()) region.setType(x, y, z, Material.WATER);
                    }
                }
                for (int y = minY + 1; y <= top; y++) {
                    Material t = region.getType(x, y, z);
                    if (STRIP.contains(t)) region.setType(x, y, z, replacement(t));
                }
            }
        }

        // 2. place this zone's ores
        WorldModel.Sample centre = m.sample(bx + 8, bz + 8);
        if (!centre.land()) return;
        Zone zone = centre.zone();
        List<OreSpec> specs = plugin.ores().getOrDefault(zone, List.of());
        if (!specs.isEmpty()) {
            Map<Zone, Integer> counts = m.zoneChunks(plugin.borderSize());
            int chunks = Math.max(1, counts.getOrDefault(zone, 1));
            for (OreSpec spec : specs) {
                double veins = spec.total() / (double) chunks / Math.max(1, spec.vein());
                int n = poisson(random, veins);
                for (int i = 0; i < n; i++) placeVein(region, random, spec, bx, bz, heights, minY);
            }
        }

        // 3. glowstone clusters on the Nether zone
        if (zone == Zone.NETHER && random.nextDouble() < plugin.glowstonePerChunk()) {
            int lx = 2 + random.nextInt(12), lz = 2 + random.nextInt(12);
            int x = bx + lx, z = bz + lz;
            int y = heights[lx][lz] + 1;
            int size = 3 + random.nextInt(5);
            for (int k = 0; k < size; k++) {
                int px = x + random.nextInt(3) - 1, pz = z + random.nextInt(3) - 1, py = y + random.nextInt(2);
                if (region.isInRegion(px, py, pz) && region.getType(px, py, pz).isAir()) region.setType(px, py, pz, Material.GLOWSTONE);
            }
        }
    }

    private static void placeVein(LimitedRegion region, Random random, OreSpec spec, int bx, int bz, int[][] heights, int minY) {
        int lx = random.nextInt(16), lz = random.nextInt(16);
        int top = Math.min(spec.maxY(), heights[lx][lz] - 3);
        int bottom = Math.max(spec.minY(), minY + 2);
        if (top < bottom) return;
        int x = bx + lx, z = bz + lz, y = bottom + random.nextInt(top - bottom + 1);
        int size = Math.max(1, (int) Math.round(spec.vein() * (0.5 + random.nextDouble())));
        for (int i = 0; i < size; i++) {
            if (x >= bx && x < bx + 16 && z >= bz && z < bz + 16 && region.isInRegion(x, y, z)) {
                Material host = region.getType(x, y, z);
                if (HOST.contains(host)) {
                    Material ore = spec.oreFor(host);
                    if (ore != null) region.setType(x, y, z, ore);
                }
            }
            x += random.nextInt(3) - 1;
            y += random.nextInt(3) - 1;
            z += random.nextInt(3) - 1;
        }
    }

    private static int poisson(Random random, double mean) {
        if (mean <= 0) return 0;
        if (mean > 30) return (int) Math.max(0, Math.round(mean + Math.sqrt(mean) * random.nextGaussian()));
        double l = Math.exp(-mean), p = 1;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > l);
        return k - 1;
    }
}
