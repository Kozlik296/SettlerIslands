package dev.kozlik.settlerislands;

import org.bukkit.Material;

/** One ore type for one zone, read from config.yml. */
public record OreSpec(Material ore, Material deepslateOre, int total, int minY, int maxY, int vein) {

    public static OreSpec of(Material ore, int total, int minY, int maxY, int vein) {
        Material deep = Material.matchMaterial("DEEPSLATE_" + ore.name());
        return new OreSpec(ore, deep, total, minY, maxY, vein);
    }

    private boolean isNetherOre() {
        return ore == Material.NETHER_QUARTZ_ORE || ore == Material.NETHER_GOLD_ORE || ore == Material.ANCIENT_DEBRIS;
    }

    /** The ore block to place into the given host block, or null if it does not belong there. */
    public Material oreFor(Material host) {
        boolean netherHost = host == Material.NETHERRACK || host == Material.BLACKSTONE || host == Material.BASALT;
        if (isNetherOre()) {
            if (ore == Material.ANCIENT_DEBRIS) return netherHost || host == Material.DEEPSLATE ? ore : null;
            return host == Material.NETHERRACK ? ore : null;
        }
        if (netherHost) return null;
        if (host == Material.DEEPSLATE || host == Material.TUFF) return deepslateOre != null ? deepslateOre : ore;
        return ore;
    }
}
