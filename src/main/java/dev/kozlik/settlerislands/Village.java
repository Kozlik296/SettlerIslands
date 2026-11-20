package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The big village on Meadow, laid out like a real village: winding roads grow out of a paved
 * square with a fountain, a bell frame, benches, market stalls and a big old tree; side lanes
 * branch off the roads, and along them stand houses of several kinds (cottages, big houses, a
 * blacksmith, farmhouses with windmills, a library, a bakery), fenced gardens, crop fields and
 * animal pens. Every house has beds and a workstation, so the game treats it as a real village
 * (iron golems are blocked separately).
 */
public final class Village {

    private static final int RADIUS = 175;         // nothing is built further from the square
    private static final int SQUARE = 17;          // radius of the square
    private static final int CORE_T = 130;         // the back streets reach this far down the avenue
    private static final int BACK_S = 34;          // and run this far to each side of it
    private static final int AVENUE_HOUSES = 14, RING_HOUSES = 12, OUTER_HOUSES = 12;
    private int avenueHouses, ringHouses, outerHouses;

    /** A kind of building: template, door column, front row (porch), inside spawn row, villagers. */
    private record Kind(String template, int doorCol, int front, int inside, int villagers) {
    }

    private static final Map<String, Kind> KINDS = Map.of(
            "house_small", new Kind("house_small", 5, 8, 6, 1),
            "house_big", new Kind("house_big", 6, 10, 8, 2),
            "blacksmith", new Kind("blacksmith", 5, 8, 6, 1),
            "farmhouse", new Kind("farmhouse", 5, 8, 6, 1),
            "library", new Kind("library", 6, 10, 7, 2),
            "bakery", new Kind("bakery", 5, 9, 6, 1));
    /** The order houses are handed out in, so every kind appears several times. */
    private static final String[] DECK = {
            "house_small", "house_big", "farmhouse", "house_small", "bakery", "house_big", "blacksmith",
            "house_small", "library", "house_big", "house_small", "farmhouse", "blacksmith", "house_big"};
    private static final String[] GENERAL_STATIONS = {
            "barrel", "brewing_stand", "loom", "cartography_table", "fletching_table", "cauldron", "stonecutter",
            "composter", "lectern"};
    private static final String[] SMITH_STATIONS = {"smithing_table", "blast_furnace", "grindstone"};
    private static final String[][] STYLES = {
            // roof, log, stripped log, planks, wall, awning wool
            {"spruce", "dark_oak_log", "stripped_dark_oak_log", "spruce_planks", "white_terracotta", "red_wool"},
            {"dark_oak", "spruce_log", "stripped_spruce_log", "oak_planks", "white_terracotta", "green_wool"},
            {"deepslate_tile", "dark_oak_log", "stripped_dark_oak_log", "spruce_planks", "calcite", "blue_wool"},
            {"brick", "spruce_log", "stripped_spruce_log", "birch_planks", "white_terracotta", "orange_wool"},
            {"spruce", "oak_log", "stripped_oak_log", "oak_planks", "calcite", "yellow_wool"}};
    private static final String[] FLOWERS = {"poppy", "dandelion", "cornflower", "allium", "azure_bluet", "oxeye_daisy", "red_tulip", "orange_tulip"};
    private static final String[] WOOLS = {"red_wool", "yellow_wool", "blue_wool", "green_wool", "orange_wool", "purple_wool"};

    /** type: a key of KINDS, or "farm" / "pen". */
    private record Lot(String type, int ox, int oz, int sx, int sz, int rot, int floorY,
                       int doorX, int doorZ, int fx, int fz, Map<String, String> vars, String station, boolean yard,
                       List<Long> path) {
        boolean house() {
            return KINDS.containsKey(type);
        }
    }

    /** One road cell: width class (2 = main road, 1 = lane) and distance from the square. */
    private record Cell(int x, int z, int main, int dist) {
    }

    /** Something standing on the square (stall, bell frame, tree, bench), with its spot and facing. */
    private record Feature(String what, int x, int z, int fx, int fz) {
    }

    public final int cx, cz, plazaY;
    private final List<Lot> lots = new ArrayList<>();
    private final Map<Long, Cell> roadCells = new HashMap<>();
    private final Map<Long, List<Cell>> roadByChunk = new HashMap<>();
    private final List<int[]> lamps = new ArrayList<>();
    private final List<Feature> features = new ArrayList<>();
    private final List<int[]> bushes = new ArrayList<>();
    private final Set<Long> protectedCells = new HashSet<>();
    private int ax = 1, az = 0;
    private final int harbourX, harbourZ;

    // the city hall on the square: players spawn inside, the world map hangs on its back wall
    private static final int HALL_SPAWN_X = 14, HALL_SPAWN_Z = 10, MAP_X = 13, MAP_Y = 3, MAP_Z = 3;
    private int hallRot, hallOx, hallOz, hallFx, hallFz;
    private Template hall;

    public Village(WorldModel m, int cx, int cz, long seed, int harbourX, int harbourZ) {
        this.cx = cx;
        this.cz = cz;
        this.harbourX = harbourX;
        this.harbourZ = harbourZ;
        this.plazaY = Math.max(Pier.DECK + 1, m.height(cx, cz, m.sample(cx, cz)));
        Random rnd = new Random(seed);

        // --- layout frame: t runs from the square towards the harbour, s across
        double toHarbour = Math.atan2(harbourZ - cz, harbourX - cx);
        int ax = 0, az = 0;
        if (Math.abs(Math.cos(toHarbour)) > Math.abs(Math.sin(toHarbour))) ax = (int) Math.signum(Math.cos(toHarbour));
        else az = (int) Math.signum(Math.sin(toHarbour));
        this.ax = ax;
        this.az = az;
        int ux = -ax, uz = -az;                     // the hall stands on the side away from the harbour
        hallFx = ax;
        hallFz = az;
        double hallDir = Math.atan2(uz, ux);

        // --- the harbour avenue: straight and wide from the square to the harbour, so the hall looks down it
        int tEnd = (harbourX - cx) * ax + (harbourZ - cz) * az;
        List<double[]> avenue = new ArrayList<>();
        for (int t = SQUARE - 1; t <= tEnd; t++) avenue.add(new double[]{cx + ax * t, cz + az * t, toHarbour});
        rasterize(avenue, 3);
        for (int t = SQUARE + 4; t <= tEnd - 4; t += 10) {
            for (int sd : new int[]{-1, 1}) lamps.add(new int[]{lx(t, sd * 4), lz(t, sd * 4), 1});
        }
        for (int t = SQUARE + 9; t <= tEnd - 4; t += 10) {
            for (int sd : new int[]{-1, 1}) bushes.add(new int[]{lx(t, sd * 4), lz(t, sd * 4)});
        }

        // --- the streets of the fixed village plan (village_plan.txt, checked for overlaps when it was made)
        List<String[]> plan = new ArrayList<>();
        try (var in = Village.class.getResourceAsStream("/village_plan.txt");
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) plan.add(line.split("\\s+"));
            }
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[SettlerIslands] Could not read village_plan.txt: " + e);
        }
        List<List<double[]>> streets = new ArrayList<>();
        for (String[] p : plan) {
            if (!p[0].equals("path")) continue;
            // a winding lower-tier path through the given points
            List<double[]> st = new ArrayList<>();
            for (int i = 1; i + 3 < p.length; i += 2) {
                st.addAll(line(m, Integer.parseInt(p[i]), Integer.parseInt(p[i + 1]), Integer.parseInt(p[i + 2]), Integer.parseInt(p[i + 3])));
            }
            if (st.isEmpty()) continue;
            streets.add(st);
            rasterize(st, 1);
        }
        List<List<double[]>> mains = new ArrayList<>(), lanes = new ArrayList<>();
        for (List<double[]> st : streets) for (int k = 8; k < st.size(); k += 14) addLamp(st.get(k), 2.5, k);

        // --- city hall, its front on the edge of the square, facing down the avenue
        hall = Template.get("city_hall");
        hallRot = Template.rotationFacing(hallFx, hallFz);
        int[] hoff = hall.offset(hall.w / 2, hall.d - 1, hallRot);
        int hfx = cx + ux * (SQUARE + 2), hfz = cz + uz * (SQUARE + 2);
        hallOx = hfx - hoff[0];
        hallOz = hfz - hoff[1];

        planSquare(hallDir);

        // --- every house and field of the plan, exactly where the plan puts it
        List<int[]> boxes = new ArrayList<>();
        boxes.add(new int[]{cx - SQUARE - 2, cz - SQUARE - 2, cx + SQUARE + 2, cz + SQUARE + 2});
        boxes.add(new int[]{hallOx - 2, hallOz - 2, hallOx + hall.sizeX(hallRot) + 1, hallOz + hall.sizeZ(hallRot) + 1});
        int[] counters = new int[3];   // general station, smith station
        int skipped = 0;
        for (String[] p : plan) {
            if (!p[0].equals("lot")) continue;
            int t = Integer.parseInt(p[2]), sv = Integer.parseInt(p[3]);
            int dt = Integer.parseInt(p[4]), ds = Integer.parseInt(p[5]);
            int fx = ax * dt - az * ds, fz = az * dt + ax * ds;      // facing, in the world
            if (placeLot(m, rnd, boxes, counters, p[1], lx(t, sv), lz(t, sv), fx, fz) == null) skipped++;
        }
        if (skipped > 0) org.bukkit.Bukkit.getLogger().warning("[SettlerIslands] " + skipped + " village lots were not on Meadow land and were left out");
        // paths from every door to the nearest road, walking round other buildings
        Set<Long> blocked = new HashSet<>();
        for (int[] bx : boxes.subList(1, boxes.size())) {
            for (int x = bx[0] + 1; x < bx[2]; x++) for (int z = bx[1] + 1; z < bx[3]; z++) blocked.add(key(x, z));
        }
        for (int i = 0; i < lots.size(); i++) {
            Lot l = lots.get(i);
            if (l.house()) lots.set(i, withPath(l, route(l, blocked)));
        }
        // no street lamps or bushes inside buildings or on a garden path
        Set<Long> onPath = new HashSet<>();
        for (Lot l : lots) onPath.addAll(l.path);
        java.util.function.Predicate<int[]> inside = l -> {
            if (onPath.contains(key(l[0], l[1]))) return true;
            for (int[] bx : boxes.subList(1, boxes.size()))
                if (l[0] > bx[0] && l[0] < bx[2] && l[1] > bx[1] && l[1] < bx[3]) return true;
            return false;
        };
        lamps.removeIf(inside);
        bushes.removeIf(inside);
        // cells a house's ground blending must not touch: other buildings, their paths, the square and hall
        protectedCells.addAll(onPath);
        for (int[] bx : boxes) for (int x = bx[0]; x <= bx[2]; x++) for (int z = bx[1]; z <= bx[3]; z++) protectedCells.add(key(x, z));
        long houses = lots.stream().filter(Lot::house).count();
        org.bukkit.Bukkit.getLogger().info("[SettlerIslands] Village built from the plan: " + houses + " houses, " + (lots.size() - houses) + " fields and pens");
    }

    // ---- layout frame helpers (t towards the harbour, s across)
    private int lx(int t, int sv) {
        return cx + ax * t - az * sv;
    }

    private int lz(int t, int sv) {
        return cz + az * t + ax * sv;
    }

    private double lxd(double t, double sv) {
        return cx + ax * t - az * sv;
    }

    private double lzd(double t, double sv) {
        return cz + az * t + ax * sv;
    }

    private boolean inCore(int x, int z) {
        int t = (x - cx) * ax + (z - cz) * az, sv = -(x - cx) * az + (z - cz) * ax;
        return t >= -SQUARE && t <= CORE_T + 6 && Math.abs(sv) <= BACK_S + 6;
    }

    /** A straight town street from (t0, s0) to (t1, s1), only where the ground is Meadow. */
    private List<double[]> line(WorldModel m, int t0, int s0, int t1, int s1) {
        List<double[]> pts = new ArrayList<>();
        int n = Math.max(Math.abs(t1 - t0), Math.abs(s1 - s0));
        double wx0 = lxd(t0, s0), wz0 = lzd(t0, s0), wx1 = lxd(t1, s1), wz1 = lzd(t1, s1);
        double heading = Math.atan2(wz1 - wz0, wx1 - wx0);
        for (int i = 0; i <= n; i++) {
            double x = wx0 + (wx1 - wx0) * i / n, z = wz0 + (wz1 - wz0) * i / n;
            WorldModel.Sample sm = m.sample((int) Math.round(x), (int) Math.round(z));
            if (!sm.land() || sm.zone() != Zone.MEADOW || sm.s() < 12) continue;
            pts.add(new double[]{x, z, heading});
        }
        return pts;
    }

    /**
     * Up to 'cap' houses on both sides of the given streets, each facing its street. The spots are
     * tried in order along the street (ordered) or in a shuffled order (spread out). Outside the
     * centre some spots become fields or pens instead (not counted).
     */
    private int placeAlong(WorldModel m, Random rnd, List<int[]> boxes, int[] counters, List<List<double[]>> roads,
                           int gap, int cap, boolean shuffled, boolean centre) {
        List<double[]> spots = new ArrayList<>();
        for (List<double[]> road : roads) {
            for (int k = 2; k < road.size() - 2; k++) {
                double[] p = road.get(k);
                for (int side : new int[]{-1, 1}) spots.add(new double[]{p[0], p[1], p[2], side});
            }
        }
        if (shuffled) java.util.Collections.shuffle(spots, rnd);
        int placed = 0, fields = 0;
        for (double[] p : spots) {
            if (placed >= cap) break;
            double nx = -Math.sin(p[2]) * p[3], nz = Math.cos(p[2]) * p[3];
            // face the street square-on
            if (Math.abs(nx) > Math.abs(nz)) { nx = Math.signum(nx); nz = 0; } else { nz = Math.signum(nz); nx = 0; }
            int frontX = (int) Math.round(p[0] + nx * gap), frontZ = (int) Math.round(p[1] + nz * gap);
            boolean field = !centre && fields < 5 && rnd.nextDouble() < 0.25;
            Lot lot = tryLot(m, rnd, boxes, counters, frontX, frontZ, nx, nz, centre, field);
            if (lot == null) continue;
            if (lot.house()) placed++;
            else fields++;
        }
        return placed;
    }

    private static Lot withPath(Lot l, List<Long> path) {
        return new Lot(l.type, l.ox, l.oz, l.sx, l.sz, l.rot, l.floorY, l.doorX, l.doorZ, l.fx, l.fz, l.vars, l.station, l.yard, path);
    }

    /**
     * Puts a lot of the plan in place: its front (porch) at (frontX, frontZ), facing (fx, fz). It is
     * only left out if the spot is not Meadow land (the plan itself has no overlaps).
     */
    private Lot placeLot(WorldModel m, Random rnd, List<int[]> boxes, int[] counters, String type, int frontX, int frontZ, int fx, int fz) {
        int rot = Template.rotationFacing(fx, fz);
        int[] f = facingVector(rot);
        String[] style = STYLES[rnd.nextInt(STYLES.length)];
        String station = switch (type) {
            case "blacksmith" -> SMITH_STATIONS[counters[1]++ % SMITH_STATIONS.length];
            case "farmhouse" -> "composter";
            case "library" -> "lectern";
            case "bakery" -> "smoker";
            case "farm", "pen" -> "composter";
            default -> GENERAL_STATIONS[counters[0]++ % GENERAL_STATIONS.length];
        };
        Map<String, String> vars = Map.of("roof", style[0], "log", style[1], "slog", style[2],
                "plank", style[3], "wall", style[4], "station", station, "wool", style[5]);
        int ox, oz, sx, sz;
        Kind kind = KINDS.get(type);
        if (kind == null) {
            sx = sz = 9;
            ox = frontX - f[0] * 4 - 4;
            oz = frontZ - 4 - f[1] * 4;
        } else {
            Template tp = Template.get(kind.template());
            int[] off = tp.offset(kind.doorCol(), kind.front(), rot);
            ox = frontX - off[0];
            oz = frontZ - off[1];
            sx = tp.sizeX(rot);
            sz = tp.sizeZ(rot);
        }
        int mx = ox + sx / 2, mz = oz + sz / 2;
        WorldModel.Sample centre = m.sample(mx, mz);
        if (!centre.land() || centre.zone() != Zone.MEADOW) return null;
        // floor at the average height of the corners and the middle, so the house sits into the slope
        int sum = 0;
        for (int[] c : new int[][]{{ox, oz}, {ox + sx - 1, oz}, {ox, oz + sz - 1}, {ox + sx - 1, oz + sz - 1}, {mx, mz}, {mx, mz}})
            sum += m.height(c[0], c[1], m.sample(c[0], c[1]));
        int floorY = Math.max(Pier.DECK + 1, Math.round(sum / 6f));
        boolean yard = false;
        boxes.add(new int[]{ox - 1, oz - 1, ox + sx, oz + sz});
        Lot lot = new Lot(type, ox, oz, sx, sz, rot, floorY, frontX, frontZ, f[0], f[1], vars, station, yard, List.of());
        lots.add(lot);
        return lot;
    }

    /** Tries to put a lot with its front at (frontX, frontZ), facing back along (nx, nz). */
    private Lot tryLot(WorldModel m, Random rnd, List<int[]> boxes, int[] counters, int frontX, int frontZ,
                       double nx, double nz, boolean centre, boolean firstRow) {
        if (Math.hypot(frontX - cx, frontZ - cz) > RADIUS) return null;
        if (Math.hypot(frontX - harbourX, frontZ - harbourZ) < 45) return null;
        double roll = rnd.nextDouble();
        String type = firstRow ? (roll < 0.5 ? "farm" : "pen") : DECK[counters[0] % DECK.length];
        int rot = Template.rotationFacing(-nx, -nz);
        int[] f = facingVector(rot);
        String[] style = STYLES[rnd.nextInt(STYLES.length)];
        String station = switch (type) {
            case "blacksmith" -> SMITH_STATIONS[counters[2] % SMITH_STATIONS.length];
            case "farmhouse" -> "composter";
            case "library" -> "lectern";
            case "bakery" -> "smoker";
            default -> GENERAL_STATIONS[counters[1] % GENERAL_STATIONS.length];
        };
        Map<String, String> vars = Map.of("roof", style[0], "log", style[1], "slog", style[2],
                "plank", style[3], "wall", style[4], "station", station, "wool", style[5]);
        int ox, oz, sx, sz;
        Kind kind = KINDS.get(type);
        if (kind == null) {
            sx = sz = 9;
            ox = frontX - f[0] * 4 - 4;
            oz = frontZ - 4 - f[1] * 4;
        } else {
            Template tp = Template.get(kind.template());
            int[] off = tp.offset(kind.doorCol(), kind.front(), rot);
            ox = frontX - off[0];
            oz = frontZ - off[1];
            sx = tp.sizeX(rot);
            sz = tp.sizeZ(rot);
        }
        boolean yard = kind != null && !centre && rnd.nextDouble() < 0.45;
        int pad = yard ? 3 : 1;
        int[] box = {ox - pad, oz - pad, ox + sx + pad - 1, oz + sz + pad - 1};
        int sep = 1;                               // at least 3 blocks between buildings
        if (overlapsRoad(new int[]{ox - 1, oz - 1, ox + sx, oz + sz}) || overlaps(boxes, box, sep) || !okLand(m, box)) return null;
        boxes.add(box);
        int mx = ox + sx / 2, mz = oz + sz / 2;
        int floorY = Math.max(Pier.DECK + 1, m.height(mx, mz, m.sample(mx, mz)));
        Lot lot = new Lot(type, ox, oz, sx, sz, rot, floorY, frontX, frontZ, f[0], f[1], vars, station, yard, List.of());
        lots.add(lot);
        if (kind != null) {
            counters[0]++;
            if (type.equals("blacksmith")) counters[2]++;
            else if (!type.equals("farmhouse") && !type.equals("library") && !type.equals("bakery")) counters[1]++;
        }
        return lot;
    }

    /**
     * A path from the door to the nearest road: straight out, and where another building is in the
     * way, sideways along it to the gap, then on.
     */
    private List<Long> route(Lot l, Set<Long> blocked) {
        List<Long> path = new ArrayList<>();
        int x = l.doorX, z = l.doorZ;
        int lx = -l.fz, lz = l.fx;
        Set<Long> own = new HashSet<>();
        for (int i = l.ox - 3; i < l.ox + l.sx + 3; i++) for (int j = l.oz - 3; j < l.oz + l.sz + 3; j++) own.add(key(i, j));
        int dir = 0;
        for (int step = 0; step < 60; step++) {
            path.add(key(x, z));
            if (roadCells.containsKey(key(x, z))) return path;
            int nx = x + l.fx, nz = z + l.fz;
            boolean free = !blocked.contains(key(nx, nz)) || own.contains(key(nx, nz));
            if (free) {
                x = nx;
                z = nz;
                dir = 0;
                continue;
            }
            if (dir == 0) {
                // pick the side where the obstacle ends sooner
                int left = 99, right = 99;
                for (int d = 1; d < 20; d++) {
                    if (left == 99 && !blocked.contains(key(x + lx * d + l.fx, z + lz * d + l.fz))) left = d;
                    if (right == 99 && !blocked.contains(key(x - lx * d + l.fx, z - lz * d + l.fz))) right = d;
                }
                dir = left <= right ? 1 : -1;
            }
            x += lx * dir;
            z += lz * dir;
        }
        return path;
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

    private static String facingName(int fx, int fz) {
        if (fx > 0) return "east";
        if (fx < 0) return "west";
        return fz > 0 ? "south" : "north";
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
        double r = main == 3 ? 3.2 : main == 2 ? 2.2 : 1.4;
        for (double[] p : road) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    int x = (int) Math.round(p[0]) + dx, z = (int) Math.round(p[1]) + dz;
                    long key = key(x, z);
                    Cell old = roadCells.get(key);
                    if (old != null && old.main >= main) continue;
                    if (old != null) roadByChunk.get(key(x >> 4, z >> 4)).remove(old);
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

    /** The closest road cell within r (square search), or null. */
    private int[] nearestRoad(int x, int z, int r) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int d = dx * dx + dz * dz;
                if (d < bestD && roadCells.containsKey(key(x + dx, z + dz))) {
                    bestD = d;
                    best = new int[]{x + dx, z + dz};
                }
            }
        return best;
    }

    /** True if any road cell lies within r of the point (roads entering the square). */
    private boolean nearRoad(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (roadCells.containsKey(key(x + dx, z + dz))) return true;
        return false;
    }

    private static boolean overlaps(List<int[]> boxes, int[] b, int sep) {
        for (int[] o : boxes) {
            if (b[0] <= o[2] + sep && b[2] >= o[0] - sep && b[1] <= o[3] + sep && b[3] >= o[1] - sep) return true;
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

    // ------------------------------------------------------------------ square plan

    /**
     * Places the things on the square where no road enters: market stalls on the outer ring,
     * a bell frame, a big old tree, and benches facing the fountain.
     */
    private void planSquare(double hallDir) {
        List<String> outer = new ArrayList<>(List.of("tree", "stall", "bell", "stall", "stall", "beds", "stall", "beds"));
        int placed = 0;
        for (int i = 0; i < 16 && !outer.isEmpty(); i++) {
            double a = i * Math.PI / 8 + 0.2;
            if (Math.abs(Math.atan2(Math.sin(a - hallDir), Math.cos(a - hallDir))) < Math.toRadians(40)) continue;
            if (Math.abs(Math.atan2(Math.sin(a - hallDir - Math.PI), Math.cos(a - hallDir - Math.PI))) < Math.toRadians(35)) continue;
            int x = cx + (int) Math.round(Math.cos(a) * 12.5), z = cz + (int) Math.round(Math.sin(a) * 12.5);
            if (nearRoad(x, z, 4)) continue;
            boolean clash = false;
            for (Feature f : features) if (Math.abs(f.x - x) < 6 && Math.abs(f.z - z) < 6) clash = true;
            if (clash) continue;
            int fx = 0, fz = 0;   // facing: towards the centre, snapped to an axis
            if (Math.abs(cx - x) > Math.abs(cz - z)) fx = Integer.signum(cx - x);
            else fz = Integer.signum(cz - z);
            features.add(new Feature(outer.remove(0), x, z, fx, fz));
            placed++;
        }
        // benches around the fountain, back to the outside
        for (int i = 0; i < 4; i++) {
            int fx = i == 0 ? 1 : i == 1 ? -1 : 0, fz = i == 2 ? 1 : i == 3 ? -1 : 0;
            int x = cx - fx * 7, z = cz - fz * 7;
            if (fx == hallFx && fz == hallFz) continue;      // keep the way to the hall open
            if (fx == -hallFx && fz == -hallFz) continue;    // and the view down the avenue
            if (!nearRoad(x, z, 2)) features.add(new Feature("bench", x, z, fx, fz));
        }
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + Math.PI / 8;
            int x = cx + (int) Math.round(Math.cos(a) * 15.5), z = cz + (int) Math.round(Math.sin(a) * 15.5);
            if (Math.abs(Math.atan2(Math.sin(a - hallDir), Math.cos(a - hallDir))) < Math.toRadians(30)) continue;
            if (!nearRoad(x, z, 1)) lamps.add(new int[]{x, z});
        }
    }

    // ------------------------------------------------------------------ build

    public void build(Builder b) {
        if (!b.touches(cx - RADIUS - 220, cz - RADIUS - 220, cx + RADIUS + 220, cz + RADIUS + 220)) return;
        if (b.touches(cx - SQUARE - 4, cz - SQUARE - 4, cx + SQUARE + 4, cz + SQUARE + 4))
            b.clearTrees(cx - SQUARE - 4, cz - SQUARE - 4, cx + SQUARE + 4, cz + SQUARE + 4);
        for (Lot lot : lots) {
            if (b.touches(lot.ox - 6, lot.oz - 6, lot.ox + lot.sx + 6, lot.oz + lot.sz + 6))
                b.clearTrees(lot.ox - 6, lot.oz - 6, lot.ox + lot.sx + 5, lot.oz + lot.sz + 5);
        }
        int hsx = hall.sizeX(hallRot), hsz = hall.sizeZ(hallRot);
        if (b.touches(hallOx - 14, hallOz - 14, hallOx + hsx + 14, hallOz + hsz + 14)) {
            b.clearTrees(hallOx - 6, hallOz - 6, hallOx + hsx + 5, hallOz + hsz + 5);
        }
        buildRoads(b);
        if (b.touches(hallOx - 14, hallOz - 14, hallOx + hsx + 14, hallOz + hsz + 14)) {
            b.terraform(hallOx, hallOz, hallOx + hsx - 1, hallOz + hsz - 1, plazaY, Material.GRASS_BLOCK, 10, 50);
            // paving between the square and the hall steps
            int lx = -hallFz, lz = hallFx;
            for (int t = SQUARE - 3; t <= SQUARE + 3; t++) {
                for (int a = -12; a <= 12; a++) {
                    int x = cx - hallFx * t + lx * a, z = cz - hallFz * t + lz * a;
                    if (!b.in(x, z) || Math.hypot(x - cx, z - cz) <= SQUARE - 0.5) continue;
                    b.foundation(x, z, plazaY, Material.COBBLESTONE);
                    b.set(x, plazaY, z, paving(x, z));
                    b.clear(x, z, plazaY + 1, plazaY + 8);
                }
            }
            hall.place(b, hallOx, plazaY, hallOz, hallRot, Map.of(), true);
        }
        buildSquare(b);
        for (int[] l : lamps) {
            if (!b.in(l[0], l[1])) continue;
            boolean onSquare = Math.hypot(l[0] - cx, l[1] - cz) <= SQUARE + 0.5;
            if (l.length > 2) avenueLamp(b, l[0], b.ground(l[0], l[1]), l[1]);
            else lamp(b, l[0], onSquare ? plazaY : b.ground(l[0], l[1]), l[1]);
        }
        for (int[] bu : bushes) {
            if (!b.in(bu[0], bu[1])) continue;
            int g = b.ground(bu[0], bu[1]);
            b.set(bu[0], g, bu[1], Material.GRASS_BLOCK);
            b.set(bu[0], g + 1, bu[1], Builder.hash(bu[0], 1, bu[1]) < 500 ? Material.FLOWERING_AZALEA : Material.AZALEA);
        }
        for (Lot lot : lots) {
            if (!b.touches(lot.ox - 14, lot.oz - 14, lot.ox + lot.sx + 14, lot.oz + lot.sz + 14)) continue;
            switch (lot.type) {
                case "farm" -> buildFarm(b, lot);
                case "pen" -> buildPen(b, lot);
                default -> buildHouse(b, lot);
            }
        }
    }

    private Material roadBlock(Cell c) {
        int h = Builder.hash(c.x, 7, c.z);
        if (c.main == 3) {
            // the avenue: stone brick kerbs, a polished centre with a chiselled stone every 8 blocks
            int t = (c.x - cx) * ax + (c.z - cz) * az, sv = Math.abs(-(c.x - cx) * az + (c.z - cz) * ax);
            if (sv == 3) return Material.STONE_BRICKS;
            if (sv == 0 && Math.floorMod(t, 8) == 0) return Material.CHISELED_STONE_BRICKS;
            if (sv <= 1) return h < 700 ? Material.POLISHED_ANDESITE : Material.ANDESITE;
            return h < 600 ? Material.STONE_BRICKS : h < 850 ? Material.POLISHED_ANDESITE : Material.CRACKED_STONE_BRICKS;
        }
        if (c.dist < 30) {
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
                b.clearTrees(c.x, c.z, c.x, c.z);
                b.set(c.x, g, c.z, roadBlock(c));
                b.clear(c.x, c.z, g + 1, g + 3);
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
        if (!b.touches(cx - SQUARE - 1, cz - SQUARE - 1, cx + SQUARE + 1, cz + SQUARE + 1)) return;
        int y = plazaY;
        for (int x = cx - SQUARE - 1; x <= cx + SQUARE + 1; x++) {
            for (int z = cz - SQUARE - 1; z <= cz + SQUARE + 1; z++) {
                if (!b.in(x, z)) continue;
                double d = Math.hypot(x - cx, z - cz);
                if (d > SQUARE + 0.5) continue;
                b.foundation(x, z, y, Material.COBBLESTONE);
                b.clear(x, z, y + 1, y + 12);
                if (d > SQUARE - 0.5) b.set(x, y, z, Material.POLISHED_ANDESITE);          // border ring
                else if (d > 8.5 && d <= 9.5) b.set(x, y, z, Material.CHISELED_STONE_BRICKS);
                else if (d > 4.5 && d <= 5.5) b.set(x, y, z, Material.POLISHED_ANDESITE);
                else if (d > 3.5 && d <= 4.5) {
                    // fountain rim
                    b.set(x, y, z, Material.STONE_BRICKS);
                    b.set(x, y + 1, z, Material.STONE_BRICK_WALL);
                } else if (d <= 3.5) {
                    // fountain basin
                    b.set(x, y - 2, z, Material.STONE_BRICKS);
                    b.set(x, y - 1, z, Material.WATER);
                    b.set(x, y, z, Material.WATER);
                    if (d > 1.5 && Builder.hash(x, 4, z) < 160) b.set(x, y + 1, z, Material.LILY_PAD);
                } else b.set(x, y, z, paving(x, z));
            }
        }
        // centre column of the fountain
        if (b.in(cx, cz)) {
            b.set(cx, y - 1, cz, Material.STONE_BRICKS);
            b.set(cx, y, cz, Material.STONE_BRICKS);
            b.set(cx, y + 1, cz, Material.CHISELED_STONE_BRICKS);
            b.set(cx, y + 2, cz, Material.STONE_BRICK_WALL);
            b.set(cx, y + 3, cz, Material.CHISELED_STONE_BRICKS);
            b.set(cx, y + 4, cz, Material.LANTERN);
        }
        for (int[] o : new int[][]{{1, 0, 0}, {-1, 0, 1}, {0, 1, 2}, {0, -1, 3}}) {
            String face = new String[]{"east", "west", "south", "north"}[o[2]];
            b.set(cx + o[0], y + 3, cz + o[1], "minecraft:stone_brick_stairs[facing=" + face + ",half=top]");
        }
        for (Feature f : features) {
            switch (f.what) {
                case "stall" -> stall(b, f.x, y, f.z, f.fx, f.fz, WOOLS[Math.floorMod(f.x * 7 + f.z, WOOLS.length)], Builder.hash(f.x, 2, f.z));
                case "bell" -> bellFrame(b, f.x, y, f.z, f.fx, f.fz);
                case "tree" -> bigTree(b, f.x, y, f.z);
                case "beds" -> flowerBed(b, f.x, y, f.z);
                case "bench" -> bench(b, f.x, y, f.z, f.fx, f.fz);
                default -> {
                }
            }
        }
    }

    /** A market stall: posts, striped wool awning, a counter of goods, facing (fx, fz). */
    static void stall(Builder b, int x, int y, int z, int fx, int fz, String wool, int pick) {
        int lx = -fz, lz = fx;   // lateral axis
        String[][] goods = {
                {"melon", "pumpkin", "hay_block"}, {"barrel[facing=up]", "composter[level=8]", "barrel[facing=up]"},
                {"hay_block", "carved_pumpkin[facing=" + facingName(fx, fz) + "]", "melon"},
                {"barrel[facing=up]", "dried_kelp_block", "barrel[facing=up]"}, {"bee_nest[facing=" + facingName(fx, fz) + "]", "barrel[facing=up]", "hay_block"}};
        String[] row = goods[Math.floorMod(pick, goods.length)];
        for (int a = -1; a <= 1; a++) {
            for (int d = 0; d <= 2; d++) {
                int px = x + lx * a - fx * d, pz = z + lz * a - fz * d;
                if (Math.abs(a) == 1 && (d == 0 || d == 2)) {
                    for (int k = 1; k <= 2; k++) b.set(px, y + k, pz, Material.SPRUCE_FENCE);
                }
                b.set(px, y + 3, pz, "minecraft:" + ((a + d) % 2 == 0 ? wool : "white_wool"));
            }
            // counter in front, a chest behind
            b.set(x + lx * a, y + 1, z + lz * a, "minecraft:" + (a == 0 ? row[1] : Math.abs(a) == 1 ? row[a + 1] : row[0]));
            b.set(x + lx * a + fx, y + 3, z + lz * a + fz, "minecraft:" + (a == 0 ? wool : "white_wool"));
        }
        b.set(x - fx * 2, y + 1, z - fz * 2, "minecraft:chest[facing=" + facingName(fx, fz) + "]");
    }

    private void bellFrame(Builder b, int x, int y, int z, int fx, int fz) {
        int lx = -fz, lz = fx;
        for (int a : new int[]{-1, 1}) {
            for (int k = 1; k <= 4; k++) b.set(x + lx * a, y + k, z + lz * a, Material.DARK_OAK_LOG);
            b.set(x + lx * a, y + 5, z + lz * a, "minecraft:dark_oak_slab[type=bottom]");
        }
        b.set(x, y + 4, z, "minecraft:stripped_dark_oak_log[axis=" + (lx != 0 ? "x" : "z") + "]");
        b.set(x, y + 5, z, "minecraft:dark_oak_slab[type=bottom]");
        b.set(x, y + 3, z, "minecraft:bell[attachment=ceiling,facing=" + facingName(fx, fz) + "]");
        for (int a = -2; a <= 2; a++) b.set(x + lx * a, y, z + lz * a, Material.POLISHED_ANDESITE);
    }

    /** A big old oak on a grass bed with a stone edge. */
    private void bigTree(Builder b, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                b.set(x + dx, y, z + dz, edge ? Material.STONE_BRICKS : Material.GRASS_BLOCK);
                if (edge) b.set(x + dx, y + 1, z + dz, "minecraft:stone_brick_slab[type=bottom]");
                else if (Builder.hash(x + dx, 6, z + dz) < 500) b.set(x + dx, y + 1, z + dz, "minecraft:" + FLOWERS[Builder.hash(x + dx, 8, z + dz) % FLOWERS.length]);
            }
        }
        for (int k = 1; k <= 7; k++) b.set(x, y + k, z, Material.OAK_LOG);
        for (int[] br : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            b.set(x + br[0], y + 6, z + br[1], "minecraft:oak_log[axis=" + (br[0] != 0 ? "x" : "z") + "]");
            b.set(x + br[0] * 2, y + 7, z + br[1] * 2, "minecraft:oak_log[axis=" + (br[0] != 0 ? "x" : "z") + "]");
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 5; dy <= 11; dy++) {
                    double r = Math.sqrt(dx * dx + dz * dz + (dy - 8.5) * (dy - 8.5) * 2.2);
                    if (r > 5.2 || (r > 4.4 && Builder.hash(x + dx, y + dy, z + dz) < 450)) continue;
                    if (b.in(x + dx, z + dz) && b.get(x + dx, y + dy, z + dz) == Material.AIR)
                        b.set(x + dx, y + dy, z + dz, "minecraft:oak_leaves[persistent=true]");
                }
            }
        }
    }

    private void flowerBed(Builder b, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                b.set(x + dx, y, z + dz, Material.GRASS_BLOCK);
                int h = Builder.hash(x + dx, 9, z + dz);
                b.set(x + dx, y + 1, z + dz, h < 200 ? "minecraft:oak_leaves[persistent=true]" : "minecraft:" + FLOWERS[h % FLOWERS.length]);
            }
        }
    }

    private void bench(Builder b, int x, int y, int z, int fx, int fz) {
        int lx = -fz, lz = fx;
        String back = facingName(-fx, -fz);          // the high side of the stairs points away from the fountain
        for (int a = -1; a <= 1; a++) b.set(x + lx * a, y + 1, z + lz * a, "minecraft:spruce_stairs[facing=" + back + "]");
        for (int a : new int[]{-2, 2}) b.set(x + lx * a, y + 1, z + lz * a, "minecraft:potted_" + (a < 0 ? "red_tulip" : "cornflower"));
    }

    /** Tall stone lamp of the avenue. */
    private void avenueLamp(Builder b, int x, int y, int z) {
        b.set(x, y, z, Material.STONE_BRICKS);
        b.set(x, y + 1, z, Material.STONE_BRICK_WALL);
        b.set(x, y + 2, z, Material.STONE_BRICK_WALL);
        b.set(x, y + 3, z, Material.STONE_BRICK_WALL);
        b.set(x, y + 4, z, Material.LANTERN);
    }

    private void lamp(Builder b, int x, int y, int z) {
        b.set(x, y + 1, z, Material.SPRUCE_FENCE);
        b.set(x, y + 2, z, Material.SPRUCE_FENCE);
        b.set(x, y + 3, z, Material.SPRUCE_FENCE);
        b.set(x, y + 4, z, Material.LANTERN);
    }

    // ------------------------------------------------------------------ houses

    private void buildHouse(Builder b, Lot lot) {
        Kind kind = KINDS.get(lot.type);
        Template tp = Template.get(kind.template());
        b.terraform(lot.ox, lot.oz, lot.ox + lot.sx - 1, lot.oz + lot.sz - 1, lot.floorY, Material.GRASS_BLOCK, 6, 26,
                (x, z) -> roadCells.containsKey(key(x, z)) || (protectedCells.contains(key(x, z))
                        && !(x >= lot.ox - 3 && x <= lot.ox + lot.sx + 2 && z >= lot.oz - 3 && z <= lot.oz + lot.sz + 2)));
        tp.place(b, lot.ox, lot.floorY, lot.oz, lot.rot, lot.vars, true);
        // path from the door to the road (planned round other buildings). It starts right at the door
        // (also over the house's own front yard) and eases from the floor height down or up to the
        // ground, one block per step, so there is no grass block or wall in between.
        List<Long> path = lot.path;
        int outside = 0;
        for (long kk : path) {
            int x = (int) (kk >> 32), z = (int) kk;
            if (roadCells.containsKey(kk)) break;
            boolean inLot = x >= lot.ox && z >= lot.oz && x < lot.ox + lot.sx && z < lot.oz + lot.sz;
            if (!inLot) outside++;
            if (!b.in(x, z)) continue;
            int g = b.ground(x, z);
            if (g < Pier.DECK) break;
            int y = inLot ? lot.floorY : Math.max(Math.min(g, lot.floorY + outside), lot.floorY - outside);   // at most 1 up or down per step
            if (inLot) {
                Material here = b.get(x, y, z);
                if (here != Material.GRASS_BLOCK && here != Material.DIRT && here != Material.COARSE_DIRT && here != Material.PODZOL) continue;
                if (!b.get(x, y + 1, z).isAir() && !Tag.REPLACEABLE.isTagged(b.get(x, y + 1, z))) continue;
            }
            for (int yy = g + 1; yy < y; yy++) b.set(x, yy, z, Material.DIRT);
            b.set(x, y, z, Material.DIRT_PATH);
            if (!inLot) b.clear(x, z, y + 1, y + 3);       // (inside the lot: keep the porch roof)
        }
        int x1 = lot.ox - 3, z1 = lot.oz - 3, x2 = lot.ox + lot.sx + 2, z2 = lot.oz + lot.sz + 2;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean inLot = x >= lot.ox && z >= lot.oz && x < lot.ox + lot.sx && z < lot.oz + lot.sz;
                if (inLot || !b.in(x, z) || roadCells.containsKey(key(x, z)) || path.contains(key(x, z)) && !lot.yard) continue;
                int g = b.ground(x, z);
                if (g < Pier.DECK) continue;
                boolean fenceLine = x == x1 || x == x2 || z == z1 || z == z2;
                if (lot.yard && fenceLine) {
                    if (Math.abs(g - lot.floorY) > 3) continue;
                    if (path.contains(key(x, z))) {
                        b.set(x, g, z, Material.DIRT_PATH);
                        b.set(x, g + 1, z, "minecraft:spruce_fence_gate[facing=" + facingName(lot.fx, lot.fz) + "]");
                    } else {
                        b.set(x, g + 1, z, Material.SPRUCE_FENCE);
                    }
                    continue;
                }
                if (path.contains(key(x, z)) || b.get(x, g + 1, z) != Material.AIR) continue;
                int h = Builder.hash(x, 5, z);
                if (lot.yard) {
                    // garden: flowers along the fence, a small tree in a back corner, sometimes beehive or composter
                    boolean byFence = x == x1 + 1 || x == x2 - 1 || z == z1 + 1 || z == z2 - 1;
                    if (byFence && h < 420) b.set(x, g + 1, z, "minecraft:" + FLOWERS[h % FLOWERS.length]);
                    else if (h < 25) b.set(x, g + 1, z, "minecraft:oak_leaves[persistent=true]");
                } else {
                    boolean ring = x < lot.ox - 2 || z < lot.oz - 2 || x > lot.ox + lot.sx + 1 || z > lot.oz + lot.sz + 1;
                    if (ring) continue;
                    if (h < 70) b.set(x, g + 1, z, "minecraft:oak_leaves[persistent=true]");
                    else if (h < 260) b.set(x, g + 1, z, "minecraft:" + FLOWERS[h % FLOWERS.length]);
                }
            }
        }
        if (lot.yard) {
            // a little tree in the back corner of the garden
            int bx = lot.fx != 0 ? (lot.fx > 0 ? x1 + 1 : x2 - 1) : (Builder.hash(lot.ox, 3, lot.oz) < 500 ? x1 + 1 : x2 - 1);
            int bz = lot.fz != 0 ? (lot.fz > 0 ? z1 + 1 : z2 - 1) : (Builder.hash(lot.ox, 4, lot.oz) < 500 ? z1 + 1 : z2 - 1);
            if (b.touches(bx - 2, bz - 2, bx + 2, bz + 2)) smallTree(b, bx, b.ground(bx, bz), bz, Builder.hash(bx, 0, bz) < 500);
        }
    }

    private static void smallTree(Builder b, int x, int g, int z, boolean birch) {
        Material log = birch ? Material.BIRCH_LOG : Material.OAK_LOG;
        String leaves = birch ? "minecraft:birch_leaves[persistent=true]" : "minecraft:oak_leaves[persistent=true]";
        for (int k = 1; k <= 4; k++) b.set(x, g + k, z, log);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 3; dy <= 6; dy++) {
                    int r = Math.abs(dx) + Math.abs(dz) + Math.max(0, dy - 5) * 2;
                    if (r > (dy >= 5 ? 1 : 3) || (dx == 0 && dz == 0 && dy <= 4)) continue;
                    if (b.in(x + dx, z + dz) && b.get(x + dx, g + dy, z + dz) == Material.AIR) b.set(x + dx, g + dy, z + dz, leaves);
                }
    }

    private void buildFarm(Builder b, Lot lot) {
        int y = lot.floorY;
        String crop = switch (Math.floorMod(lot.ox + lot.oz, 4)) {
            case 0 -> "minecraft:wheat[age=7]";
            case 1 -> "minecraft:carrots[age=7]";
            case 2 -> "minecraft:beetroots[age=3]";
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
        b.set(lot.ox + 8, y + 1, lot.oz, Material.SPRUCE_FENCE);
        b.set(lot.ox + 8, y + 2, lot.oz, Material.CARVED_PUMPKIN);   // scarecrow head
    }

    /** Fenced pasture with a gate to the road, a water trough and hay. */
    private void buildPen(Builder b, Lot lot) {
        int y = lot.floorY;
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                int x = lot.ox + i, z = lot.oz + j;
                if (!b.in(x, z)) continue;
                b.foundation(x, z, y, Material.DIRT);
                b.set(x, y, z, Builder.hash(x, 1, z) < 120 ? Material.COARSE_DIRT : Material.GRASS_BLOCK);
                b.clear(x, z, y + 1, y + 10);
                boolean border = i == 0 || j == 0 || i == 8 || j == 8;
                if (border) b.set(x, y + 1, z, Material.SPRUCE_FENCE);
                else if (Builder.hash(x, 2, z) < 200) b.set(x, y + 1, z, Material.SHORT_GRASS);
            }
        }
        // gate in the middle of the side facing the road
        b.set(lot.doorX, y + 1, lot.doorZ, "minecraft:spruce_fence_gate[facing=" + facingName(lot.fx, lot.fz) + "]");
        b.set(lot.ox + 1, y + 1, lot.oz + 1, "minecraft:water_cauldron[level=3]");
        b.set(lot.ox + 2, y + 1, lot.oz + 1, "minecraft:water_cauldron[level=3]");
        b.set(lot.ox + 7, y + 1, lot.oz + 7, Material.HAY_BLOCK);
        b.set(lot.ox + 7, y + 1, lot.oz + 6, Material.HAY_BLOCK);
        for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) {
            b.set(lot.ox + c[0], y + 2, lot.oz + c[1], Material.TORCH);
        }
    }

    private static EntityType penAnimal(Lot lot) {
        EntityType[] kinds = {EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN};
        return kinds[Math.floorMod(lot.ox * 31 + lot.oz, kinds.length)];
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

    /** Spawn point: the middle of the city hall. */
    public int spawnX() {
        return hallOx + hall.offset(HALL_SPAWN_X, HALL_SPAWN_Z, hallRot)[0];
    }

    public int spawnZ() {
        return hallOz + hall.offset(HALL_SPAWN_X, HALL_SPAWN_Z, hallRot)[1];
    }

    public void collectTasks(List<StructurePlan.Task> tasks) {
        for (Lot lot : lots) {
            if (lot.type.equals("pen")) {
                EntityType animal = penAnimal(lot);
                for (int k = 0; k < 4; k++) {
                    tasks.add(new StructurePlan.SpawnEntity(lot.ox + 2 + k, lot.floorY + 1, lot.oz + 3 + (k % 2) * 2, animal));
                }
                continue;
            }
            if (!lot.house()) continue;
            Kind kind = KINDS.get(lot.type);
            Template tp = Template.get(kind.template());
            int[] off = tp.offset(kind.doorCol(), kind.inside(), lot.rot);
            int x = lot.ox + off[0], z = lot.oz + off[1];
            tasks.add(new StructurePlan.SpawnVillager(x, lot.floorY + 1, z, professionFor(lot.station), false, null));
            if (kind.villagers() > 1) tasks.add(new StructurePlan.SpawnVillager(x, lot.floorY + 1, z, Villager.Profession.NONE, false, null));
        }
        // the world map: 3 x 3 frames on the back wall of the city hall
        int[] f = facingVector(hallRot);
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                int[] off = hall.offset(MAP_X + col, MAP_Z, hallRot);
                tasks.add(new StructurePlan.MapFrame(hallOx + off[0], plazaY + MAP_Y + 2 - row, hallOz + off[1], f[0], f[1], col, row));
            }
        }
        int[] lec = hall.offset(17, 5, hallRot);         // the lectern by the map wall
        tasks.add(new StructurePlan.GuideLectern(hallOx + lec[0], plazaY + 1, hallOz + lec[1]));
        tasks.add(new StructurePlan.SpawnVillager(cx - 6, plazaY + 1, cz + 7, Villager.Profession.NONE, false, null));
        tasks.add(new StructurePlan.SpawnVillager(cx + 7, plazaY + 1, cz - 6, Villager.Profession.NONE, false, null));
    }
}
