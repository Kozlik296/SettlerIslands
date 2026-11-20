package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Map;

/**
 * A trader settlement at a natural harbour, built in the style of its island (logger cabins,
 * a glassworks, a mining camp, shepherd lodges, a blackstone outpost). It looks along its
 * stretch of coast for a low, flat spot, gets a straight pier, a path up from the water and the
 * yard blended into the slope. Each villager sleeps in a bed of its own colour; the colour says
 * which fixed trade list from trades.yml it gets.
 */
public final class Settlement {

    private static final String[] COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    public final String zone;
    private final StructurePlan.Coast coast;
    private final Template tp;
    private final Pier pier;
    private final int yardY, centreT, frontT;

    public Settlement(WorldModel m, Layout.Island isl, double angle, String zone) {
        this.zone = zone;
        this.tp = Template.get("settlement_" + zone);
        this.centreT = -(tp.d / 2 + 14);
        this.frontT = centreT + tp.d / 2;
        this.coast = bestSite(m, isl, angle, centreT, tp.w / 2 + 2, tp.d / 2 + 2);
        this.pier = new Pier(coast, 28, true);
        int sum = 0, n = 0;
        int r = Math.max(4, tp.d / 2 - 3);
        for (int a = -r; a <= r; a += Math.max(1, r / 2))
            for (int s = -r; s <= r; s += Math.max(1, r / 2)) {
                int x = coast.px(centreT + a, s), z = coast.pz(centreT + a, s);
                sum += m.height(x, z, m.sample(x, z));
                n++;
            }
        this.yardY = Math.max(Pier.DECK + 1, Math.min(Pier.DECK + 12, Math.round(sum / (float) n)));
    }

    /** Tries the coast around the given angle and keeps the lowest, flattest yard site. */
    private static StructurePlan.Coast bestSite(WorldModel m, Layout.Island isl, double angle, int centreT, int halfW, int halfD) {
        StructurePlan.Coast best = null;
        double bestScore = Double.MAX_VALUE;
        for (double a = angle - 40; a <= angle + 40; a += 4) {
            StructurePlan.Coast c = StructurePlan.coast(m, isl, a).snapped();
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, sum = 0, n = 0;
            boolean ok = true;
            for (int t = centreT - halfD; t <= -4; t += 4) {
                for (int s = -halfW; s <= halfW; s += 4) {
                    int x = c.px(t, s), z = c.pz(t, s);
                    WorldModel.Sample sm = m.sample(x, z);
                    boolean yard = t <= centreT + halfD;
                    if (yard && (!sm.land() || sm.island() != isl)) ok = false;
                    int h = m.height(x, z, sm);
                    if (yard) {
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

    private int[] origin() {
        int[] off = tp.offset(tp.w / 2, tp.d / 2, rot());
        return new int[]{coast.px(centreT, 0) - off[0], coast.pz(centreT, 0) - off[1]};
    }

    /** A spot to teleport to: the front of the yard. */
    public int[] spot() {
        return new int[]{coast.px(frontT + 2, 0), yardY, coast.pz(frontT + 2, 0)};
    }

    private Material pathBlock(int x, int z) {
        int h = Builder.hash(x, 3, z);
        return switch (zone) {
            case "nether" -> h < 600 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE;
            case "sandbanks" -> h < 500 ? Material.SMOOTH_SANDSTONE : h < 800 ? Material.CUT_SANDSTONE : Material.SANDSTONE;
            case "ironstone" -> h < 450 ? Material.GRAVEL : h < 800 ? Material.COBBLESTONE : Material.ANDESITE;
            case "highlands" -> h < 450 ? Material.DIRT_PATH : h < 750 ? Material.GRAVEL : Material.COBBLESTONE;
            default -> h < 650 ? Material.DIRT_PATH : Material.COARSE_DIRT;
        };
    }

    private String stairBlock() {
        return switch (zone) {
            case "nether" -> "polished_blackstone_brick_stairs";
            case "sandbanks" -> "sandstone_stairs";
            case "ironstone" -> "cobblestone_stairs";
            case "highlands" -> "cobblestone_stairs";
            default -> "spruce_stairs";
        };
    }

    private Material yardTop() {
        return switch (zone) {
            case "nether" -> Material.BLACKSTONE;
            case "sandbanks" -> Material.SAND;
            case "ironstone" -> Material.COARSE_DIRT;
            default -> Material.GRASS_BLOCK;
        };
    }

    public void build(Builder b) {
        int yx = coast.px(centreT, 0), yz = coast.pz(centreT, 0);
        int reach = Math.max(tp.w, tp.d) / 2 + 30;
        if (!b.touches(yx - reach, yz - reach, yx + reach, yz + reach)) return;
        int[] o = origin();
        int sx = tp.sizeX(rot()), sz = tp.sizeZ(rot());
        b.clearTrees(o[0] - 14, o[1] - 14, o[0] + sx + 14, o[1] + sz + 14);
        b.terraform(o[0], o[1], o[0] + sx - 1, o[1] + sz - 1, yardY, yardTop(), 12, 18);
        // path from the pier up to the front of the yard, blended in the same way
        String landward = Math.abs(coast.dx()) > Math.abs(coast.dz()) ? (coast.dx() > 0 ? "west" : "east") : (coast.dz() > 0 ? "north" : "south");
        int prev = Pier.DECK;
        for (int t = Pier.START; t >= frontT; t--) {
            int py = (int) Math.round(Pier.DECK + (yardY - Pier.DECK) * (double) (Pier.START - t) / (Pier.START - frontT));
            boolean rise = py > prev;      // one block up from the cell before: make it a stair, no jumping
            prev = py;
            for (int side = -1; side <= 1; side++) {
                int x = coast.px(t, side), z = coast.pz(t, side);
                if (!b.in(x, z)) continue;
                b.foundation(x, z, py, Material.COBBLESTONE);
                if (rise) {
                    b.set(x, py - 1, z, pathBlock(x, z));
                    b.set(x, py, z, "minecraft:" + stairBlock() + "[facing=" + landward + ",half=bottom]");
                } else b.set(x, py, z, pathBlock(x, z));
                b.clear(x, z, py + 1, py + 5);
            }
        }
        pier.build(b);
        tp.place(b, o[0], yardY, o[1], rot(), Map.of(), true);
    }

    public void placements(List<StructurePlan.Placement> out) {
        int[] o = origin();
        out.add(new StructurePlan.Placement(tp, o[0], yardY, o[1], rot()));
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        int[] o = origin();
        for (int[] c : tp.findContaining("_bed[part=head")) {
            String name = tp.blockAt(c[0], c[1], c[2]).substring("minecraft:".length());
            String colour = name.substring(0, name.indexOf("_bed"));
            boolean known = false;
            for (String k : COLOURS) known |= k.equals(colour);
            if (!known) continue;
            int[] off = tp.offset(c[0], c[2], rot());
            tasks.add(new StructurePlan.SpawnVillager(o[0] + off[0], yardY + c[1] + 1, o[1] + off[1],
                    Villager.Profession.NONE, true, zone + "/" + colour));
        }
        if (zone.equals("highlands")) {
            for (int[] c : new int[][]{{5, 14}, {7, 16}, {8, 14}, {5, 17}, {9, 17}}) {
                int[] off = tp.offset(c[0], c[1], rot());
                tasks.add(new StructurePlan.SpawnEntity(o[0] + off[0], yardY + 1, o[1] + off[1], EntityType.SHEEP));
            }
        }
    }
}
