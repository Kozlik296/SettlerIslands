package dev.kozlik.settlerislands;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where every structure goes. Positions are found from the island shapes (for example "the
 * coast of Timberlands facing west"), so they always sit on the real coastline.
 */
public final class StructurePlan {

    /** A point on a coast: where land ends along a ray, plus the outward direction. */
    public record Coast(int x, int z, double dx, double dz) {
        public int px(double t, double side) {
            return (int) Math.round(x + dx * t - dz * side);
        }

        public int pz(double t, double side) {
            return (int) Math.round(z + dz * t + dx * side);
        }

        /** Same point, direction turned to the nearest axis (straight piers look clean). */
        public Coast snapped() {
            if (Math.abs(dx) > Math.abs(dz)) return new Coast(x, z, Math.signum(dx), 0);
            return new Coast(x, z, 0, Math.signum(dz));
        }
    }

    /** Things that need the live world (entities, spawner types, loot) and are done when the chunk first loads. */
    public sealed interface Task permits SpawnVillager, SetSpawner, SetLoot, SpawnEntity, MapFrame, GuideLectern {
        int x();

        int z();
    }

    /** trades: id of a fixed trade list in trades.yml, or null for normal village trades. */
    public record SpawnVillager(int x, int y, int z, Villager.Profession profession, boolean camp, String trades) implements Task {
    }

    /** One item frame of the world map wall: (fx, fz) is where it faces, (col, row) which part of the world it shows. */
    public record MapFrame(int x, int y, int z, int fx, int fz, int col, int row) implements Task {
    }

    /** The lectern in the city hall gets the guide book. */
    public record GuideLectern(int x, int y, int z) implements Task {
    }

    /** Boats at the piers, animals in the pens. */
    public record SpawnEntity(int x, int y, int z, EntityType type) implements Task {
    }

    public record SetSpawner(int x, int y, int z, EntityType type) implements Task {
    }

    public record SetLoot(int x, int y, int z, String lootTable) implements Task {
    }

    private static final Map<Long, StructurePlan> PLANS = new ConcurrentHashMap<>();

    public static StructurePlan get(WorldModel model, long seed) {
        return PLANS.computeIfAbsent(seed, s -> new StructurePlan(model));
    }

    public final Village village;
    public final Fortress fortress;
    public final Harbour harbour;
    public final List<Settlement> settlements = new ArrayList<>();
    public final List<Pier> piers = new ArrayList<>();
    public final List<Task> tasks = new ArrayList<>();

    private StructurePlan(WorldModel m) {
        Layout l = m.layout();

        // Meadow: main harbour in the east bay, village inland, the street joins them
        Layout.Island meadow = l.find("meadow");
        Coast bay = coast(m, meadow, 0);
        harbour = new Harbour(bay);
        village = new Village(m, bay.px(-190, 0), bay.pz(-190, 0), seedOf(l), bay.px(-13, 0), bay.pz(-13, 0));

        // Timberlands: cove port facing Meadow
        Layout.Island timber = l.find("timberlands");
        settlements.add(new Settlement(m, timber, 180, "timberlands"));
        piers.add(new Pier(coast(m, timber, 170).snapped(), 30, true));
        piers.add(new Pier(coast(m, timber, 190).snapped(), 30, true));

        // the other settlements, each on the side of its island that faces the way in
        settlements.add(new Settlement(m, l.find("sandbanks"), 120, "sandbanks"));
        settlements.add(new Settlement(m, l.find("ironstone"), 40, "ironstone"));
        settlements.add(new Settlement(m, l.find("highlands"), 40, "highlands"));
        settlements.add(new Settlement(m, l.find("nether"), 150, "nether"));

        Layout.Island nether = l.find("nether");
        fortress = new Fortress((int) nether.cx + 40, (int) nether.cz);

        village.collectTasks(tasks);
        fortress.collectTasks(tasks);
        harbour.collectTasks(tasks);
        for (Settlement st : settlements) st.collectTasks(tasks);
    }

    private static long seedOf(Layout l) {
        return l.islands().size() * 1_000_003L;
    }

    /** Walks out from the island centre along the angle until the island ends. */
    public static Coast coast(WorldModel m, Layout.Island isl, double angleDeg) {
        double dx = Math.cos(Math.toRadians(angleDeg)), dz = Math.sin(Math.toRadians(angleDeg));
        int lastX = (int) isl.cx, lastZ = (int) isl.cz;
        double max = Math.max(isl.hw, isl.hh) * 2.5;
        boolean wasLand = false;
        for (double r = 0; r < max; r += 1) {
            int x = (int) Math.round(isl.cx + dx * r), z = (int) Math.round(isl.cz + dz * r);
            WorldModel.Sample sm = m.sample(x, z);
            boolean land = sm.land() && sm.island() == isl;
            if (land) {
                lastX = x;
                lastZ = z;
                wasLand = true;
            } else if (wasLand && !sm.land()) {
                break;
            }
        }
        return new Coast(lastX, lastZ, dx, dz);
    }

    /** Spawn point: inside the city hall. */
    public int spawnX() {
        return village.spawnX();
    }

    public int spawnZ() {
        return village.spawnZ();
    }

    public int spawnY() {
        return village.plazaY + 1;
    }
}
