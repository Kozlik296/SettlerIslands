package dev.kozlik.settlerislands;

import org.bukkit.Material;

/**
 * A straight wooden pier, 3 wide, on posts. It starts a few blocks up the shore so it always
 * joins the land, and runs out to sea along the (axis-aligned) direction of the coast point.
 */
public record Pier(StructurePlan.Coast coast, int length, boolean railed) {

    public static final int DECK = WorldModel.WATER_TOP + 1;
    public static final int START = -4;

    public void build(Builder b) {
        StructurePlan.Coast c = coast;
        int ex = c.px(length, 0), ez = c.pz(length, 0);
        int sx = c.px(START, 0), sz = c.pz(START, 0);
        if (!b.touches(Math.min(sx, ex) - 3, Math.min(sz, ez) - 3, Math.max(sx, ex) + 3, Math.max(sz, ez) + 3)) return;
        for (int t = START; t <= length; t++) {
            for (int side = -1; side <= 1; side++) {
                int x = c.px(t, side), z = c.pz(t, side);
                if (!b.in(x, z)) continue;
                b.set(x, DECK, z, Material.SPRUCE_PLANKS);
                b.clear(x, z, DECK + 1, DECK + 5);
                // posts under the deck edges
                if (side != 0 && t > 0 && (t % 4 == 0 || t == length)) {
                    for (int y = b.ground(x, z) + 1; y < DECK; y++) b.set(x, y, z, Material.SPRUCE_LOG);
                }
                // lantern posts on both edges, every 8 blocks and at the end
                if (railed && side != 0 && t > 0 && (t % 8 == 0 || t == length)) {
                    b.set(x, DECK + 1, z, Material.SPRUCE_FENCE);
                    b.set(x, DECK + 2, z, Material.LANTERN);
                }
            }
        }
    }
}
