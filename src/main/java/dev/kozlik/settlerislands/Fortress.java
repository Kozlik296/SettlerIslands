package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * The huge Nether fortress (about 230 x 200 blocks): a raised ring of covered corridors on pillars,
 * four corner towers with blaze spawners, a tall central keep reached by open bridges,
 * four nether wart gardens and four staircases down to the ground.
 */
public final class Fortress {

    public static final int DECK = 100;
    private static final int HW = 100, HD = 85;          // ring half size
    private static final int KEEP = 22, KEEP_H = 32;
    private static final int TOWER = 7, TOWER_H = 22;
    private static final Material BRICK = Material.NETHER_BRICKS;
    private static final Material FENCE = Material.NETHER_BRICK_FENCE;
    private static final String LOOT = "minecraft:chests/nether_bridge";

    public final int fx, fz;

    public Fortress(int fx, int fz) {
        this.fx = fx;
        this.fz = fz;
    }

    public void build(Builder b) {
        if (!b.touches(fx - HW - 50, fz - HD - 50, fx + HW + 50, fz + HD + 50)) return;
        // ring corridors
        corridorX(b, fx - HW, fx + HW, fz - HD);
        corridorX(b, fx - HW, fx + HW, fz + HD);
        corridorZ(b, fz - HD, fz + HD, fx - HW);
        corridorZ(b, fz - HD, fz + HD, fx + HW);
        // open bridges from the ring to the keep
        bridgeZ(b, fz - HD + 4, fz - KEEP - 1, fx);
        bridgeZ(b, fz + KEEP + 1, fz + HD - 4, fx);
        bridgeX(b, fx - HW + 4, fx - KEEP - 1, fz);
        bridgeX(b, fx + KEEP + 1, fx + HW - 4, fz);
        hole(b, fx - 1, fz - HD + 3, fx + 1, fz - HD + 3);
        hole(b, fx - 1, fz + HD - 3, fx + 1, fz + HD - 3);
        hole(b, fx - HW + 3, fz - 1, fx - HW + 3, fz + 1);
        hole(b, fx + HW - 3, fz - 1, fx + HW - 3, fz + 1);
        keep(b);
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) tower(b, fx + sx * HW, fz + sz * HD);
        garden(b, fx, fz - HD - 10, 6, 6);
        garden(b, fx, fz + HD + 10, 6, 6);
        garden(b, fx - HW - 10, fz, 6, 6);
        garden(b, fx + HW + 10, fz, 6, 6);
        hole(b, fx - 1, fz - HD - 3, fx + 1, fz - HD - 4);
        hole(b, fx - 1, fz + HD + 3, fx + 1, fz + HD + 4);
        hole(b, fx - HW - 3, fz - 1, fx - HW - 4, fz + 1);
        hole(b, fx + HW + 3, fz - 1, fx + HW + 4, fz + 1);
        stairs(b, fx - 50, fz + HD + 4, 0, 1, "north");
        stairs(b, fx + 50, fz - HD - 4, 0, -1, "south");
        stairs(b, fx + HW + 4, fz + 40, 1, 0, "west");
        stairs(b, fx - HW - 4, fz - 40, -1, 0, "east");
        hole(b, fx - 51, fz + HD + 3, fx - 49, fz + HD + 3);
        hole(b, fx + 49, fz - HD - 3, fx + 51, fz - HD - 3);
        hole(b, fx + HW + 3, fz + 39, fx + HW + 3, fz + 41);
        hole(b, fx - HW - 3, fz - 41, fx - HW - 3, fz - 39);
    }

    private void corridorX(Builder b, int xa, int xb, int zc) {
        if (!b.touches(xa - 2, zc - 4, xb + 2, zc + 4)) return;
        b.fill(xa, DECK, zc - 3, xb, DECK, zc + 3, BRICK);
        b.fill(xa, DECK + 1, zc - 3, xb, DECK + 5, zc - 3, BRICK);
        b.fill(xa, DECK + 1, zc + 3, xb, DECK + 5, zc + 3, BRICK);
        b.fill(xa, DECK + 1, zc - 2, xb, DECK + 5, zc + 2, Material.AIR);
        b.fill(xa, DECK + 6, zc - 3, xb, DECK + 6, zc + 3, BRICK);
        for (int x = xa; x <= xb; x++) {
            if ((x - xa) % 5 == 2) {
                b.fill(x, DECK + 2, zc - 3, x, DECK + 3, zc - 3, FENCE);
                b.fill(x, DECK + 2, zc + 3, x, DECK + 3, zc + 3, FENCE);
            }
            if ((x - xa) % 12 == 0) b.pillar(x - 1, zc - 1, x + 1, zc + 1, DECK, BRICK);
        }
    }

    private void corridorZ(Builder b, int za, int zb, int xc) {
        if (!b.touches(xc - 4, za - 2, xc + 4, zb + 2)) return;
        b.fill(xc - 3, DECK, za, xc + 3, DECK, zb, BRICK);
        b.fill(xc - 3, DECK + 1, za, xc - 3, DECK + 5, zb, BRICK);
        b.fill(xc + 3, DECK + 1, za, xc + 3, DECK + 5, zb, BRICK);
        b.fill(xc - 2, DECK + 1, za, xc + 2, DECK + 5, zb, Material.AIR);
        b.fill(xc - 3, DECK + 6, za, xc + 3, DECK + 6, zb, BRICK);
        for (int z = za; z <= zb; z++) {
            if ((z - za) % 5 == 2) {
                b.fill(xc - 3, DECK + 2, z, xc - 3, DECK + 3, z, FENCE);
                b.fill(xc + 3, DECK + 2, z, xc + 3, DECK + 3, z, FENCE);
            }
            if ((z - za) % 12 == 0) b.pillar(xc - 1, z - 1, xc + 1, z + 1, DECK, BRICK);
        }
    }

    private void bridgeX(Builder b, int xa, int xb, int zc) {
        if (!b.touches(xa, zc - 3, xb, zc + 3)) return;
        b.fill(xa, DECK, zc - 2, xb, DECK, zc + 2, BRICK);
        b.fill(xa, DECK + 1, zc - 2, xb, DECK + 4, zc + 2, Material.AIR);
        b.fill(xa, DECK + 1, zc - 2, xb, DECK + 1, zc - 2, FENCE);
        b.fill(xa, DECK + 1, zc + 2, xb, DECK + 1, zc + 2, FENCE);
        for (int x = xa; x <= xb; x += 16) b.pillar(x - 1, zc - 1, x + 1, zc + 1, DECK, BRICK);
    }

    private void bridgeZ(Builder b, int za, int zb, int xc) {
        if (!b.touches(xc - 3, za, xc + 3, zb)) return;
        b.fill(xc - 2, DECK, za, xc + 2, DECK, zb, BRICK);
        b.fill(xc - 2, DECK + 1, za, xc + 2, DECK + 4, zb, Material.AIR);
        b.fill(xc - 2, DECK + 1, za, xc - 2, DECK + 1, zb, FENCE);
        b.fill(xc + 2, DECK + 1, za, xc + 2, DECK + 1, zb, FENCE);
        for (int z = za; z <= zb; z += 16) b.pillar(xc - 1, z - 1, xc + 1, z + 1, DECK, BRICK);
    }

    /** A 3 high doorway through whatever wall is in the box. */
    private void hole(Builder b, int x1, int z1, int x2, int z2) {
        b.fill(x1, DECK + 1, z1, x2, DECK + 3, z2, Material.AIR);
    }

    private void keep(Builder b) {
        int x1 = fx - KEEP, x2 = fx + KEEP, z1 = fz - KEEP, z2 = fz + KEEP;
        if (!b.touches(x1, z1, x2, z2)) return;
        b.pillar(x1, z1, x2, z2, DECK, BRICK);
        b.fill(x1, DECK, z1, x2, DECK + KEEP_H, z2, BRICK);
        b.fill(x1 + 1, DECK + 1, z1 + 1, x2 - 1, DECK + KEEP_H - 1, z2 - 1, Material.AIR);
        for (int f = 8; f < KEEP_H; f += 8) b.fill(x1 + 1, DECK + f, z1 + 1, x2 - 1, DECK + f, z2 - 1, BRICK);
        // windows on every floor
        for (int f = 0; f < KEEP_H; f += 8) {
            for (int k = -KEEP + 3; k <= KEEP - 3; k += 6) {
                b.fill(fx + k, DECK + f + 3, z1, fx + k, DECK + f + 4, z1, FENCE);
                b.fill(fx + k, DECK + f + 3, z2, fx + k, DECK + f + 4, z2, FENCE);
                b.fill(x1, DECK + f + 3, fz + k, x1, DECK + f + 4, fz + k, FENCE);
                b.fill(x2, DECK + f + 3, fz + k, x2, DECK + f + 4, fz + k, FENCE);
            }
        }
        // crenellations
        for (int x = x1; x <= x2; x += 2) {
            b.set(x, DECK + KEEP_H + 1, z1, BRICK);
            b.set(x, DECK + KEEP_H + 1, z2, BRICK);
        }
        for (int z = z1; z <= z2; z += 2) {
            b.set(x1, DECK + KEEP_H + 1, z, BRICK);
            b.set(x2, DECK + KEEP_H + 1, z, BRICK);
        }
        // doors where the bridges arrive
        b.fill(fx - 1, DECK + 1, z1, fx + 1, DECK + 3, z1, Material.AIR);
        b.fill(fx - 1, DECK + 1, z2, fx + 1, DECK + 3, z2, Material.AIR);
        b.fill(x1, DECK + 1, fz - 1, x1, DECK + 3, fz + 1, Material.AIR);
        b.fill(x2, DECK + 1, fz - 1, x2, DECK + 3, fz + 1, Material.AIR);
        // ladder up through all floors, against the west wall
        for (int y = DECK + 1; y < DECK + KEEP_H; y++) b.set(x1 + 1, y, fz + 6, "minecraft:ladder[facing=east]");
        // spawners on the two top floors, chests on every floor
        for (int f = 16; f < KEEP_H; f += 8) {
            b.set(fx - 8, DECK + f + 1, fz - 8, Material.SPAWNER);
            b.set(fx + 8, DECK + f + 1, fz + 8, Material.SPAWNER);
        }
        for (int f = 0; f < KEEP_H; f += 8) b.set(x2 - 1, DECK + f + 1, z1 + 1, "minecraft:chest[facing=west]");
    }

    private void tower(Builder b, int tx, int tz) {
        int x1 = tx - TOWER, x2 = tx + TOWER, z1 = tz - TOWER, z2 = tz + TOWER;
        if (!b.touches(x1, z1, x2, z2)) return;
        b.pillar(x1, z1, x2, z2, DECK, BRICK);
        b.fill(x1, DECK, z1, x2, DECK + TOWER_H, z2, BRICK);
        b.fill(x1 + 1, DECK + 1, z1 + 1, x2 - 1, DECK + TOWER_H - 1, z2 - 1, Material.AIR);
        b.fill(x1 + 1, DECK + 11, z1 + 1, x2 - 1, DECK + 11, z2 - 1, BRICK);
        // openings where the corridors run through
        b.fill(x1, DECK + 1, tz - 2, x2, DECK + 5, tz + 2, Material.AIR);
        b.fill(tx - 2, DECK + 1, z1, tx + 2, DECK + 5, z2, Material.AIR);
        for (int y = DECK + 1; y < DECK + TOWER_H; y++) b.set(x1 + 1, y, z1 + 1, "minecraft:ladder[facing=east]");
        b.set(x1 + 1, DECK + 11, z1 + 1, "minecraft:ladder[facing=east]");
        b.set(tx + 4, DECK + 1, tz + 4, Material.SPAWNER);
        b.set(tx, DECK + 12, tz, Material.SPAWNER);
        b.set(x2 - 1, DECK + 12, z2 - 1, "minecraft:chest[facing=north]");
    }

    private void garden(Builder b, int gx, int gz, int rx, int rz) {
        int x1 = gx - rx, x2 = gx + rx, z1 = gz - rz, z2 = gz + rz;
        if (!b.touches(x1, z1, x2, z2)) return;
        b.pillar(x1, z1, x2, z2, DECK, BRICK);
        b.fill(x1, DECK, z1, x2, DECK + 6, z2, BRICK);
        b.fill(x1 + 1, DECK + 1, z1 + 1, x2 - 1, DECK + 5, z2 - 1, Material.AIR);
        for (int x = x1 + 1; x <= x2 - 1; x++) {
            for (int z = z1 + 1; z <= z2 - 1; z++) {
                if ((x - x1) % 3 == 0) continue;     // walkways
                b.set(x, DECK, z, Material.SOUL_SAND);
                b.set(x, DECK + 1, z, "minecraft:nether_wart[age=3]");
            }
        }
    }

    /** Stairs from the deck down to the ground, going outward along (dx, dz). */
    private void stairs(Builder b, int sx, int sz, int dx, int dz, String facing) {
        for (int k = 0; k < 80; k++) {
            int y = DECK - k;
            for (int side = -1; side <= 1; side++) {
                int x = sx + dx * k - dz * side, z = sz + dz * k + dx * side;
                if (!b.in(x, z)) continue;
                if (y <= b.ground(x, z)) continue;
                b.foundation(x, z, y, BRICK);
                b.set(x, y, z, "minecraft:nether_brick_stairs[facing=" + facing + "]");
                b.clear(x, z, y + 1, y + 4);
            }
        }
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        for (int f = 16; f < KEEP_H; f += 8) {
            tasks.add(new StructurePlan.SetSpawner(fx - 8, DECK + f + 1, fz - 8, EntityType.BLAZE));
            tasks.add(new StructurePlan.SetSpawner(fx + 8, DECK + f + 1, fz + 8, EntityType.BLAZE));
        }
        for (int f = 0; f < KEEP_H; f += 8) tasks.add(new StructurePlan.SetLoot(fx + KEEP - 1, DECK + f + 1, fz - KEEP + 1, LOOT));
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                int tx = fx + sx * HW, tz = fz + sz * HD;
                tasks.add(new StructurePlan.SetSpawner(tx + 4, DECK + 1, tz + 4, EntityType.BLAZE));
                tasks.add(new StructurePlan.SetSpawner(tx, DECK + 12, tz, EntityType.BLAZE));
                tasks.add(new StructurePlan.SetLoot(tx + TOWER - 1, DECK + 12, tz + TOWER - 1, LOOT));
            }
        }
    }
}
