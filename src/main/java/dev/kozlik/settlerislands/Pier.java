package dev.kozlik.settlerislands;

import org.bukkit.Material;

/** A wooden pier on posts, running from a coast point straight out to sea. */
public record Pier(StructurePlan.Coast coast, int length, boolean unused) {

    public static final int DECK = WorldModel.WATER_TOP + 1;

    public void build(Builder b) {
        StructurePlan.Coast c = coast;
        int ex = c.px(length, 0), ez = c.pz(length, 0);
        if (!b.touches(c.x() - 4, c.z() - 4, ex + 4, ez + 4) && !b.touches(ex - 4, ez - 4, c.x() + 4, c.z() + 4)) return;
        for (double t = -3; t <= length; t += 0.5) {
            for (double side = -1; side <= 1; side += 0.5) {
                int x = c.px(t, side), z = c.pz(t, side);
                if (!b.in(x, z)) continue;
                int g = b.ground(x, z);
                if (t < 1 && g > DECK) continue;              // still on land above the deck
                b.set(x, DECK, z, Material.SPRUCE_PLANKS);
                b.clear(x, z, DECK + 1, DECK + 3);
            }
        }
        for (int t = 2; t <= length; t += 4) {
            for (int side : new int[]{-1, 1}) {
                int x = c.px(t, side * 1.5), z = c.pz(t, side * 1.5);
                if (!b.in(x, z)) continue;
                for (int y = b.ground(x, z) + 1; y < DECK; y++) b.set(x, y, z, Material.SPRUCE_LOG);
                b.set(x, DECK, z, Material.SPRUCE_LOG);
                if (t + 4 > length) {
                    b.set(x, DECK + 1, z, Material.SPRUCE_FENCE);
                    b.set(x, DECK + 2, z, Material.LANTERN);
                }
            }
        }
    }
}
