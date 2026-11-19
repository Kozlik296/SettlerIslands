package dev.kozlik.settlerislands;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * Builds the island world: our own terrain (stone, surface, sea), vanilla caves,
 * vanilla trees/plants/animals from the biomes, no vanilla structures, and our own ores.
 */
public final class IslandGenerator extends ChunkGenerator {

    /** Where new players appear: west part of Meadow, near the harbour. */
    public static final int SPAWN_X = -1960, SPAWN_Z = 1060;

    private final SettlerIslands plugin;

    public IslandGenerator(SettlerIslands plugin) {
        this.plugin = plugin;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        WorldModel m = WorldModel.get(info.getSeed());
        int minY = data.getMinHeight();
        int maxY = data.getMaxHeight() - 1;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = (chunkX << 4) + lx, z = (chunkZ << 4) + lz;
                WorldModel.Sample sm = m.sample(x, z);
                int h = Math.min(maxY - 1, m.height(x, z, sm));
                WorldModel.Surface sf = m.surface(x, z, sm, h);

                data.setBlock(lx, minY, lz, Material.BEDROCK);
                for (int y = minY + 1; y <= h; y++) {
                    int depth = h - y;
                    Material mat;
                    if (depth == 0) mat = sf.top();
                    else if (depth <= sf.underDepth()) mat = sf.banded() ? WorldModel.band(y) : sf.under();
                    else mat = y < sf.deepY() ? sf.deep() : sf.stone();
                    if (y <= minY + 3 && random.nextInt(y - minY + 1) == 0) mat = Material.BEDROCK;
                    else if (m.cave(x, y, z, sm, h, minY)) mat = y <= minY + 10 ? Material.LAVA : Material.CAVE_AIR;
                    data.setBlock(lx, y, lz, mat);
                }
                if (sm.zone() == Zone.NETHER) {
                    if (m.lavaPool(x, z, sm)) {
                        for (int y = h + 1; y <= WorldModel.LAVA_LEVEL; y++) data.setBlock(lx, y, lz, Material.LAVA);
                    }
                } else {
                    for (int y = h + 1; y <= WorldModel.WATER_TOP; y++) data.setBlock(lx, y, lz, Material.WATER);
                }
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new IslandBiomes();
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new OrePopulator(plugin));
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        WorldModel m = WorldModel.get(world.getSeed());
        for (int r = 0; r < 400; r += 8) {
            for (int a = 0; a < 360; a += 30) {
                int x = SPAWN_X + (int) (r * Math.cos(Math.toRadians(a)));
                int z = SPAWN_Z + (int) (r * Math.sin(Math.toRadians(a)));
                WorldModel.Sample sm = m.sample(x, z);
                if (sm.zone() == Zone.MEADOW && sm.s() > 60) {
                    return new Location(world, x + 0.5, m.height(x, z, sm) + 1, z + 0.5);
                }
            }
        }
        return new Location(world, SPAWN_X + 0.5, 80, SPAWN_Z + 0.5);
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return true; }
    @Override public boolean shouldGenerateMobs() { return true; }
    @Override public boolean shouldGenerateStructures() { return false; }
}
