package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The support rule (also in creative): a placed block must be within 'reach' blocks (sideways) of something that
 * stands on the ground, counting along the blocks it is connected to. Going up or down through
 * connected blocks is free, so walls, pillars and towers are fine; only long overhangs and
 * bridges without pillars fail. An unsupported block falls down like sand. When a placed block
 * is broken, the placed blocks next to it are checked again (so knocking out a pillar can bring a
 * bridge down). "Standing on the ground" means a column of solid blocks reaching down into the
 * terrain. Only player-placed blocks ever fall, never the natural terrain.
 */
public final class SupportRule implements Listener {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private final SettlerIslands plugin;
    private final NamespacedKey placedKey;
    private final Map<Long, Set<Integer>> placedCache = new HashMap<>();

    public SupportRule(SettlerIslands plugin) {
        this.plugin = plugin;
        this.placedKey = new NamespacedKey(plugin, "placed");
    }

    private int span() {
        return plugin.getConfig().getInt("rules.support-reach", 8);
    }

    private static boolean island(World w) {
        return w.getGenerator() instanceof IslandGenerator;
    }

    /** Blocks the rule cares about: solid, not already falling, not two-block things. */
    static boolean applies(Material m) {
        if (!m.isSolid() || m.hasGravity()) return false;
        if (Tag.BEDS.isTagged(m) || Tag.DOORS.isTagged(m) || m == Material.SCAFFOLDING || m == Material.PISTON_HEAD
                || m == Material.MOVING_PISTON || m == Material.BEDROCK) return false;
        return true;
    }

    // ---------------------------------------------------------------- placed-block memory (per chunk)

    private static int local(Block b) {
        return ((b.getY() + 2048) << 8) | ((b.getX() & 15) << 4) | (b.getZ() & 15);
    }

    private Set<Integer> placed(Chunk c) {
        long key = ((long) c.getX() << 32) ^ (c.getZ() & 0xffffffffL) ^ ((long) c.getWorld().getName().hashCode() << 52);
        return placedCache.computeIfAbsent(key, k -> {
            Set<Integer> set = new HashSet<>();
            int[] arr = c.getPersistentDataContainer().get(placedKey, PersistentDataType.INTEGER_ARRAY);
            if (arr != null) for (int v : arr) set.add(v);
            return set;
        });
    }

    private void save(Chunk c, Set<Integer> set) {
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        int[] arr = new int[set.size()];
        int i = 0;
        for (int v : set) arr[i++] = v;
        pdc.set(placedKey, PersistentDataType.INTEGER_ARRAY, arr);
    }

    private boolean isPlaced(Block b) {
        return placed(b.getChunk()).contains(local(b));
    }

    private void mark(Block b, boolean on) {
        Set<Integer> set = placed(b.getChunk());
        boolean changed = on ? set.add(local(b)) : set.remove(local(b));
        if (changed) save(b.getChunk(), set);
    }

    // ---------------------------------------------------------------- the test

    /** A solid column under this block that reaches down into the ground (not ending in air or water). */
    private static boolean grounded(Block b) {
        World w = b.getWorld();
        int x = b.getX(), z = b.getZ();
        int min = w.getMinHeight();
        for (int y = b.getY() - 1, n = 0; y > min; y--, n++) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (!m.isSolid()) return false;
            if (n >= 48) return true;
        }
        return true;
    }

    /** True if the block is held up within 'span' sideways steps (0-1 BFS: sideways costs 1, up/down 0). */
    boolean supported(Block start) {
        int span = span();
        ArrayDeque<Object[]> dq = new ArrayDeque<>();
        Map<Block, Integer> best = new HashMap<>();
        dq.add(new Object[]{start, 0});
        best.put(start, 0);
        int visited = 0;
        while (!dq.isEmpty()) {
            Object[] e = dq.pollFirst();
            Block b = (Block) e[0];
            int cost = (Integer) e[1];
            if (cost > best.getOrDefault(b, Integer.MAX_VALUE)) continue;
            if (++visited > 3000) return true;             // huge connected structure: don't fuss
            if (grounded(b)) return true;
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN}) {
                Block n = b.getRelative(f);
                if (!n.getType().isSolid()) continue;
                if (cost < best.getOrDefault(n, Integer.MAX_VALUE)) {
                    best.put(n, cost);
                    dq.addFirst(new Object[]{n, cost});
                }
            }
            if (cost >= span) continue;
            for (BlockFace f : SIDES) {
                Block n = b.getRelative(f);
                if (!n.getType().isSolid()) continue;
                if (cost + 1 < best.getOrDefault(n, Integer.MAX_VALUE)) {
                    best.put(n, cost + 1);
                    dq.addLast(new Object[]{n, cost + 1});
                }
            }
        }
        return false;
    }

    private void fall(Block b) {
        if (b.getState() instanceof org.bukkit.block.Container) {
            // chests and other containers break and drop what is in them instead of falling
            mark(b, false);
            b.breakNaturally();
            return;
        }
        BlockData data = b.getBlockData();
        mark(b, false);
        b.setType(Material.AIR, false);
        Location loc = b.getLocation().add(0.5, 0, 0.5);
        b.getWorld().spawn(loc, org.bukkit.entity.FallingBlock.class, fb -> {
            fb.setBlockData(data);
            fb.setDropItem(true);
            fb.setHurtEntities(false);
        });
    }

    /** After something was removed: check the placed blocks around it, and around anything that falls. */
    private void cascade(Block origin) {
        ArrayDeque<Block> todo = new ArrayDeque<>();
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.DOWN})
            todo.add(origin.getRelative(f));
        int budget = 96;
        Set<Block> seen = new HashSet<>();
        while (!todo.isEmpty() && budget-- > 0) {
            Block b = todo.poll();
            if (!seen.add(b) || !applies(b.getType()) || !isPlaced(b)) continue;
            if (supported(b)) continue;
            fall(b);
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.DOWN})
                todo.add(b.getRelative(f));
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        if (!island(b.getWorld()) || !applies(b.getType())) return;
        mark(b, true);
        Material type = b.getType();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.getType() != type || supported(b)) return;
            event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                    "Nothing holds this up (more than " + span() + " blocks from a support) - it falls"));
            fall(b);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!island(b.getWorld())) return;
        mark(b, false);
        Bukkit.getScheduler().runTask(plugin, () -> cascade(b));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!island(event.getLocation().getWorld())) return;
        List<Block> blocks = List.copyOf(event.blockList());
        for (Block b : blocks) mark(b, false);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Block b : blocks) cascade(b);
        });
    }

    /** Forget the in-memory copy when a chunk unloads (it is saved in the chunk already). */
    @EventHandler
    public void onUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        Chunk c = event.getChunk();
        long key = ((long) c.getX() << 32) ^ (c.getZ() & 0xffffffffL) ^ ((long) c.getWorld().getName().hashCode() << 52);
        placedCache.remove(key);
    }
}
