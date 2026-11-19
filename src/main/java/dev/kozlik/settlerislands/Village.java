package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The big village on Meadow. A paved square with a garden, a well and the bell, four paved
 * streets (the east one runs on to the harbour), timber-framed houses with front gardens,
 * and crop farms. Every house has beds and a workstation, so the game treats it as a real
 * village (iron golems are blocked separately).
 */
public final class Village {

    private static final int ARM = 120;            // how far houses go along each street
    private static final int HALF = 2;             // street half width (5 wide)
    private static final int SETBACK = 6;          // street centre to the house front
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final String[] STATIONS = {
            "lectern", "smithing_table", "composter", "lectern", "blast_furnace", "barrel", "grindstone",
            "brewing_stand", "lectern", "loom", "cartography_table", "fletching_table", "cauldron", "stonecutter",
            "smoker", "composter"};
    private static final String[][] STYLES = {
            // roof, log, stripped log, planks, wall
            {"spruce", "dark_oak_log", "stripped_dark_oak_log", "spruce_planks", "white_terracotta"},
            {"dark_oak", "spruce_log", "stripped_spruce_log", "oak_planks", "white_terracotta"},
            {"deepslate_tile", "dark_oak_log", "stripped_dark_oak_log", "spruce_planks", "calcite"},
            {"brick", "spruce_log", "stripped_spruce_log", "birch_planks", "white_terracotta"},
            {"spruce", "oak_log", "stripped_oak_log", "oak_planks", "calcite"}};
    private static final String[] FLOWERS = {"poppy", "dandelion", "cornflower", "allium", "azure_bluet", "oxeye_daisy", "red_tulip", "orange_tulip"};

    private record Lot(boolean farm, boolean big, int ox, int oz, int rot, int floorY, int fx, int fz,
                       int frontX, int frontZ, int px, int pz, Map<String, String> vars, String station) {
    }

    public final int cx, cz, plazaY;
    private final int eastLength;
    private final List<Lot> lots = new ArrayList<>();

    public Village(WorldModel m, int cx, int cz, long seed, int eastLength) {
        this.cx = cx;
        this.cz = cz;
        this.eastLength = Math.max(ARM, eastLength);
        this.plazaY = Math.max(Pier.DECK + 1, m.height(cx, cz, m.sample(cx, cz)));
        Random rnd = new Random(seed);
        int st = 0;
        for (int[] d : DIRS) {
            for (int t = 20; t <= ARM - 4; t += 18) {
                for (int side : new int[]{-1, 1}) {
                    int px = -d[1] * side, pz = d[0] * side;          // away from the street
                    int sx = cx + d[0] * t, sz = cz + d[1] * t;       // street centre
                    int frontX = sx + px * SETBACK, frontZ = sz + pz * SETBACK;
                    boolean farm = rnd.nextDouble() < 0.2;
                    boolean big = !farm && rnd.nextDouble() < 0.4;
                    String[] style = STYLES[rnd.nextInt(STYLES.length)];
                    Map<String, String> vars = Map.of("roof", style[0], "log", style[1], "slog", style[2],
                            "plank", style[3], "wall", style[4], "station", STATIONS[st++ % STATIONS.length]);
                    int rot = Template.rotationFacing(-px, -pz);
                    int ox, oz;
                    if (farm) {
                        ox = frontX + px * 5 - 4;
                        oz = frontZ + pz * 5 - 4;
                    } else {
                        Template tp = Template.get(big ? "house_big" : "house_small");
                        int porchZ = big ? 10 : 8;
                        int[] off = tp.offset(tp.w / 2, porchZ, rot);
                        ox = frontX - off[0];
                        oz = frontZ - off[1];
                    }
                    int sizeX = farm ? 9 : Template.get(big ? "house_big" : "house_small").sizeX(rot);
                    int sizeZ = farm ? 9 : Template.get(big ? "house_big" : "house_small").sizeZ(rot);
                    if (!okLand(m, ox, oz, ox + sizeX - 1, oz + sizeZ - 1)) continue;
                    int mx = ox + sizeX / 2, mz = oz + sizeZ / 2;
                    int floorY = Math.max(Pier.DECK + 1, m.height(mx, mz, m.sample(mx, mz)));
                    lots.add(new Lot(farm, big, ox, oz, rot, floorY, -px, -pz, frontX, frontZ, px, pz, vars, vars.get("station")));
                }
            }
        }
    }

    private static boolean okLand(WorldModel m, int x1, int z1, int x2, int z2) {
        for (int[] p : new int[][]{{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}, {(x1 + x2) / 2, (z1 + z2) / 2}}) {
            WorldModel.Sample sm = m.sample(p[0], p[1]);
            if (!sm.land() || sm.zone() != Zone.MEADOW || sm.s() < 10) return false;
        }
        return true;
    }

    public void build(Builder b) {
        int r = Math.max(ARM, eastLength) + 30;
        if (!b.touches(cx - r, cz - r, cx + r, cz + r)) return;
        buildStreets(b);
        buildSquare(b);
        for (Lot lot : lots) {
            if (!b.touches(lot.ox - 8, lot.oz - 8, lot.ox + 20, lot.oz + 20)) continue;
            if (lot.farm) buildFarm(b, lot);
            else buildHouse(b, lot);
        }
    }

    // ------------------------------------------------------------------ square

    private static Material paving(int x, int z) {
        int h = Builder.hash(x, 7, z);
        if (h < 420) return Material.STONE_BRICKS;
        if (h < 600) return Material.POLISHED_ANDESITE;
        if (h < 760) return Material.COBBLESTONE;
        if (h < 880) return Material.ANDESITE;
        if (h < 950) return Material.CRACKED_STONE_BRICKS;
        return Material.MOSSY_STONE_BRICKS;
    }

    private void buildSquare(Builder b) {
        int y = plazaY;
        for (int x = cx - 11; x <= cx + 11; x++) {
            for (int z = cz - 11; z <= cz + 11; z++) {
                if (!b.in(x, z)) continue;
                b.foundation(x, z, y, Material.COBBLESTONE);
                b.clear(x, z, y + 1, y + 10);
                int ring = Math.max(Math.abs(x - cx), Math.abs(z - cz));
                if (ring == 4) b.set(x, y, z, Material.POLISHED_ANDESITE);
                else if (ring == 3) {
                    b.set(x, y, z, Material.GRASS_BLOCK);
                    int h = Builder.hash(x, 3, z);
                    if (h < 700) b.set(x, y + 1, z, "minecraft:" + FLOWERS[h % FLOWERS.length]);
                } else if (ring == 2) b.set(x, y, z, Material.STONE_BRICKS);
                else if (ring <= 1) {
                    b.set(x, y, z, Material.WATER);
                    b.set(x, y - 1, z, Material.WATER);
                    b.set(x, y - 2, z, Material.STONE_BRICKS);
                } else b.set(x, y, z, paving(x, z));
            }
        }
        // well roof with the bell hanging under it
        for (int[] o : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int k = 1; k <= 3; k++) b.set(cx + o[0], y + k, cz + o[1], Material.SPRUCE_FENCE);
        }
        for (int x = cx - 2; x <= cx + 2; x++)
            for (int z = cz - 2; z <= cz + 2; z++) b.set(x, y + 4, z, "minecraft:spruce_slab[type=bottom]");
        b.set(cx, y + 3, cz, "minecraft:bell[attachment=ceiling,facing=north]");
        // lamp posts and banners where the streets meet the square
        for (int[] o : new int[][]{{9, 9}, {-9, 9}, {9, -9}, {-9, -9}}) lamp(b, cx + o[0], y, cz + o[1]);
        int[] rot = {12, 4, 0, 8};
        for (int i = 0; i < 4; i++) {
            int[] d = DIRS[i];
            for (int side : new int[]{-1, 1}) {
                int x = cx + d[0] * 11 - d[1] * side * 4, z = cz + d[1] * 11 + d[0] * side * 4;
                b.set(x, y + 1, z, "minecraft:red_banner[rotation=" + rot[i] + "]");
            }
        }
    }

    private void lamp(Builder b, int x, int y, int z) {
        b.set(x, y + 1, z, Material.SPRUCE_FENCE);
        b.set(x, y + 2, z, Material.SPRUCE_FENCE);
        b.set(x, y + 3, z, Material.SPRUCE_FENCE);
        b.set(x, y + 4, z, Material.LANTERN);
    }

    // ----------------------------------------------------------------- streets

    private void buildStreets(Builder b) {
        for (int[] d : DIRS) {
            int len = d[0] == 1 ? eastLength : ARM + 6;
            int px = -d[1], pz = d[0];
            for (int t = 11; t <= len; t++) {
                for (int side = -HALF; side <= HALF; side++) {
                    int x = cx + d[0] * t + px * side, z = cz + d[1] * t + pz * side;
                    if (!b.in(x, z)) continue;
                    WorldModel.Sample sm = b.model.sample(x, z);
                    int g = b.ground(x, z);
                    if (!sm.land() || g < Pier.DECK) {
                        b.set(x, Pier.DECK, z, Material.SPRUCE_PLANKS);
                        b.clear(x, z, Pier.DECK + 1, Pier.DECK + 4);
                    } else {
                        b.set(x, g, z, paving(x, z));
                        b.clear(x, z, g + 1, g + 5);
                    }
                }
                if (t % 16 == 0) {
                    int side = (t / 16) % 2 == 0 ? HALF + 1 : -HALF - 1;
                    int x = cx + d[0] * t + px * side, z = cz + d[1] * t + pz * side;
                    if (b.in(x, z)) lamp(b, x, b.ground(x, z), z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ houses

    private void buildHouse(Builder b, Lot lot) {
        Template tp = Template.get(lot.big ? "house_big" : "house_small");
        int sx = tp.sizeX(lot.rot), sz = tp.sizeZ(lot.rot);
        b.level(lot.ox, lot.oz, lot.ox + sx - 1, lot.oz + sz - 1, lot.floorY, Material.GRASS_BLOCK, 20);
        tp.place(b, lot.ox, lot.floorY, lot.oz, lot.rot, lot.vars, true);
        // front garden between the house and the street, path to the door
        for (int along = -5; along <= 5; along++) {
            for (int k = HALF + 1; k < SETBACK; k++) {
                int x = lot.frontX - lot.px * (SETBACK - k) - lot.pz * along;
                int z = lot.frontZ - lot.pz * (SETBACK - k) + lot.px * along;
                if (!b.in(x, z)) continue;
                int g = b.ground(x, z);
                if (g < Pier.DECK) continue;
                if (along == 0) {
                    b.set(x, g, z, Material.DIRT_PATH);
                    b.clear(x, z, g + 1, g + 3);
                    continue;
                }
                b.set(x, g, z, Material.GRASS_BLOCK);
                b.clear(x, z, g + 1, g + 3);
                int h = Builder.hash(x, 5, z);
                if (h < 180) b.set(x, g + 1, z, "minecraft:oak_leaves[persistent=true]");
                else if (h < 520) b.set(x, g + 1, z, "minecraft:" + FLOWERS[h % FLOWERS.length]);
            }
        }
    }

    private void buildFarm(Builder b, Lot lot) {
        int y = lot.floorY;
        String crop = switch (Math.floorMod(lot.ox + lot.oz, 3)) {
            case 0 -> "minecraft:wheat[age=7]";
            case 1 -> "minecraft:carrots[age=7]";
            default -> "minecraft:potatoes[age=7]";
        };
        boolean waterAlongX = lot.fx != 0;
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                int x = lot.ox + i, z = lot.oz + j;
                if (!b.in(x, z)) continue;
                b.foundation(x, z, y, Material.DIRT);
                b.clear(x, z, y + 1, y + 10);
                boolean border = i == 0 || j == 0 || i == 8 || j == 8;
                boolean water = waterAlongX ? j == 4 : i == 4;
                if (border) b.set(x, y, z, Material.OAK_LOG);
                else if (water) b.set(x, y, z, Material.WATER);
                else {
                    b.set(x, y, z, "minecraft:farmland[moisture=7]");
                    b.set(x, y + 1, z, crop);
                }
            }
        }
        b.set(lot.ox, y + 1, lot.oz, Material.COMPOSTER);
        b.set(lot.ox + 8, y + 1, lot.oz + 8, Material.HAY_BLOCK);
    }

    private static Villager.Profession professionFor(String station) {
        return switch (station) {
            case "lectern" -> Villager.Profession.LIBRARIAN;
            case "smithing_table" -> Villager.Profession.TOOLSMITH;
            case "blast_furnace" -> Villager.Profession.ARMORER;
            case "grindstone" -> Villager.Profession.WEAPONSMITH;
            case "composter" -> Villager.Profession.FARMER;
            case "barrel" -> Villager.Profession.FISHERMAN;
            case "brewing_stand" -> Villager.Profession.CLERIC;
            case "cartography_table" -> Villager.Profession.CARTOGRAPHER;
            case "fletching_table" -> Villager.Profession.FLETCHER;
            case "loom" -> Villager.Profession.SHEPHERD;
            case "cauldron" -> Villager.Profession.LEATHERWORKER;
            case "stonecutter" -> Villager.Profession.MASON;
            case "smoker" -> Villager.Profession.BUTCHER;
            default -> Villager.Profession.NONE;
        };
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        for (Lot lot : lots) {
            if (lot.farm) continue;
            Template tp = Template.get(lot.big ? "house_big" : "house_small");
            int[] off = tp.offset(tp.w / 2, tp.d / 2 - 1, lot.rot);
            int x = lot.ox + off[0], z = lot.oz + off[1];
            tasks.add(new StructurePlan.SpawnVillager(x, lot.floorY + 1, z, professionFor(lot.station), false));
            if (lot.big) tasks.add(new StructurePlan.SpawnVillager(x, lot.floorY + 1, z, Villager.Profession.NONE, false));
        }
        tasks.add(new StructurePlan.SpawnVillager(cx - 6, plazaY + 1, cz + 6, Villager.Profession.NONE, false));
        tasks.add(new StructurePlan.SpawnVillager(cx + 6, plazaY + 1, cz - 6, Villager.Profession.NONE, false));
    }
}
