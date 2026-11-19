package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.List;

/**
 * A trader camp at a natural harbour: a pier out to sea, a path up from the water,
 * a small cleared yard with a wool tent, a campfire, barrels, a workstation and one villager.
 */
public final class Camp {

    private static final int YARD_T = -24;   // yard centre, 24 blocks inland from the coast
    private static final int PATH_LEN = 18;

    private final StructurePlan.Coast coast;
    private final Villager.Profession profession;
    private final Material workstation;
    private final Material wool;
    private final Pier pier;

    public Camp(StructurePlan.Coast coast, Villager.Profession profession, Material workstation, Material wool) {
        this.coast = coast;
        this.profession = profession;
        this.workstation = workstation;
        this.wool = wool;
        this.pier = new Pier(coast, 30, false);
    }

    private int yardY(WorldModel m) {
        int x = coast.px(YARD_T, 0), z = coast.pz(YARD_T, 0);
        return Math.max(Pier.DECK, m.height(x, z, m.sample(x, z)));
    }

    public void build(Builder b) {
        StructurePlan.Coast c = coast;
        int yx = c.px(YARD_T, 0), yz = c.pz(YARD_T, 0);
        if (!b.touches(yx - 40, yz - 40, yx + 40, yz + 40)) return;
        pier.build(b);
        int yardY = yardY(b.model);
        boolean nether = b.model.sample(yx, yz).zone() == Zone.NETHER;
        Material pathMat = nether ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
        Material yardMat = nether ? Material.BLACKSTONE : Material.COARSE_DIRT;

        // path from the pier up to the yard
        for (double t = 0; t >= -PATH_LEN; t -= 0.5) {
            int y = (int) Math.round(Pier.DECK + (yardY - Pier.DECK) * (-t) / PATH_LEN);
            for (double side = -1; side <= 1; side += 0.5) {
                int x = c.px(t, side), z = c.pz(t, side);
                if (!b.in(x, z)) continue;
                b.foundation(x, z, y, Material.COBBLESTONE);
                b.set(x, y, z, pathMat);
                b.clear(x, z, y + 1, y + 4);
            }
        }
        // level yard
        for (double t = YARD_T - 6; t <= YARD_T + 6; t += 0.5) {
            for (double side = -6; side <= 6; side += 0.5) {
                int x = c.px(t, side), z = c.pz(t, side);
                if (!b.in(x, z)) continue;
                b.foundation(x, z, yardY, Material.DIRT);
                b.set(x, yardY, z, yardMat);
                b.clear(x, z, yardY + 1, yardY + 10);
            }
        }
        // A-frame tent, 6 long, ridge 3 high
        for (double t = YARD_T - 4; t <= YARD_T + 1; t += 0.5) {
            for (double side = -2; side <= 2; side += 0.5) {
                int x = c.px(t, side), z = c.pz(t, side);
                if (!b.in(x, z)) continue;
                int top = 3 - (int) Math.round(Math.abs(side));
                b.set(x, yardY + Math.max(1, top), z, wool);
                if (Math.abs(side) >= 2) b.set(x, yardY + 1, z, wool);
            }
        }
        set(b, YARD_T + 5, 0, yardY + 1, "minecraft:campfire[lit=true]");
        set(b, YARD_T - 1, 3, yardY + 1, Material.BARREL);
        set(b, YARD_T + 1, 3, yardY + 1, Material.BARREL);
        set(b, YARD_T - 3, -3, yardY + 1, Material.BARREL);
        set(b, YARD_T - 3, 1, yardY + 1, workstation);
    }

    private void set(Builder b, double t, double side, int y, Material mat) {
        b.set(coast.px(t, side), y, coast.pz(t, side), mat);
    }

    private void set(Builder b, double t, double side, int y, String data) {
        b.set(coast.px(t, side), y, coast.pz(t, side), data);
    }

    public void collectTasks(WorldModel m, List<StructurePlan.Task> tasks) {
        int y = yardY(m) + 1;
        tasks.add(new StructurePlan.SpawnVillager(coast.px(YARD_T - 1, -1), y, coast.pz(YARD_T - 1, -1), profession, true, workstation));
    }
}
