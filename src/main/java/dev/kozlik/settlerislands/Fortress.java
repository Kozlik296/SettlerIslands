package dev.kozlik.settlerislands;

import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * The huge Nether fortress, placed from the "fortress" template: a lava lake inside a ring of
 * battlements on pillars, four corner towers with spires, a cathedral keep with glowing windows
 * and a rose window, arched bridges with hanging chains, nether wart gardens and grand stairs.
 */
public final class Fortress {

    public static final int BASE = 56;          // world y of the template's bottom layer
    public static final int DECK = 100;
    public static final int OX = 130, OZ = 125; // template cell of the fortress centre

    public final int fx, fz;

    public Fortress(int fx, int fz) {
        this.fx = fx;
        this.fz = fz;
    }

    public void build(Builder b) {
        Template tp = Template.get("fortress");
        tp.place(b, fx - OX, BASE, fz - OZ, 0, null, true);
    }

    /** True if a spot is on or inside the fortress (used for its mob spawns). */
    public boolean contains(int x, int y, int z) {
        return Math.abs(x - fx) <= 125 && Math.abs(z - fz) <= 115 && y >= DECK - 4;
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
