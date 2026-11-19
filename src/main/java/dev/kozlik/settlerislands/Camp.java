package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Map;

/**
 * A trader camp at a natural harbour. The camp looks along its stretch of coast for a low, flat spot,
 * then gets a straight pier, a path up from the water and a 23 x 23 yard (tent with the villager's
 * bed, market stall with the workstation, campfire, crates, lanterns) blended into the slope.
 */
public final class Camp {

    private static final int CENTRE_T = -27;       // yard centre, blocks inland from the coast
    private static final int FRONT_T = -16;        // front edge of the yard

    private final StructurePlan.Coast coast;
    private final Villager.Profession profession;
    private final Map<String, String> vars;
    private final Pier pier;
    private final int yardY;

    public Camp(WorldModel m, Layout.Island isl, double angle, Villager.Profession profession, String station, String wool) {
        this.coast = bestSite(m, isl, angle);
        this.profession = profession;
        this.vars = Map.of("station", station, "wool", wool, "wool2", "white_wool");
        this.pier = new Pier(coast, 28, true);
        int sum = 0, n = 0;
        for (int a = -8; a <= 8; a += 4)
            for (int s = -8; s <= 8; s += 4) {
                int x = coast.px(CENTRE_T + a, s), z = coast.pz(CENTRE_T + a, s);
                sum += m.height(x, z, m.sample(x, z));
                n++;
            }
        this.yardY = Math.max(Pier.DECK + 1, Math.min(Pier.DECK + 12, Math.round(sum / (float) n)));
    }

    /** Tries the coast around the given angle and keeps the lowest, flattest yard site. */
    private static StructurePlan.Coast bestSite(WorldModel m, Layout.Island isl, double angle) {
        StructurePlan.Coast best = null;
        double bestScore = Double.MAX_VALUE;
        for (double a = angle - 40; a <= angle + 40; a += 4) {
            StructurePlan.Coast c = StructurePlan.coast(m, isl, a).snapped();
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, sum = 0, n = 0;
            boolean ok = true;
            for (int t = -38; t <= -4; t += 4) {
                for (int s = -12; s <= 12; s += 4) {
                    int x = c.px(t, s), z = c.pz(t, s);
                    WorldModel.Sample sm = m.sample(x, z);
                    if (t <= -16 && (!sm.land() || sm.island() != isl)) ok = false;
                    int h = m.height(x, z, sm);
                    if (t <= -16) {
                        min = Math.min(min, h);
                        max = Math.max(max, h);
                        sum += h;
                        n++;
                    }
                }
            }
            // the pier must reach open water
            int ex = c.px(20, 0), ez = c.pz(20, 0);
            if (m.sample(ex, ez).land()) ok = false;
            if (!ok) continue;
            double mean = sum / (double) n;
            double score = (max - min) * 3 + Math.max(0, mean - 68) * 2 + Math.abs(a - angle) * 0.1;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best != null ? best : StructurePlan.coast(m, isl, angle).snapped();
    }

    private int rot() {
        return Template.rotationFacing(coast.dx(), coast.dz());
    }

    private int[] origin(Template tp) {
        int[] off = tp.offset(tp.w / 2, tp.d / 2, rot());
        return new int[]{coast.px(CENTRE_T, 0) - off[0], coast.pz(CENTRE_T, 0) - off[1]};
    }

    public void build(Builder b) {
        int yx = coast.px(CENTRE_T, 0), yz = coast.pz(CENTRE_T, 0);
        if (!b.touches(yx - 50, yz - 50, yx + 50, yz + 50)) return;
        boolean nether = b.model.sample(yx, yz).zone() == Zone.NETHER;
        Template tp = Template.get("camp");
        int[] o = origin(tp);
        b.terraform(o[0], o[1], o[0] + tp.sizeX(rot()) - 1, o[1] + tp.sizeZ(rot()) - 1, yardY,
                nether ? Material.BLACKSTONE : Material.GRASS_BLOCK, 10, 16);
        // path from the pier up to the front of the yard, blended in the same way
        Material pathMat = nether ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
        for (int t = Pier.START; t >= FRONT_T; t--) {
            int py = (int) Math.round(Pier.DECK + (yardY - Pier.DECK) * (double) (Pier.START - t) / (Pier.START - FRONT_T));
            for (int side = -1; side <= 1; side++) {
                int x = coast.px(t, side), z = coast.pz(t, side);
                if (!b.in(x, z)) continue;
                b.foundation(x, z, py, Material.COBBLESTONE);
                b.set(x, py, z, pathMat);
                b.clear(x, z, py + 1, py + 5);
            }
        }
        pier.build(b);
        tp.place(b, o[0], yardY, o[1], rot(), vars, true);
    }

    public void collectTasks(WorldModel m, List<StructurePlan.Task> tasks) {
        Template tp = Template.get("camp");
        int[] o = origin(tp);
        int[] off = tp.offset(tp.w / 2, 12, rot());
        tasks.add(new StructurePlan.SpawnVillager(o[0] + off[0], yardY + 1, o[1] + off[1], profession, true));
    }
}
