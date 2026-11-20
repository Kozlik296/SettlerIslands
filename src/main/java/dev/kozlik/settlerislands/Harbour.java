package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Map;

/**
 * The main harbour in Meadow's bay: a stone quay wall with a paved promenade, five railed
 * piers on posts with lanterns and wider ends, two harbour buildings behind the promenade,
 * cargo stacks, and boats moored at the piers. In the middle of the promenade, where the village
 * road arrives, is the harbour market (stalls and a bell; its fisherman and cartographer sleep in
 * the harbour buildings). A wooden crane, fishing huts and drying racks on the outer piers and tall
 * lamps along the promenade finish it.
 */
public final class Harbour {

    public static final int QUAY = 65;          // promenade height
    private static final int HALF = 34;         // half length of the quay
    private static final int[] PIERS = {-24, -8, 8, 24};
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

    /** Where the village road meets the promenade. */
    public int[] spot() {
        return new int[]{x(-8, 0), z(-8, 0)};
    }

    /** World direction of +t (out to sea) and +s (along the quay), snapped to axes. */
    private int tx() { return (int) Math.round(c.dx()); }
    private int tz() { return (int) Math.round(c.dz()); }
    private int sx() { return (int) Math.round(-c.dz()); }
    private int sz() { return (int) Math.round(c.dx()); }

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
            for (int p : PIERS) if (Math.abs(s - p) <= 2) gap = true;
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
        market(b);
        crane(b, -16);
        for (int p : new int[]{-24, 24}) fishingHut(b, p);
        // tall lamps along the back of the promenade
        for (int s = -30; s <= 30; s += 10) {
            if (Math.abs(s) < 5) continue;
            int x = x(-11, s), z = z(-11, s);
            for (int k = 1; k <= 3; k++) b.set(x, QUAY + k, z, Material.STONE_BRICK_WALL);
            b.set(x, QUAY + 4, z, Material.LANTERN);
        }
        // harbour buildings behind the promenade, facing the water
        int rot = Template.rotationFacing(c.dx(), c.dz());
        Template tp = Template.get("house_big");
        for (int side : new int[]{-17, 17}) {
            int[] o = buildingOrigin(tp, side);
            int ox = o[0], oz = o[1];
            b.clearTrees(ox - 4, oz - 4, ox + tp.sizeX(rot) + 4, oz + tp.sizeZ(rot) + 4);
            b.level(ox, oz, ox + tp.sizeX(rot) - 1, oz + tp.sizeZ(rot) - 1, QUAY, Material.STONE_BRICKS, 20);
            Map<String, String> vars = Map.of("roof", side < 0 ? "deepslate_tile" : "dark_oak", "log", "spruce_log",
                    "slog", "stripped_spruce_log", "plank", "spruce_planks", "wall", side < 0 ? "white_terracotta" : "calcite",
                    "station", side < 0 ? "barrel" : "cartography_table");
            tp.place(b, ox, QUAY, oz, rot, vars, true);
        }
    }

    public void placements(List<StructurePlan.Placement> out) {
        int rot = Template.rotationFacing(c.dx(), c.dz());
        Template tp = Template.get("house_big");
        for (int side : new int[]{-17, 17}) {
            int[] o = buildingOrigin(tp, side);
            out.add(new StructurePlan.Placement(tp, o[0], QUAY, o[1], rot));
        }
    }

    private int[] buildingOrigin(Template tp, int side) {
        int rot = Template.rotationFacing(c.dx(), c.dz());
        int[] off = tp.offset(tp.w / 2, 10, rot);
        return new int[]{x(-14, side) - off[0], z(-14, side) - off[1]};
    }

    /** Four stalls on both sides of the walkway where the village road comes in, and a bell. */
    private void market(Builder b) {
        String[] wools = {"blue_wool", "yellow_wool", "red_wool", "green_wool"};
        int i = 0;
        for (int t : new int[]{-9, -4}) {
            for (int side : new int[]{-1, 1}) {
                int x = x(t, side * 5), z = z(t, side * 5);
                // the stall faces the walkway (towards s = 0)
                Village.stall(b, x, QUAY, z, -side * sx(), -side * sz(), wools[i % wools.length], Builder.hash(x, 5, z) + i);
                i++;
            }
        }
        // bell post at the quay edge, the market's meeting point
        int bx = x(-1, 0), bz = z(-1, 0);
        for (int side : new int[]{-1, 1}) {
            for (int k = 1; k <= 3; k++) b.set(x(-1, side), QUAY + k, z(-1, side), Material.SPRUCE_FENCE);
            b.set(x(-1, side), QUAY + 4, z(-1, side), "minecraft:spruce_slab[type=bottom]");
        }
        b.set(bx, QUAY + 4, bz, "minecraft:spruce_slab[type=bottom]");
        b.set(bx, QUAY + 3, bz, "minecraft:bell[attachment=ceiling,facing=" + facingName(sx(), sz()) + "]");
    }

    /** A wooden crane on the quay edge: post, boom out over the water, chain with a crate. */
    private void crane(Builder b, int s) {
        for (int k = 1; k <= 9; k++) b.set(x(1, s), QUAY + k, z(1, s), Material.SPRUCE_LOG);
        String axis = tx() != 0 ? "x" : "z";
        for (int t = -1; t <= 6; t++) b.set(x(t, s), QUAY + 10, z(t, s), "minecraft:spruce_log[axis=" + axis + "]");
        b.set(x(-1, s), QUAY + 9, z(-1, s), Material.COBBLESTONE);                     // counterweight
        b.set(x(-1, s), QUAY + 8, z(-1, s), Material.COBBLESTONE);
        b.set(x(2, s), QUAY + 9, z(2, s), Material.SPRUCE_FENCE);                      // brace
        b.set(x(3, s), QUAY + 9, z(3, s), "minecraft:spruce_trapdoor[half=top,facing=" + facingName(tx(), tz()) + "]");
        for (int y = QUAY + 9; y >= QUAY + 5; y--) b.set(x(6, s), y, z(6, s), "minecraft:iron_chain[axis=y]");
        b.set(x(6, s), QUAY + 4, z(6, s), Material.BARREL);
        b.set(x(0, s), QUAY + 1, z(0, s), Material.BARREL);
        b.set(x(0, s + 1), QUAY + 1, z(0, s + 1), Material.BARREL);
        b.set(x(-1, s + 1), QUAY + 1, z(-1, s + 1), Material.HAY_BLOCK);
        b.set(x(0, s), QUAY + 2, z(0, s), Material.BARREL);
    }

    /** A little fishing hut and a drying rack on the head of an outer pier. */
    private void fishingHut(Builder b, int p) {
        int y = Pier.DECK;
        int out = p < 0 ? -1 : 1;                          // the side of the head away from the middle
        int s1 = p + out, s2 = p + out * 3;
        for (int t = 26; t <= 29; t++) {
            for (int s = Math.min(s1, s2); s <= Math.max(s1, s2); s++) {
                boolean edge = t == 26 || t == 29 || s == s1 || s == s2;
                boolean corner = (t == 26 || t == 29) && (s == s1 || s == s2);
                for (int k = 1; k <= 2; k++) {
                    if (corner) b.set(x(t, s), y + k, z(t, s), Material.SPRUCE_LOG);
                    else if (edge && s != s1) b.set(x(t, s), y + k, z(t, s), k == 2 && t != 26 && t != 29 ? "minecraft:glass_pane" : "minecraft:spruce_planks");
                    else if (edge) b.set(x(t, s), y + k, z(t, s), Material.AIR);      // open towards the pier
                }
                b.set(x(t, s), y + 3, z(t, s), "minecraft:spruce_slab[type=bottom]");
            }
        }
        int ms = p + out * 2;
        b.set(x(27, ms), y + 1, z(27, ms), Material.BARREL);
        b.set(x(28, ms), y + 1, z(28, ms), "minecraft:chest[facing=" + facingName(-out * sx(), -out * sz()) + "]");
        b.set(x(27, ms), y + 2, z(27, ms), "minecraft:lantern[hanging=false]");
        // drying rack on the other side of the head
        int rs = p - out * 3;
        for (int t : new int[]{26, 29}) for (int k = 1; k <= 3; k++) b.set(x(t, rs), y + k, z(t, rs), Material.SPRUCE_FENCE);
        for (int t = 27; t <= 28; t++) {
            b.set(x(t, rs), y + 3, z(t, rs), Material.SPRUCE_FENCE);
            b.set(x(t, rs), y + 2, z(t, rs), Material.DRIED_KELP_BLOCK);
        }
    }

    private void buildPier(Builder b, int p) {
        // steps down from the quay to the pier deck
        for (int s = p - 2; s <= p + 2; s++) {
            b.set(x(1, s), QUAY - 1, z(1, s), "minecraft:stone_brick_stairs[facing=" + landward + "]");
            b.clear(x(1, s), z(1, s), QUAY, QUAY + 3);
            b.set(x(2, s), QUAY - 2, z(2, s), Material.SPRUCE_PLANKS);
            b.clear(x(2, s), z(2, s), QUAY - 1, QUAY + 3);
        }
        // deck: 5 wide, 9 wide at the end
        for (double t = 3; t <= PIER_LEN; t += 0.5) {
            double w = t >= PIER_LEN - 4 ? 4 : 2;
            for (double s = p - w; s <= p + w; s += 0.5) {
                int x = x(t, s), z = z(t, s);
                if (!b.in(x, z)) continue;
                b.set(x, Pier.DECK, z, Material.SPRUCE_PLANKS);
                b.clear(x, z, Pier.DECK + 1, Pier.DECK + 4);
            }
        }
        for (int t = 3; t <= PIER_LEN; t++) {
            boolean head = t >= PIER_LEN - 4;
            int w = head ? 4 : 2;
            for (int side : new int[]{-1, 1}) {
                int x = x(t, p + side * w), z = z(t, p + side * w);
                // posts stay under the deck
                if (t % 4 == 0 || t == PIER_LEN) {
                    for (int y = b.ground(x, z) + 1; y < Pier.DECK; y++) b.set(x, y, z, Material.SPRUCE_LOG);
                }
                // rail along the edge, with openings to step into the boats
                boolean opening = !head && (t == 13 || t == 14 || t == 15 || t == 22 || t == 23 || t == 24);
                if (!opening && t > 3) b.set(x, Pier.DECK + 1, z, Material.SPRUCE_FENCE);
                if (!opening && t % 8 == 4) {
                    b.set(x, Pier.DECK + 2, z, Material.SPRUCE_FENCE);
                    b.set(x, Pier.DECK + 3, z, Material.LANTERN);
                }
            }
        }
        // the end of the pier is open (only the corners have rails) so ships can come alongside
        for (int s = p - 4; s <= p + 4; s++) {
            if (Math.abs(s - p) >= 3) b.set(x(PIER_LEN, s), Pier.DECK + 1, z(PIER_LEN, s), Material.SPRUCE_FENCE);
        }
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        // the market traders live in the harbour buildings
        int rot = Template.rotationFacing(c.dx(), c.dz());
        Template tp = Template.get("house_big");
        for (int side : new int[]{-17, 17}) {
            int[] o = buildingOrigin(tp, side);
            int[] off = tp.offset(6, 8, rot);
            Villager.Profession prof = side < 0 ? Villager.Profession.FISHERMAN : Villager.Profession.CARTOGRAPHER;
            tasks.add(new StructurePlan.SpawnVillager(o[0] + off[0], QUAY + 1, o[1] + off[1], prof, false, null));
            tasks.add(new StructurePlan.SpawnVillager(o[0] + off[0], QUAY + 1, o[1] + off[1], Villager.Profession.NONE, false, null));
        }
        EntityType[] boats = {EntityType.OAK_BOAT, EntityType.SPRUCE_BOAT, EntityType.OAK_CHEST_BOAT, EntityType.BIRCH_BOAT};
        int i = 0;
        for (int p : PIERS) {
            for (int t : new int[]{14, 23}) {
                int side = (i % 2 == 0) ? 4 : -4;   // just outside the rail opening
                tasks.add(new StructurePlan.SpawnEntity(x(t, p + side), Pier.DECK, z(t, p + side), boats[i % boats.length]));
                i++;
            }
        }
    }
}
