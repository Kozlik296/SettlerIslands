package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Map;

/**
 * The main harbour in Meadow's bay: a stone quay wall with a paved promenade, five railed
 * piers on posts with lanterns and wider ends, two harbour buildings behind the promenade,
 * cargo stacks, and boats moored at the piers.
 */
public final class Harbour {

    public static final int QUAY = 65;          // promenade height
    private static final int HALF = 34;         // half length of the quay
    private static final int[] PIERS = {-24, -12, 0, 12, 24};
    private static final int PIER_LEN = 30;

    private final StructurePlan.Coast c;
    private final String landward;              // facing name pointing back to land

    public Harbour(StructurePlan.Coast coast) {
        this.c = coast;
        this.landward = facingName(-coast.dx(), -coast.dz());
    }

    private static String facingName(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? "east" : "west";
        return dz > 0 ? "south" : "north";
    }

    private int x(double t, double s) {
        return c.px(t, s);
    }

    private int z(double t, double s) {
        return c.pz(t, s);
    }

    private static Material quayStone(int x, int y, int z) {
        int h = Builder.hash(x, y, z);
        return h < 600 ? Material.STONE_BRICKS : h < 780 ? Material.MOSSY_STONE_BRICKS : h < 900 ? Material.CRACKED_STONE_BRICKS : Material.COBBLESTONE;
    }

    public void build(Builder b) {
        int cx = c.x(), cz = c.z();
        if (!b.touches(cx - 80, cz - 80, cx + 80, cz + 80)) return;

        // promenade (t -12..0) and quay wall (t 1..2)
        for (double t = -12; t <= 2; t += 0.5) {
            for (double s = -HALF; s <= HALF; s += 0.5) {
                int x = x(t, s), z = z(t, s);
                if (!b.in(x, z)) continue;
                int g = b.ground(x, z);
                for (int y = g + 1; y < QUAY; y++) b.set(x, y, z, quayStone(x, y, z));
                if (t >= 1) {
                    b.set(x, QUAY, z, quayStone(x, QUAY, z));
                    b.clear(x, z, QUAY + 1, QUAY + 8);
                } else {
                    int h = Builder.hash(x, 1, z);
                    b.set(x, QUAY, z, h < 500 ? Material.POLISHED_ANDESITE : h < 850 ? Material.STONE_BRICKS : Material.ANDESITE);
                    b.clear(x, z, QUAY + 1, QUAY + 10);
                }
            }
        }
        // railing on the quay edge with lanterns, gaps where the piers start
        for (int s = -HALF; s <= HALF; s++) {
            boolean gap = false;
            for (int p : PIERS) if (Math.abs(s - p) <= 1) gap = true;
            int x = x(2, s), z = z(2, s);
            if (!gap) b.set(x, QUAY + 1, z, Material.STONE_BRICK_WALL);
            if (!gap && Math.floorMod(s, 8) == 4) {
                b.set(x, QUAY + 2, z, Material.STONE_BRICK_WALL);
                b.set(x, QUAY + 3, z, Material.LANTERN);
            }
        }
        // piers
        for (int p : PIERS) buildPier(b, p);
        // cargo on the promenade
        for (int[] o : new int[][]{{-4, -30}, {-3, -30}, {-4, -29}, {-5, -31}, {-4, 30}, {-3, 29}, {-4, 29}, {-6, 31}, {-2, 18}, {-2, -18}}) {
            int x = x(o[0], o[1]), z = z(o[0], o[1]);
            b.set(x, QUAY + 1, z, Builder.hash(x, 0, z) < 600 ? Material.BARREL : Material.HAY_BLOCK);
            if (Builder.hash(x, 9, z) < 400) b.set(x, QUAY + 2, z, Material.BARREL);
        }
        // harbour buildings behind the promenade, facing the water
        int rot = Template.rotationFacing(c.dx(), c.dz());
        Template tp = Template.get("house_big");
        for (int side : new int[]{-17, 17}) {
            int[] off = tp.offset(tp.w / 2, 10, rot);
            int fx = x(-14, side), fz = z(-14, side);
            int ox = fx - off[0], oz = fz - off[1];
            b.level(ox, oz, ox + tp.sizeX(rot) - 1, oz + tp.sizeZ(rot) - 1, QUAY, Material.STONE_BRICKS, 20);
            Map<String, String> vars = Map.of("roof", side < 0 ? "deepslate_tile" : "dark_oak", "log", "spruce_log",
                    "slog", "stripped_spruce_log", "plank", "spruce_planks", "wall", side < 0 ? "white_terracotta" : "calcite",
                    "station", side < 0 ? "barrel" : "cartography_table");
            tp.place(b, ox, QUAY, oz, rot, vars, true);
        }
    }

    private void buildPier(Builder b, int p) {
        // steps down from the quay to the pier deck
        for (int s = p - 1; s <= p + 1; s++) {
            b.set(x(1, s), QUAY - 1, z(1, s), "minecraft:stone_brick_stairs[facing=" + landward + "]");
            b.clear(x(1, s), z(1, s), QUAY, QUAY + 3);
            b.set(x(2, s), QUAY - 2, z(2, s), Material.SPRUCE_PLANKS);
            b.clear(x(2, s), z(2, s), QUAY - 1, QUAY + 3);
        }
        for (double t = 3; t <= PIER_LEN; t += 0.5) {
            boolean head = t >= PIER_LEN - 3;
            double w = head ? 4 : 1;
            for (double s = p - w; s <= p + w; s += 0.5) {
                int x = x(t, s), z = z(t, s);
                if (!b.in(x, z)) continue;
                b.set(x, Pier.DECK, z, Material.SPRUCE_PLANKS);
                b.clear(x, z, Pier.DECK + 1, Pier.DECK + 4);
            }
        }
        for (int t = 3; t <= PIER_LEN; t++) {
            boolean head = t >= PIER_LEN - 3;
            int w = head ? 5 : 2;
            for (int side : new int[]{-1, 1}) {
                int x = x(t, p + side * w), z = z(t, p + side * w);
                if (t % 4 == 0 || (head && t == PIER_LEN)) {
                    for (int y = b.ground(x, z) + 1; y <= Pier.DECK; y++) b.set(x, y, z, Material.SPRUCE_LOG);
                }
                if (!head && t % 9 == 5) continue;           // gaps in the rail to step into boats
                b.set(x, Pier.DECK + 1, z, Material.SPRUCE_FENCE);
                if (t % 8 == 0) {
                    b.set(x, Pier.DECK + 2, z, Material.SPRUCE_FENCE);
                    b.set(x, Pier.DECK + 3, z, Material.LANTERN);
                }
            }
            if (head && t == PIER_LEN) {
                for (int s = p - 5; s <= p + 5; s++) b.set(x(t + 1, s), Pier.DECK + 1, z(t + 1, s), Material.SPRUCE_FENCE);
            }
        }
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        EntityType[] boats = {EntityType.OAK_BOAT, EntityType.SPRUCE_BOAT, EntityType.OAK_CHEST_BOAT, EntityType.BIRCH_BOAT};
        int i = 0;
        for (int p : PIERS) {
            for (int t : new int[]{14, 23}) {
                int side = (i % 2 == 0) ? 4 : -4;
                tasks.add(new StructurePlan.SpawnBoat(x(t, p + side), Pier.DECK, z(t, p + side), boats[i % boats.length]));
                i++;
            }
        }
    }
}
