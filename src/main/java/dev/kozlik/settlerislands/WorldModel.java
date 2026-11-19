package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything about the shape of the world that depends only on x/z (and the world seed):
 * which zone a column belongs to, how far it is from the coast, the terrain height,
 * the surface blocks and the biome. Used by both the chunk generator and the biome provider,
 * so terrain and biomes always agree.
 */
public final class WorldModel {

    public static final int SEA_LEVEL = 63;      // water surface is at y = 62
    public static final int WATER_TOP = SEA_LEVEL - 1;

    /** One column of the world. s = blocks from the coast (positive on land). */
    public record Sample(Zone zone, double s, Zone nearZone, Layout.Island island) {
        public boolean land() {
            return s > 0;
        }
    }

    /** Surface layers of one column. */
    public record Surface(Material top, Material under, int underDepth, Material stone, Material deep, int deepY, boolean banded) {
    }

    private static final Map<Long, WorldModel> MODELS = new ConcurrentHashMap<>();

    public static WorldModel get(long seed) {
        return MODELS.computeIfAbsent(seed, WorldModel::new);
    }

    private final Layout layout = Layout.get();
    private final SimplexOctaveGenerator coastA, coastB, floor, relA, relB, ridge, var, var2, lava, surf, caveA, caveB, caveC, warpA, warpB, ridge2, canyonG, canyonMask, crackG, crackMask;
    private volatile Map<Zone, Integer> zoneChunks;

    private WorldModel(long seed) {
        coastA = gen(seed, 1, 3);
        coastB = gen(seed, 2, 2);
        floor = gen(seed, 3, 2);
        relA = gen(seed, 4, 4);
        relB = gen(seed, 5, 3);
        ridge = gen(seed, 6, 4);
        var = gen(seed, 7, 2);
        var2 = gen(seed, 8, 2);
        lava = gen(seed, 9, 2);
        surf = gen(seed, 10, 2);
        caveA = gen(seed, 11, 2);
        caveB = gen(seed, 12, 1);
        caveC = gen(seed, 13, 1);
        warpA = gen(seed, 14, 2);
        warpB = gen(seed, 15, 2);
        ridge2 = gen(seed, 16, 2);
        canyonG = gen(seed, 17, 2);
        canyonMask = gen(seed, 18, 1);
        crackG = gen(seed, 19, 1);
        crackMask = gen(seed, 20, 1);
    }

    private static SimplexOctaveGenerator gen(long seed, int salt, int octaves) {
        SimplexOctaveGenerator g = new SimplexOctaveGenerator(new Random(seed * 31 + salt * 7919L), octaves);
        g.setScale(1.0);
        return g;
    }

    private static double n(SimplexOctaveGenerator g, double x, double z, double scale) {
        return g.noise(x / scale, z / scale, 2.0, 0.5, true);
    }

    private static double ridged(SimplexOctaveGenerator g, double x, double z, double scale) {
        // 0..1, sharp ridges
        double r = 1 - Math.abs(n(g, x, z, scale));
        return r * r;
    }

    private static double smooth(double v, double a, double b) {
        double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    public Layout layout() {
        return layout;
    }

    // ------------------------------------------------------------------ zones

    public Sample sample(int x, int z) {
        double detail = n(coastA, x, z, 64) * 14 + n(coastB, x, z, 18) * 4;
        Layout.Island best = null, iron = null;
        double bestS = -Double.MAX_VALUE, ironS = -Double.MAX_VALUE;
        for (Layout.Island isl : layout.islands()) {
            double s = isl.signedDistance(x, z) + detail;
            if (s > bestS) {
                bestS = s;
                best = isl;
            }
            if (isl.zone == Zone.IRONSTONE) {
                iron = isl;
                ironS = s;
            }
        }
        // Ironstone is drawn over Gloomreach: where both overlap, the column is Ironstone.
        if (iron != null && ironS > 0) {
            best = iron;
            bestS = ironS;
        }
        Zone zone = bestS > 0 ? best.zone : Zone.SEA;
        return new Sample(zone, bestS, best.zone, best);
    }

    /** Variety noise used to choose sub-biomes, from -1 to 1. */
    public double variant(int x, int z) {
        return n(var, x, z, 260);
    }

    public double variant2(int x, int z) {
        return n(var2, x, z, 140);
    }

    // ---------------------------------------------------------------- terrain

    public int height(int x, int z, Sample sm) {
        if (!sm.land()) {
            double s = -sm.s();
            double y;
            if (s < 110) y = 62 - s * 0.13;                  // shallow shelf
            else if (s < 300) y = 47.7 - (s - 110) * 0.17;   // slope down
            else y = 15.4;                                   // deep sea floor
            y += n(floor, x, z, 40) * 3;
            return (int) Math.max(6, y);
        }
        double beach = SEA_LEVEL + Math.min(sm.s() / 30.0, 1.0) * 2;
        double rel = Math.max(1, relief(sm.zone(), x, z, sm.s()));
        Zone zone = sm.zone();
        if (zone == Zone.IRONSTONE || zone == Zone.HIGHLANDS || zone == Zone.GLOOMREACH) rel *= smooth(sm.s(), 6, 60);
        else if (zone == Zone.NETHER) rel = 14 * smooth(sm.s(), 4, 40) + rel * smooth(sm.s(), 40, 200);
        else rel *= smooth(sm.s(), 15, 160);
        int h = (int) (beach + rel);
        if (zone == Zone.NETHER && lavaPool(x, z, sm)) h = LAVA_LEVEL - 4;
        return h;
    }

    // --------------------------------------------------- natural mountain shapes

    private double wx(double x, double z) {
        return x + n(warpA, x, z, 420) * 110;
    }

    private double wz(double x, double z) {
        return z + n(warpB, x, z, 420) * 110;
    }

    /** Broad, warped mountain ranges from 0 (valley) to 1 (peak). */
    private double mountains(int x, int z, double scale) {
        double ax = wx(x, z), az = wz(x, z);
        double r1 = ridged(ridge, ax, az, scale);
        double r2 = ridged(ridge2, ax, az, scale * 0.45);
        double base = n(relA, ax, az, scale * 1.6) * 0.5 + 0.5;
        return Math.pow(r1 * 0.7 + r2 * 0.3, 1.6) * (0.55 + 0.45 * base);
    }

    /** Winding canyons (1 at the bottom of a canyon), only in some regions. */
    private double canyon(int x, int z) {
        double c = Math.abs(n(canyonG, wx(x, z), wz(x, z), 260));
        return (1 - smooth(c, 0.008, 0.04)) * smooth(n(canyonMask, x, z, 500), -0.1, 0.25);
    }

    /** Narrow crevices high up in the mountains. */
    private double crack(int x, int z) {
        double c = Math.abs(n(crackG, x, z, 70));
        return (1 - smooth(c, 0.004, 0.014)) * smooth(n(crackMask, x, z, 300), 0.15, 0.4);
    }

    private double carve(double rel, int x, int z, double dome, double canyonDepth) {
        rel -= canyon(x, z) * Math.min(rel - 4, canyonDepth) * dome;
        rel -= crack(x, z) * Math.min(rel * 0.35, 18) * smooth(rel, 35, 70);
        return rel;
    }

    /** Nether: terraced cliffs and ridges, like the real Nether's ledges. */
    private double netherRaw(int x, int z) {
        double b = 10 + n(relA, x, z, 90) * 18 + mountains(x, z, 150) * 60;
        double t = b / 8.0, f = t - Math.floor(t);
        f = f * f * f * f;                                  // flat ledges, steep risers
        return (Math.floor(t) + f) * 8.0;
    }

    private double relief(Zone zone, int x, int z, double s) {
        return switch (zone) {
            case MEADOW -> 4 + n(relA, x, z, 200) * 7 + n(relB, x, z, 60) * 3;
            case IRONSTONE -> {
                double dome = smooth(s, 10, 380);
                yield carve(12 + dome * (18 + mountains(x, z, 300) * 85), x, z, dome, 40);
            }
            case HIGHLANDS -> {
                double dome = smooth(s, 10, 320);
                yield carve(15 + dome * (20 + mountains(x, z, 280) * 95), x, z, dome, 45);
            }
            case GLOOMREACH -> carve(8 + n(relA, x, z, 110) * 12 + mountains(x, z, 160) * 30, x, z, smooth(s, 10, 120), 22);
            case SANDBANKS -> 2 + Math.abs(n(relA, x, z, 45)) * 7;
            case TIMBERLANDS -> carve(6 + n(relA, x, z, 140) * 12 + n(relB, x, z, 45) * 4 + mountains(x, z, 200) * 18, x, z, 1, 12);
            case NETHER -> netherRaw(x, z);
            case FAR_REEF -> 3 + n(relA, x, z, 70) * 7;
            case ISLET -> 2 + Math.abs(n(relA, x, z, 20)) * 6;
            case SEA -> 0;
        };
    }

    /** Lava lakes on the Nether zone. */
    public boolean lavaPool(int x, int z, Sample sm) {
        return sm.zone() == Zone.NETHER && sm.s() > 230 && netherRaw(x, z) < 17;
    }

    public static final int LAVA_LEVEL = 72;

    // ------------------------------------------------------------------ caves

    /**
     * Our own caves (vanilla caves are off because they punched holes in the sea floor).
     * Only under land, never near the coast and never close to the surface.
     */
    public boolean cave(int x, int y, int z, Sample sm, int h, int minY) {
        if (!sm.land() || sm.s() < 28 || y > h - 8 || y < minY + 6) return false;
        boolean gloom = sm.zone() == Zone.GLOOMREACH;
        // big open chambers
        double cheese = caveA.noise(x / 70.0, y / 35.0, z / 70.0, 2.0, 0.5, true);
        if (cheese > (gloom ? 0.48 : 0.62)) return true;
        // winding tunnels where two noise fields are both near zero
        double a = caveB.noise(x / 45.0, y / 28.0, z / 45.0, 2.0, 0.5, true);
        if (Math.abs(a) > 0.07) return false;
        double b = caveC.noise(x / 45.0, y / 28.0, z / 45.0, 2.0, 0.5, true);
        return Math.abs(b) < (gloom ? 0.09 : 0.06);
    }

    // ---------------------------------------------------------------- surface

    private static final Material[] BANDS = {
            Material.TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.BROWN_TERRACOTTA,
            Material.TERRACOTTA, Material.RED_TERRACOTTA, Material.WHITE_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA};

    public static Material band(int y) {
        return BANDS[Math.floorMod(y / 2, BANDS.length)];
    }

    public Surface surface(int x, int z, Sample sm, int h) {
        double g = n(surf, x, z, 20);
        double v = variant(x, z);
        Zone zone = sm.zone();
        boolean beach = sm.s() < 35 && h <= SEA_LEVEL + 1;
        return switch (zone) {
            case MEADOW, FAR_REEF -> beach || (zone == Zone.FAR_REEF && sm.s() < 50)
                    ? s(Material.SAND, Material.SAND, 4)
                    : s(Material.GRASS_BLOCK, Material.DIRT, 3);
            case IRONSTONE -> {
                if (sm.s() < 30 || g > 0.45) yield s(Material.GRAVEL, Material.GRAVEL, 2);
                if (g > 0.2) yield s(Material.ANDESITE, Material.STONE, 1);
                if (g < -0.45 && h > 120) yield s(Material.TUFF, Material.STONE, 1);
                yield s(Material.STONE, Material.STONE, 1);
            }
            case GLOOMREACH -> {
                if (beach) yield new Surface(Material.GRAVEL, Material.GRAVEL, 2, Material.STONE, Material.DEEPSLATE, 24, false);
                Material top = g > 0.3 ? Material.PODZOL : g < -0.35 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
                yield new Surface(top, Material.DIRT, 2, Material.STONE, Material.DEEPSLATE, 24, false);
            }
            case SANDBANKS -> {
                if (!beach && sm.s() >= 50 && v > 0.35)
                    yield new Surface(Material.RED_SAND, Material.TERRACOTTA, 14, Material.SANDSTONE, Material.DEEPSLATE, 0, true);
                yield new Surface(Material.SAND, Material.SAND, 5, Material.SANDSTONE, Material.DEEPSLATE, 0, false);
            }
            case TIMBERLANDS -> {
                if (beach) yield s(Material.SAND, Material.SAND, 3);
                if (mangrove(x, z, sm)) yield s(Material.MUD, Material.MUD, 3);
                if (v < -0.4) yield s(Material.PODZOL, Material.DIRT, 3);
                yield s(Material.GRASS_BLOCK, Material.DIRT, 3);
            }
            case HIGHLANDS -> {
                if (sm.s() < 30) yield s(Material.GRAVEL, Material.GRAVEL, 2);
                if (h > 112) yield s(Material.SNOW_BLOCK, Material.SNOW_BLOCK, 2);
                yield s(Material.GRASS_BLOCK, Material.DIRT, 2);
            }
            case NETHER -> {
                double inner = smooth(sm.s(), 110, 220);
                if (g + 0.6 > inner * 1.6 + 0.2 || sm.s() < 110) {
                    // outer volcanic rim: blackstone, basalt, tuff, a little magma
                    Material top = g > 0.55 ? Material.MAGMA_BLOCK : g > 0.15 ? Material.BLACKSTONE : g > -0.3 ? Material.BASALT : Material.TUFF;
                    yield new Surface(top, Material.BLACKSTONE, 3, Material.BLACKSTONE, Material.DEEPSLATE, 0, false);
                }
                yield switch (netherKind(v)) {
                case 0 -> new Surface(Material.BASALT, Material.BLACKSTONE, 3, Material.NETHERRACK, Material.DEEPSLATE, 0, false);
                case 1 -> new Surface(Material.SOUL_SAND, Material.SOUL_SOIL, 3, Material.NETHERRACK, Material.DEEPSLATE, 0, false);
                case 3 -> new Surface(Material.CRIMSON_NYLIUM, Material.NETHERRACK, 1, Material.NETHERRACK, Material.DEEPSLATE, 0, false);
                case 4 -> new Surface(Material.WARPED_NYLIUM, Material.NETHERRACK, 1, Material.NETHERRACK, Material.DEEPSLATE, 0, false);
                default -> new Surface(Material.NETHERRACK, Material.NETHERRACK, 1, Material.NETHERRACK, Material.DEEPSLATE, 0, false);
                };
            }
            case ISLET -> g > 0.3 ? s(Material.GRASS_BLOCK, Material.DIRT, 1) : g < -0.3 ? s(Material.GRAVEL, Material.STONE, 1) : s(Material.STONE, Material.STONE, 1);
            case SEA -> {
                if (sm.s() > -110) {
                    if (g > 0.5) yield s(Material.CLAY, Material.CLAY, 2);
                    if (g < -0.5) yield s(Material.GRAVEL, Material.GRAVEL, 2);
                    yield s(Material.SAND, Material.SAND, 3);
                }
                yield g > 0.3 ? s(Material.SAND, Material.SAND, 2) : s(Material.GRAVEL, Material.GRAVEL, 2);
            }
        };
    }

    private static Surface s(Material top, Material under, int depth) {
        return new Surface(top, under, depth, Material.STONE, Material.DEEPSLATE, 0, false);
    }

    private boolean mangrove(int x, int z, Sample sm) {
        return sm.zone() == Zone.TIMBERLANDS && sm.s() < 110 && variant2(x, z) > 0.3;
    }

    /** 0 basalt deltas, 1 soul sand valley, 2 nether wastes, 3 crimson forest, 4 warped forest. */
    private static int netherKind(double v) {
        if (v < -0.45) return 0;
        if (v < -0.1) return 1;
        if (v < 0.25) return 2;
        if (v < 0.55) return 3;
        return 4;
    }

    // ----------------------------------------------------------------- biomes

    public Biome biome(int x, int y, int z) {
        Sample sm = sample(x, z);
        double v = variant(x, z);
        if (!sm.land()) {
            boolean deep = sm.s() < -110;
            return switch (sm.nearZone()) {
                case HIGHLANDS, GLOOMREACH -> deep ? Biome.DEEP_COLD_OCEAN : Biome.COLD_OCEAN;
                case FAR_REEF, SANDBANKS -> deep ? Biome.DEEP_LUKEWARM_OCEAN : Biome.WARM_OCEAN;
                default -> deep ? Biome.DEEP_OCEAN : Biome.OCEAN;
            };
        }
        int h = height(x, z, sm);
        boolean beach = sm.s() < 35 && h <= SEA_LEVEL + 1;
        return switch (sm.zone()) {
            case MEADOW -> beach ? Biome.BEACH : v < -0.35 ? Biome.FLOWER_FOREST : v < 0.1 ? Biome.PLAINS : v < 0.45 ? Biome.MEADOW : Biome.SUNFLOWER_PLAINS;
            case IRONSTONE -> y < 40 ? Biome.DRIPSTONE_CAVES : sm.s() < 40 ? Biome.STONY_SHORE : h > 120 ? Biome.STONY_PEAKS : Biome.WINDSWEPT_GRAVELLY_HILLS;
            case GLOOMREACH -> y < 30 ? Biome.DRIPSTONE_CAVES : beach ? Biome.STONY_SHORE : v > 0.4 ? Biome.PALE_GARDEN : Biome.DARK_FOREST;
            case SANDBANKS -> beach || sm.s() < 50 ? Biome.BEACH : v > 0.35 ? Biome.BADLANDS : Biome.DESERT;
            case TIMBERLANDS -> {
                if (beach) yield Biome.BEACH;
                if (mangrove(x, z, sm)) yield Biome.MANGROVE_SWAMP;
                if (v < -0.4) yield Biome.OLD_GROWTH_SPRUCE_TAIGA;
                if (v < -0.1) yield Biome.BIRCH_FOREST;
                if (v < 0.25) yield Biome.FOREST;
                if (v < 0.55) yield Biome.JUNGLE;
                yield Biome.CHERRY_GROVE;
            }
            case HIGHLANDS -> sm.s() < 30 ? Biome.STONY_SHORE : h > 150 ? Biome.FROZEN_PEAKS : h > 112 ? Biome.SNOWY_SLOPES : v < 0 ? Biome.GROVE : Biome.SNOWY_TAIGA;
            case NETHER -> sm.s() < 140 ? Biome.STONY_SHORE : switch (netherKind(v)) {
                case 0 -> Biome.BASALT_DELTAS;
                case 1 -> Biome.SOUL_SAND_VALLEY;
                case 3 -> Biome.CRIMSON_FOREST;
                case 4 -> Biome.WARPED_FOREST;
                default -> Biome.NETHER_WASTES;
            };
            case FAR_REEF -> y < h - 15 ? Biome.LUSH_CAVES : sm.s() < 50 ? Biome.BEACH : Biome.SPARSE_JUNGLE;
            case ISLET -> Biome.STONY_SHORE;
            case SEA -> Biome.OCEAN;
        };
    }

    // ------------------------------------------------------------ statistics

    /** Number of chunks whose centre is land of each zone, inside the world border. */
    public Map<Zone, Integer> zoneChunks(int borderSize) {
        Map<Zone, Integer> result = zoneChunks;
        if (result == null) {
            synchronized (this) {
                result = zoneChunks;
                if (result == null) {
                    EnumMap<Zone, Integer> counts = new EnumMap<>(Zone.class);
                    for (Zone zz : Zone.values()) counts.put(zz, 0);
                    int half = borderSize / 2 / 16 + 1;
                    for (int cx = -half; cx < half; cx++) {
                        for (int cz = -half; cz < half; cz++) {
                            Sample sm = sample(cx * 16 + 8, cz * 16 + 8);
                            if (sm.land()) counts.merge(sm.zone(), 1, Integer::sum);
                        }
                    }
                    result = counts;
                    zoneChunks = result;
                }
            }
        }
        return result;
    }
}
