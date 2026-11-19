package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The big village on Meadow, laid out like a real village: winding roads grow out of a paved
 * square (one of them runs down to the harbour), side lanes branch off them, and houses and
 * farms sit along the roads wherever there is room, each with a little path to its door.
 * Every house has beds and a workstation, so the game treats it as a real village
 * (iron golems are blocked separately).
 */
public final class Village {

    private static final int RADIUS = 175;         // nothing is built further from the square
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

    private record Lot(boolean farm, boolean big, int ox, int oz, int sx, int sz, int rot, int floorY,
                       int doorX, int doorZ, int fx, int fz, Map<String, String> vars, String station) {
    }

    /** One road cell: width class (2 = main road, 1 = lane) and distance from the square. */
    private record Cell(int x, int z, int main, int dist) {
    }

    public final int cx, cz, plazaY;
    private final List<Lot> lots = new ArrayList<>();
    private final Map<Long, Cell> roadCells = new HashMap<>();
    private final Map<Long, List<Cell>> roadByChunk = new HashMap<>();
    private final List<int[]> lamps = new ArrayList<>();
    private final int harbourX, harbourZ;

    public Village(WorldModel m, int cx, int cz, long seed, int harbourX, int harbourZ) {
        this.cx = cx;
        this.cz = cz;
        this.harbourX = harbourX;
        this.harbourZ = harbourZ;
        this.plazaY = Math.max(Pier.DECK + 1, m.height(cx, cz, m.sample(cx, cz)));
        Random rnd = new Random(seed);

        // --- roads: main roads out of the square, lanes branching off them
        List<List<double[]>> mains = new ArrayList<>();
        double toHarbour = Math.atan2(harbourZ - cz, harbourX - cx);
        int count = 5;
        for (int i = 0; i < count; i++) {
            double heading = i == 0 ? toHarbour : toHarbour + i * (Math.PI * 2 / count) + (rnd.nextDouble() - 0.5) * 0.5;
            boolean harbour = i == 0;
            List<double[]> road = grow(m, rnd, cx + Math.cos(heading) * 12, cz + Math.sin(heading) * 12, heading,
                    harbour ? 400 : 95 + rnd.nextInt(55), harbour);
            mains.add(road);
            rasterize(road, 2);
        }
        List<List<double[]>> lanes = new ArrayList<>();
        for (List<double[]> road : mains) {
            for (int k = 30; k < road.size() - 15; k += 28 + rnd.nextInt(18)) {
                if (rnd.nextDouble() < 0.35) continue;
                double[] p = road.get(k);
                double h = p[2] + (rnd.nextBoolean() ? 1 : -1) * Math.toRadians(65 + rnd.nextInt(40));
                List<double[]> lane = grow(m, rnd, p[0], p[1], h, 35 + rnd.nextInt(40), false);
                if (lane.size() < 15) continue;
                lanes.add(lane);
                rasterize(lane, 1);
            }
        }
        for (List<double[]> road : mains) for (int k = 14; k < road.size(); k += 19) addLamp(road.get(k), 3.5, k);
        for (List<double[]> lane : lanes) for (int k = 12; k < lane.size(); k += 19) addLamp(lane.get(k), 2.5, k);

        // --- houses and farms along the roads
        List<int[]> boxes = new ArrayList<>();
        boxes.add(new int[]{cx - 13, cz - 13, cx + 13, cz + 13});
        int st = 0;
        List<List<double[]>> all = new ArrayList<>(mains);
        all.addAll(lanes);
        for (int r = 0; r < all.size(); r++) {
            List<double[]> road = all.get(r);
            int half = r < mains.size() ? 2 : 1;
            for (int k = 8; k < road.size(); k += 13 + rnd.nextInt(8)) {
                for (int side : new int[]{-1, 1}) {
                    if (rnd.nextDouble() < 0.15) continue;
                    double[] p = road.get(k);
                    double nx = -Math.sin(p[2]) * side, nz = Math.cos(p[2]) * side;   // away from the road
                    double gap = half + 3 + rnd.nextInt(3);
                    int frontX = (int) Math.round(p[0] + nx * gap), frontZ = (int) Math.round(p[1] + nz * gap);
                    if (Math.hypot(frontX - cx, frontZ - cz) > RADIUS) continue;
                    if (Math.hypot(frontX - harbourX, frontZ - harbourZ) < 45) continue;
                    boolean farm = rnd.nextDouble() < 0.18;
                    boolean big = !farm && rnd.nextDouble() < 0.4;
                    int rot = Template.rotationFacing(-nx, -nz);
                    int[] f = facingVector(rot);
                    String[] style = STYLES[rnd.nextInt(STYLES.length)];
                    Map<String, String> vars = Map.of("roof", style[0], "log", style[1], "slog", style[2],
                            "plank", style[3], "wall", style[4], "station", STATIONS[st % STATIONS.length]);
                    int ox, oz, sx, sz, doorX, doorZ;
                    if (farm) {
                        sx = sz = 9;
                        ox = frontX - f[0] * 4 - 4;
                        oz = frontZ - 4 - f[1] * 4;
                        doorX = frontX;
                        doorZ = frontZ;
                    } else {
                        Template tp = Template.get(big ? "house_big" : "house_small");
                        int porchZ = big ? 10 : 8;
                        int[] off = tp.offset(tp.w / 2, porchZ, rot);
                        ox = frontX - off[0];
                        oz = frontZ - off[1];
                        sx = tp.sizeX(rot);
                        sz = tp.sizeZ(rot);
                        doorX = frontX;
                        doorZ = frontZ;
                    }
                    int[] box = {ox - 1, oz - 1, ox + sx, oz + sz};
                    if (overlapsRoad(box) || overlaps(boxes, box) || !okLand(m, box)) continue;
                    boxes.add(box);
                    int mx = ox + sx / 2, mz = oz + sz / 2;
                    int floorY = Math.max(Pier.DECK + 1, m.height(mx, mz, m.sample(mx, mz)));
                    lots.add(new Lot(farm, big, ox, oz, sx, sz, rot, floorY, doorX, doorZ, f[0], f[1], vars, vars.get("station")));
                    if (!farm) st++;
                }
            }
        }
    }

    /** Direction the design's front faces for a rotation (see Template.rotationFacing). */
    private static int[] facingVector(int rot) {
        return switch (rot) {
            case 0 -> new int[]{0, 1};
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    /** Grows a winding road: the heading drifts a little every block; the harbour road steers to the harbour. */
    private List<double[]> grow(WorldModel m, Random rnd, double x, double z, double heading, int maxLen, boolean toHarbour) {
        List<double[]> pts = new ArrayList<>();
        double turn = 0;
        for (int i = 0; i < maxLen; i++) {
            if (toHarbour) {
                double want = Math.atan2(harbourZ - z, harbourX - x);
                double diff = Math.atan2(Math.sin(want - heading), Math.cos(want - heading));
                heading += diff * 0.06;
                if (Math.hypot(harbourX - x, harbourZ - z) < 3) break;
            }
            turn = turn * 0.9 + rnd.nextGaussian() * 0.012;
            heading += turn;
            x += Math.cos(heading);
            z += Math.sin(heading);
            WorldModel.Sample sm = m.sample((int) x, (int) z);
            if (!toHarbour && (!sm.land() || sm.zone() != Zone.MEADOW || sm.s() < 12)) break;
            if (!toHarbour && Math.hypot(x - cx, z - cz) > RADIUS) break;
            pts.add(new double[]{x, z, heading});
        }
        return pts;
    }

    private void rasterize(List<double[]> road, int main) {
        double r = main == 2 ? 2.2 : 1.4;
        for (double[] p : road) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    int x = (int) Math.round(p[0]) + dx, z = (int) Math.round(p[1]) + dz;
                    long key = key(x, z);
                    Cell old = roadCells.get(key);
                    if (old != null && old.main >= main) continue;
                    Cell c = new Cell(x, z, main, (int) Math.hypot(x - cx, z - cz));
                    roadCells.put(key, c);
                    roadByChunk.computeIfAbsent(key(x >> 4, z >> 4), k -> new ArrayList<>()).add(c);
                }
            }
        }
    }

    private void addLamp(double[] p, double off, int k) {
        double side = (k / 19) % 2 == 0 ? 1 : -1;
        int x = (int) Math.round(p[0] - Math.sin(p[2]) * off * side), z = (int) Math.round(p[1] + Math.cos(p[2]) * off * side);
        if (!roadCells.containsKey(key(x, z))) lamps.add(new int[]{x, z});
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private boolean overlapsRoad(int[] box) {
        for (int x = box[0]; x <= box[2]; x++)
            for (int z = box[1]; z <= box[3]; z++)
                if (roadCells.containsKey(key(x, z))) return true;
        return false;
    }

    private static boolean overlaps(List<int[]> boxes, int[] b) {
        for (int[] o : boxes) {
            if (b[0] <= o[2] + 2 && b[2] >= o[0] - 2 && b[1] <= o[3] + 2 && b[3] >= o[1] - 2) return true;
        }
        return false;
    }

    private static boolean okLand(WorldModel m, int[] b) {
        for (int[] p : new int[][]{{b[0], b[1]}, {b[2], b[1]}, {b[0], b[3]}, {b[2], b[3]}, {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2}}) {
            WorldModel.Sample sm = m.sample(p[0], p[1]);
            if (!sm.land() || sm.zone() != Zone.MEADOW || sm.s() < 10) return false;
        }
        int h1 = m.height(b[0], b[1], m.sample(b[0], b[1])), h2 = m.height(b[2], b[3], m.sample(b[2], b[3]));
        return Math.abs(h1 - h2) <= 6;
    }

    // ------------------------------------------------------------------ build

    public void build(Builder b) {
        if (!b.touches(cx - RADIUS - 220, cz - RADIUS - 220, cx + RADIUS + 220, cz + RADIUS + 220)) return;
        buildRoads(b);
        buildSquare(b);
        for (int[] l : lamps) if (b.in(l[0], l[1])) lamp(b, l[0], b.ground(l[0], l[1]), l[1]);
        for (Lot lot : lots) {
            if (!b.touches(lot.ox - 14, lot.oz - 14, lot.ox + lot.sx + 14, lot.oz + lot.sz + 14)) continue;
            if (lot.farm) buildFarm(b, lot);
            else buildHouse(b, lot);
        }
    }

    private Material roadBlock(Cell c) {
        int h = Builder.hash(c.x, 7, c.z);
        if (c.dist < 28) {
            return h < 450 ? Material.STONE_BRICKS : h < 650 ? Material.COBBLESTONE : h < 820 ? Material.POLISHED_ANDESITE : Material.GRAVEL;
        }
        if (c.main == 2) {
            return h < 420 ? Material.DIRT_PATH : h < 600 ? Material.GRAVEL : h < 800 ? Material.COBBLESTONE : Material.COARSE_DIRT;
        }
        return h < 600 ? Material.DIRT_PATH : h < 800 ? Material.COARSE_DIRT : Material.GRAVEL;
    }

    private void buildRoads(Builder b) {
        List<Cell> cells = roadByChunk.get(key(b.minX >> 4, b.minZ >> 4));
        if (cells == null) return;
        for (Cell c : cells) {
            WorldModel.Sample sm = b.model.sample(c.x, c.z);
            int g = b.ground(c.x, c.z);
            if (!sm.land() || g < Pier.DECK) {
                b.set(c.x, Pier.DECK, c.z, Material.SPRUCE_PLANKS);
                b.clear(c.x, c.z, Pier.DECK + 1, Pier.DECK + 4);
            } else {
                b.set(c.x, g, c.z, roadBlock(c));
                b.clear(c.x, c.z, g + 1, g + 5);
            }
        }
    }

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
        for (int x = cx - 12; x <= cx + 12; x++) {
            for (int z = cz - 12; z <= cz + 12; z++) {
                if (!b.in(x, z)) continue;
                double d = Math.hypot(x - cx, z - cz);
                if (d > 12.5) continue;                               // round square
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
        for (int[] o : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int k = 1; k <= 3; k++) b.set(cx + o[0], y + k, cz + o[1], Material.SPRUCE_FENCE);
        }
        for (int x = cx - 2; x <= cx + 2; x++)
            for (int z = cz - 2; z <= cz + 2; z++) b.set(x, y + 4, z, "minecraft:spruce_slab[type=bottom]");
        b.set(cx, y + 3, cz, "minecraft:bell[attachment=ceiling,facing=north]");
        for (int[] o : new int[][]{{8, 8}, {-8, 8}, {8, -8}, {-8, -8}}) lamp(b, cx + o[0], y, cz + o[1]);
        for (int[] o : new int[][]{{11, 0, 12}, {-11, 0, 4}, {0, 11, 0}, {0, -11, 8}}) {
            b.set(cx + o[0], y + 1, cz + o[1], "minecraft:red_banner[rotation=" + o[2] + "]");
        }
    }

    private void lamp(Builder b, int x, int y, int z) {
        b.set(x, y + 1, z, Material.SPRUCE_FENCE);
        b.set(x, y + 2, z, Material.SPRUCE_FENCE);
        b.set(x, y + 3, z, Material.SPRUCE_FENCE);
        b.set(x, y + 4, z, Material.LANTERN);
    }

    // ------------------------------------------------------------------ houses

    private void buildHouse(Builder b, Lot lot) {
        Template tp = Template.get(lot.big ? "house_big" : "house_small");
        b.level(lot.ox, lot.oz, lot.ox + lot.sx - 1, lot.oz + lot.sz - 1, lot.floorY, Material.GRASS_BLOCK, 20);
        tp.place(b, lot.ox, lot.floorY, lot.oz, lot.rot, lot.vars, true);
        // path from the door out to the road
        for (int k = 0; k <= 14; k++) {
            int x = lot.doorX + lot.fx * k, z = lot.doorZ + lot.fz * k;
            if (roadCells.containsKey(key(x, z))) break;
            if (!b.in(x, z)) continue;
            int g = b.ground(x, z);
            if (g < Pier.DECK) break;
            b.set(x, g, z, Material.DIRT_PATH);
            b.clear(x, z, g + 1, g + 3);
        }
        // flowers and bushes around the house
        for (int x = lot.ox - 2; x <= lot.ox + lot.sx + 1; x++) {
            for (int z = lot.oz - 2; z <= lot.oz + lot.sz + 1; z++) {
                boolean ring = x < lot.ox || z < lot.oz || x >= lot.ox + lot.sx || z >= lot.oz + lot.sz;
                if (!ring || !b.in(x, z) || roadCells.containsKey(key(x, z))) continue;
                int g = b.ground(x, z);
                if (g < Pier.DECK || b.get(x, g + 1, z) != Material.AIR) continue;
                int h = Builder.hash(x, 5, z);
                if (h < 70) b.set(x, g + 1, z, "minecraft:oak_leaves[persistent=true]");
                else if (h < 260) b.set(x, g + 1, z, "minecraft:" + FLOWERS[h % FLOWERS.length]);
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
            int[] off = tp.offset(tp.w / 2, tp.d / 2, lot.rot);
            int x = lot.ox + off[0], z = lot.oz + off[1];
            tasks.add(new StructurePlan.SpawnVillager(x, lot.floorY + 1, z, professionFor(lot.station), false));
            if (lot.big) tasks.add(new StructurePlan.SpawnVillager(x, lot.floorY + 1, z, Villager.Profession.NONE, false));
        }
        tasks.add(new StructurePlan.SpawnVillager(cx - 6, plazaY + 1, cz + 6, Villager.Profession.NONE, false));
        tasks.add(new StructurePlan.SpawnVillager(cx + 6, plazaY + 1, cz - 6, Villager.Profession.NONE, false));
    }
}
