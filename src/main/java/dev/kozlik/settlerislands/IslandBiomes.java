package dev.kozlik.settlerislands;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/** Biomes follow the zones (see WorldModel.biome). */
public final class IslandBiomes extends BiomeProvider {

    private static final List<Biome> ALL = List.of(
            Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.MEADOW, Biome.FLOWER_FOREST, Biome.BEACH,
            Biome.STONY_PEAKS, Biome.WINDSWEPT_GRAVELLY_HILLS, Biome.STONY_SHORE, Biome.DRIPSTONE_CAVES,
            Biome.DARK_FOREST, Biome.PALE_GARDEN,
            Biome.DESERT, Biome.BADLANDS,
            Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.BIRCH_FOREST, Biome.FOREST, Biome.JUNGLE, Biome.CHERRY_GROVE, Biome.MANGROVE_SWAMP,
            Biome.FROZEN_PEAKS, Biome.SNOWY_SLOPES, Biome.GROVE, Biome.SNOWY_TAIGA,
            Biome.BASALT_DELTAS, Biome.SOUL_SAND_VALLEY, Biome.NETHER_WASTES, Biome.CRIMSON_FOREST, Biome.WARPED_FOREST,
            Biome.SPARSE_JUNGLE, Biome.LUSH_CAVES,
            Biome.OCEAN, Biome.DEEP_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.WARM_OCEAN, Biome.DEEP_LUKEWARM_OCEAN);

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return WorldModel.get(worldInfo.getSeed()).biome(x, y, z);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return ALL;
    }
}
