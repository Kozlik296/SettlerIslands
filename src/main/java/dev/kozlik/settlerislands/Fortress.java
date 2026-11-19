package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * The huge Nether fortress, placed from the "fortress" template. It stands at ground level on a
 * levelled plateau: a ring of battlements with gatehouses in the middle of each side, a sunken
 * lava lake inside, corner towers with spires, a cathedral keep reached by arched bridges,
 * and nether wart gardens.
 */
public final class Fortress {

    public static final int DECK = 88;           // floor level = level of the plateau around it
    public static final int BASE = DECK - 44;    // world y of the template's bottom layer
    public static final int OX = 130, OZ = 125;  // template cell of the fortress centre
    private static final int FLAT_X = 135, FLAT_Z = 120, FALLOFF = 45;

    public final int fx, fz;

    public Fortress(int fx, int fz) {
        this.fx = fx;
        this.fz = fz;
    }

    public void build(Builder b) {
        if (!b.touches(fx - FLAT_X - FALLOFF, fz - FLAT_Z - FALLOFF, fx + FLAT_X + FALLOFF, fz + FLAT_Z + FALLOFF)) return;
        plateau(b);
        Template.get("fortress").place(b, fx - OX, BASE, fz - OZ, 0, null, true);
    }

    /** Flattens the ground around the fortress to the deck height, blending into the land around it. */
    private void plateau(Builder b) {
        for (int x = b.minX; x < b.minX + 16; x++) {
            for (int z = b.minZ; z < b.minZ + 16; z++) {
                double ox = Math.max(0, Math.abs(x - fx) - FLAT_X), oz = Math.max(0, Math.abs(z - fz) - FLAT_Z);
                double out = Math.hypot(ox, oz);
                if (out >= FALLOFF) continue;
                double t = out / FALLOFF;
                t = t * t * (3 - 2 * t);
                int natural = b.ground(x, z);
                int y = (int) Math.round(DECK * (1 - t) + natural * t);
                int h = Builder.hash(x, 2, z);
                Material top = h < 500 ? Material.NETHERRACK : h < 700 ? Material.BLACKSTONE : h < 850 ? Material.SOUL_SOIL : Material.CRIMSON_NYLIUM;
                for (int yy = Math.min(natural, y) - 2; yy < y; yy++) b.set(x, yy, z, Material.NETHERRACK);
                b.set(x, y, z, top);
                b.clear(x, z, y + 1, Math.max(y, natural) + (out == 0 ? 25 : 12));
            }
        }
    }

    /** True if a spot is on or inside the fortress (used for its mob spawns). */
    public boolean contains(int x, int y, int z) {
        return Math.abs(x - fx) <= 125 && Math.abs(z - fz) <= 115 && y >= DECK - 16;
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        Template tp = Template.get("fortress");
        for (int[] c : tp.find("minecraft:spawner")) {
            tasks.add(new StructurePlan.SetSpawner(fx - OX + c[0], BASE + c[1], fz - OZ + c[2], EntityType.BLAZE));
        }
        for (int[] c : tp.find("minecraft:chest")) {
            tasks.add(new StructurePlan.SetLoot(fx - OX + c[0], BASE + c[1], fz - OZ + c[2], "minecraft:chests/nether_bridge"));
        }
    }
}
