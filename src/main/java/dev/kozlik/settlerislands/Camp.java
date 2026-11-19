package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Map;

/**
 * A trader camp at a natural harbour: a pier, a path up from the water and a 23 x 23 yard with
 * a big tent (with the villager's bed), a market stall with the workstation, a campfire with
 * benches, crates, hay and lanterns. The villager can walk around and always comes back to its bed.
 */
public final class Camp {

    private static final int CENTRE_T = -27;       // yard centre, blocks inland from the coast
    private static final int PATH_END = -15;

    private final StructurePlan.Coast coast;
    private final Villager.Profession profession;
    private final Map<String, String> vars;
    private final Pier pier;

    public Camp(StructurePlan.Coast coast, Villager.Profession profession, String station, String wool) {
        this.coast = coast;
        this.profession = profession;
        this.vars = Map.of("station", station, "wool", wool, "wool2", "white_wool");
        this.pier = new Pier(coast, 30, true);
    }

    private int yardY(WorldModel m) {
        int x = coast.px(CENTRE_T, 0), z = coast.pz(CENTRE_T, 0);
        return Math.max(Pier.DECK + 1, m.height(x, z, m.sample(x, z)));
    }

    private int rot() {
        return Template.rotationFacing(coast.dx(), coast.dz());
    }

    private int[] origin(Template tp) {
        int rot = rot();
        int[] off = tp.offset(tp.w / 2, tp.d / 2, rot);
        return new int[]{coast.px(CENTRE_T, 0) - off[0], coast.pz(CENTRE_T, 0) - off[1]};
    }

    public void build(Builder b) {
        int yx = coast.px(CENTRE_T, 0), yz = coast.pz(CENTRE_T, 0);
        if (!b.touches(yx - 45, yz - 45, yx + 45, yz + 45)) return;
        pier.build(b);
        int y = yardY(b.model);
        boolean nether = b.model.sample(yx, yz).zone() == Zone.NETHER;
        Material pathMat = nether ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;

        // path from the pier up to the yard
        for (double t = 0; t >= PATH_END; t -= 0.5) {
            int py = (int) Math.round(Pier.DECK + (y - Pier.DECK) * (-t) / -PATH_END);
            for (double side = -1.5; side <= 1.5; side += 0.5) {
                int x = coast.px(t, side), z = coast.pz(t, side);
                if (!b.in(x, z)) continue;
                b.foundation(x, z, py, Material.COBBLESTONE);
                b.set(x, py, z, pathMat);
                b.clear(x, z, py + 1, py + 4);
            }
        }
        Template tp = Template.get("camp");
        int rot = rot();
        int[] o = origin(tp);
        b.level(o[0] - 1, o[1] - 1, o[0] + tp.sizeX(rot), o[1] + tp.sizeZ(rot), y, nether ? Material.BLACKSTONE : Material.GRASS_BLOCK, 14);
        tp.place(b, o[0], y, o[1], rot, vars, true);
    }

    public void collectTasks(WorldModel m, List<StructurePlan.Task> tasks) {
        Template tp = Template.get("camp");
        int[] o = origin(tp);
        int[] off = tp.offset(tp.w / 2, 12, rot());
        tasks.add(new StructurePlan.SpawnVillager(o[0] + off[0], yardY(m) + 1, o[1] + off[1], profession, true));
    }
}
