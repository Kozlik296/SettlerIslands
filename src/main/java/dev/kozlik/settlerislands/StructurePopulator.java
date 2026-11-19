package dev.kozlik.settlerislands;

import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/** Builds each chunk's part of the village, the fortress, the camps and the piers. */
public final class StructurePopulator extends BlockPopulator {

    @Override
    public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        WorldModel m = WorldModel.get(info.getSeed());
        StructurePlan plan = StructurePlan.get(m, info.getSeed());
        Builder b = new Builder(region, m, chunkX, chunkZ, info.getMinHeight(), info.getMaxHeight());
        plan.village.build(b);
        plan.fortress.build(b);
        for (Camp c : plan.camps) c.build(b);
        for (Pier p : plan.piers) p.build(b);
    }
}
