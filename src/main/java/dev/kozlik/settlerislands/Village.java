package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The big village on Meadow: a square with a well and the bell, four long streets,
 * houses with a workstation and a bed each, and crop farms. No iron golems are set up.
 */
public final class Village {

    private static final int ARM = 112;          // street length from the square
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Material[] STATIONS = {
            Material.LECTERN, Material.SMITHING_TABLE, Material.COMPOSTER, Material.LECTERN, Material.BLAST_FURNACE,
            Material.BARREL, Material.GRINDSTONE, Material.BREWING_STAND, Material.LECTERN, Material.LOOM,
            Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE, Material.CAULDRON, Material.STONECUTTER,
            Material.SMOKER, Material.COMPOSTER};
    private static final Material[][] WOODS = {
            {Material.OAK_PLANKS, Material.OAK_LOG, Material.SPRUCE_PLANKS},
            {Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS},
            {Material.BIRCH_PLANKS, Material.BIRCH_LOG, Material.SPRUCE_PLANKS}};

    private record Lot(int x, int z, int fx, int fz, int hw, int hd, boolean farm, Material station, int wood, int floorY, int crop) {
    }

    public final int cx, cz, plazaY;
    private final List<Lot> lots = new ArrayList<>();

    public Village(WorldModel m, int cx, int cz, long seed) {
        this.cx = cx;
        this.cz = cz;
        this.plazaY = Math.max(Pier.DECK, m.height(cx, cz, m.sample(cx, cz)));
        Random rnd = new Random(seed);
        int station = 0;
        for (int[] d : DIRS) {
            for (int t = 18; t <= ARM - 8; t += 14) {
                for (int side : new int[]{-1, 1}) {
                    // side is perpendicular to the street; houses face the street
                    int px = -d[1] * side, pz = d[0] * side;
                    int x = cx + d[0] * t + px * 10, z = cz + d[1] * t + pz * 10;
                    if (!okLand(m, x, z, 6)) continue;
                    boolean farm = rnd.nextDouble() < 0.25;
                    int hw = farm ? 4 : (rnd.nextBoolean() ? 3 : 4);
                    int hd = farm ? 4 : 3;
                    int floorY = Math.max(Pier.DECK, m.height(x, z, m.sample(x, z)));
                    lots.add(new Lot(x, z, -px, -pz, hw, hd, farm, STATIONS[station++ % STATIONS.length],
                            rnd.nextInt(WOODS.length), floorY, rnd.nextInt(3)));
                }
            }
        }
    }

    private static boolean okLand(WorldModel m, int x, int z, int r) {
        for (int[] o : new int[][]{{0, 0}, {r, r}, {-r, r}, {r, -r}, {-r, -r}}) {
            WorldModel.Sample sm = m.sample(x + o[0], z + o[1]);
            if (!sm.land() || sm.zone() != Zone.MEADOW || sm.s() < 10) return false;
        }
        return true;
    }

    public void build(Builder b) {
        if (!b.touches(cx - ARM - 20, cz - ARM - 20, cx + ARM + 20, cz + ARM + 20)) return;
        buildStreets(b);
        buildSquare(b);
        for (Lot lot : lots) {
            if (!b.touches(lot.x - 6, lot.z - 6, lot.x + 6, lot.z + 6) && !touchesPath(b, lot)) continue;
            if (lot.farm) buildFarm(b, lot);
            else buildHouse(b, lot);
        }
    }

    private boolean touchesPath(Builder b, Lot lot) {
        int x2 = lot.x + lot.fx * 10, z2 = lot.z + lot.fz * 10;
        return b.touches(lot.x, lot.z, x2, z2);
    }

    private void buildSquare(Builder b) {
        for (int x = cx - 7; x <= cx + 7; x++) {
            for (int z = cz - 7; z <= cz + 7; z++) {
                if (!b.in(x, z)) continue;
                b.foundation(x, z, plazaY, Material.COBBLESTONE);
                boolean edge = Math.abs(x - cx) == 7 || Math.abs(z - cz) == 7;
                b.set(x, plazaY, z, edge ? Material.COBBLESTONE : Material.STONE_BRICKS);
                b.clear(x, z, plazaY + 1, plazaY + 8);
            }
        }
        // well
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                boolean rim = Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2;
                if (rim) b.set(x, plazaY + 1, z, Material.COBBLESTONE_WALL);
                else {
                    b.set(x, plazaY, z, Material.WATER);
                    b.set(x, plazaY - 1, z, Material.WATER);
                }
            }
        }
        b.set(cx + 5, plazaY + 1, cz, "minecraft:bell[attachment=floor,facing=north]");
        for (int[] o : new int[][]{{6, 6}, {-6, 6}, {6, -6}, {-6, -6}}) lamp(b, cx + o[0], plazaY, cz + o[1]);
    }

    private void lamp(Builder b, int x, int y, int z) {
        b.set(x, y + 1, z, Material.OAK_FENCE);
        b.set(x, y + 2, z, Material.OAK_FENCE);
        b.set(x, y + 3, z, Material.LANTERN);
    }

    private void buildStreets(Builder b) {
        for (int[] d : DIRS) {
            int px = -d[1], pz = d[0];
            for (int t = 8; t <= ARM; t++) {
                for (int side = -1; side <= 1; side++) {
                    int x = cx + d[0] * t + px * side, z = cz + d[1] * t + pz * side;
                    if (!b.in(x, z)) continue;
                    WorldModel.Sample sm = b.model.sample(x, z);
                    int g = b.ground(x, z);
                    if (!sm.land() || g < Pier.DECK) {
                        b.set(x, Pier.DECK, z, Material.OAK_PLANKS);   // little bridge over water
                        b.clear(x, z, Pier.DECK + 1, Pier.DECK + 3);
                    } else {
                        b.set(x, g, z, Material.DIRT_PATH);
                        b.clear(x, z, g + 1, g + 4);
                    }
                }
                if (t % 16 == 0) {
                    int x = cx + d[0] * t + px * 2, z = cz + d[1] * t + pz * 2;
                    if (b.in(x, z)) lamp(b, x, b.ground(x, z), z);
                }
            }
        }
    }

    private static String facing(int dx, int dz) {
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        if (dz > 0) return "south";
        return "north";
    }

    /** World position of local (i across, j towards the street). */
    private static int lx(Lot lot, int i, int j) {
        return lot.x + lot.fx * j - lot.fz * i;
    }

    private static int lz(Lot lot, int i, int j) {
        return lot.z + lot.fz * j + lot.fx * i;
    }

    private void buildHouse(Builder b, Lot lot) {
        Material planks = WOODS[lot.wood][0], log = WOODS[lot.wood][1], roof = WOODS[lot.wood][2];
        int y = lot.floorY;
        int hw = lot.hw, hd = lot.hd;
        // walls, floor, interior
        for (int i = -hw - 1; i <= hw + 1; i++) {
            for (int j = -hd - 1; j <= hd + 1; j++) {
                int x = lx(lot, i, j), z = lz(lot, i, j);
                if (!b.in(x, z)) continue;
                b.clear(x, z, y + 1, y + 16);
                if (Math.abs(i) > hw || Math.abs(j) > hd) continue;   // overhang column: roof only
                b.foundation(x, z, y, Material.COBBLESTONE);
                boolean wallI = Math.abs(i) == hw, wallJ = Math.abs(j) == hd;
                b.set(x, y, z, wallI || wallJ ? Material.COBBLESTONE : planks);
                if (wallI || wallJ) {
                    boolean corner = wallI && wallJ;
                    for (int k = 1; k <= 4; k++) {
                        Material mat = corner ? log : planks;
                        boolean window = !corner && k == 2 && ((wallJ && Math.abs(i) == hw - 1) || (wallI && Math.abs(j) == 1));
                        b.set(x, y + k, z, window ? Material.GLASS_PANE : mat);
                    }
                }
            }
        }
        // stepped roof with a one block overhang
        for (int k = 0; ; k++) {
            int rw = hw + 1 - k, rd = hd + 1 - k;
            if (rw < 0 || rd < 0) break;
            for (int i = -rw; i <= rw; i++)
                for (int j = -rd; j <= rd; j++)
                    b.set(lx(lot, i, j), y + 5 + k, lz(lot, i, j), roof);
        }
        // door facing the street, with a path to it
        String f = facing(lot.fx, lot.fz);
        b.set(lx(lot, 0, hd), y + 1, lz(lot, 0, hd), "minecraft:oak_door[half=lower,facing=" + f + "]");
        b.set(lx(lot, 0, hd), y + 2, lz(lot, 0, hd), "minecraft:oak_door[half=upper,facing=" + f + "]");
        for (int j = hd + 1; j <= 8; j++) {
            int x = lx(lot, 0, j), z = lz(lot, 0, j);
            if (!b.in(x, z)) continue;
            int g = b.ground(x, z);
            if (g >= Pier.DECK) b.set(x, g, z, Material.DIRT_PATH);
        }
        // bed, workstation, light
        String back = facing(-lot.fx, -lot.fz);
        b.set(lx(lot, hw - 1, -hd + 2), y + 1, lz(lot, hw - 1, -hd + 2), "minecraft:red_bed[part=foot,facing=" + back + "]");
        b.set(lx(lot, hw - 1, -hd + 1), y + 1, lz(lot, hw - 1, -hd + 1), "minecraft:red_bed[part=head,facing=" + back + "]");
        b.set(lx(lot, -hw + 1, -hd + 1), y + 1, lz(lot, -hw + 1, -hd + 1), lot.station);
        b.set(lot.x, y + 4, lot.z, "minecraft:lantern[hanging=true]");
    }

    private void buildFarm(Builder b, Lot lot) {
        int y = lot.floorY;
        String crop = switch (lot.crop) {
            case 0 -> "minecraft:wheat[age=7]";
            case 1 -> "minecraft:carrots[age=7]";
            default -> "minecraft:potatoes[age=7]";
        };
        for (int i = -4; i <= 4; i++) {
            for (int j = -4; j <= 4; j++) {
                int x = lx(lot, i, j), z = lz(lot, i, j);
                if (!b.in(x, z)) continue;
                b.foundation(x, z, y, Material.DIRT);
                b.clear(x, z, y + 1, y + 8);
                boolean border = Math.abs(i) == 4 || Math.abs(j) == 4;
                if (border) b.set(x, y, z, Material.OAK_LOG);
                else if (i == 0) b.set(x, y, z, Material.WATER);
                else {
                    b.set(x, y, z, "minecraft:farmland[moisture=7]");
                    b.set(x, y + 1, z, crop);
                }
            }
        }
        b.set(lx(lot, 4, 4), y + 1, lz(lot, 4, 4), Material.COMPOSTER);
    }

    private static Villager.Profession professionFor(Material station) {
        return switch (station) {
            case LECTERN -> Villager.Profession.LIBRARIAN;
            case SMITHING_TABLE -> Villager.Profession.TOOLSMITH;
            case BLAST_FURNACE -> Villager.Profession.ARMORER;
            case GRINDSTONE -> Villager.Profession.WEAPONSMITH;
            case COMPOSTER -> Villager.Profession.FARMER;
            case BARREL -> Villager.Profession.FISHERMAN;
            case BREWING_STAND -> Villager.Profession.CLERIC;
            case CARTOGRAPHY_TABLE -> Villager.Profession.CARTOGRAPHER;
            case FLETCHING_TABLE -> Villager.Profession.FLETCHER;
            case LOOM -> Villager.Profession.SHEPHERD;
            case CAULDRON -> Villager.Profession.LEATHERWORKER;
            case STONECUTTER -> Villager.Profession.MASON;
            case SMOKER -> Villager.Profession.BUTCHER;
            default -> Villager.Profession.NONE;
        };
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        for (Lot lot : lots) {
            if (lot.farm) continue;
            tasks.add(new StructurePlan.SpawnVillager(lot.x, lot.floorY + 1, lot.z, professionFor(lot.station), false, lot.station));
        }
        tasks.add(new StructurePlan.SpawnVillager(cx - 4, plazaY + 1, cz + 4, Villager.Profession.NONE, false, null));
        tasks.add(new StructurePlan.SpawnVillager(cx + 4, plazaY + 1, cz - 4, Villager.Profession.NONE, false, null));
    }
}
