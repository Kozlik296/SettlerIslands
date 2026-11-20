package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;

/**
 * The first time a structure chunk loads, this spawns its villagers, sets the blaze spawners
 * and fills the loot chests (things that need the live world, not the generator).
 */
public final class SetupListener implements Listener {

    private final SettlerIslands plugin;
    private final NamespacedKey doneKey;
    public final NamespacedKey campKey;
    public final NamespacedKey tradesKey;

    public SetupListener(SettlerIslands plugin) {
        this.plugin = plugin;
        this.doneKey = new NamespacedKey(plugin, "setup_done");
        this.campKey = new NamespacedKey(plugin, "camp_trader");
        this.tradesKey = new NamespacedKey(plugin, "trades");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!(world.getGenerator() instanceof IslandGenerator)) return;
        Chunk chunk = event.getChunk();
        if (chunk.getPersistentDataContainer().has(doneKey, PersistentDataType.BYTE)) return;
        int cx = chunk.getX(), cz = chunk.getZ();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!chunk.isLoaded() || chunk.getPersistentDataContainer().has(doneKey, PersistentDataType.BYTE)) return;
            StructurePlan plan = StructurePlan.get(WorldModel.get(world.getSeed()), world.getSeed());
            for (StructurePlan.Task task : plan.tasks) {
                if ((task.x() >> 4) != cx || (task.z() >> 4) != cz) continue;
                try {
                    run(world, task);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Structure setup failed at " + task + ": " + e);
                }
            }
            chunk.getPersistentDataContainer().set(doneKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Gives a villager its fixed trade list; it keeps its job and never levels up. */
    public void applyTrades(Villager villager, Trades.Trader trader) {
        villager.setProfession(trader.profession());
        villager.setVillagerExperience(1);
        villager.setVillagerLevel(1);
        villager.setRecipes(Trades.copy(trader));
        villager.getPersistentDataContainer().remove(new NamespacedKey(plugin, "base_prices"));
        villager.getPersistentDataContainer().set(tradesKey, PersistentDataType.STRING, trader.id());
    }

    private boolean fixed(Villager v) {
        return v.getPersistentDataContainer().has(tradesKey, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        if (event.getEntity() instanceof Villager v && fixed(v)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (fixed(event.getEntity())) event.setCancelled(true);
    }

    /**
     * Hangs one map of the world map wall (replacing a frame already in that spot). The frame is
     * summoned like the /summon command does, which places it exactly where asked (spawning it
     * through the API refuses frames right next to each other).
     */
    public void spawnMapFrame(World world, StructurePlan.MapFrame f) {
        org.bukkit.block.Block block = world.getBlockAt(f.x(), f.y(), f.z());
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(block.getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5)) {
            if (e instanceof org.bukkit.entity.ItemFrame && e.getLocation().getBlock().equals(block)) e.remove();
        }
        org.bukkit.inventory.ItemStack map = plugin.worldMap().item(world, f.col(), f.row());
        int id = ((org.bukkit.inventory.meta.MapMeta) map.getItemMeta()).getMapView().getId();
        int facing = f.fx() > 0 ? 5 : f.fx() < 0 ? 4 : f.fz() > 0 ? 3 : 2;   // east, west, south, north
        String cmd = "execute in " + world.getKey().asString() + " run summon minecraft:glow_item_frame "
                + f.x() + " " + f.y() + " " + f.z()
                + " {Facing:" + facing + "b,Fixed:1b,Invisible:1b,Invulnerable:1b,"
                + "Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":" + id + "}}}";
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
            throw new IllegalStateException("summon failed: " + cmd);
        }
    }

    private void run(World world, StructurePlan.Task task) {
        switch (task) {
            case StructurePlan.SpawnVillager v -> {
                Location loc = new Location(world, v.x() + 0.5, v.y(), v.z() + 0.5);
                Trades.Trader trader = v.trades() != null ? Trades.get(v.trades()) : null;
                if (v.trades() != null && trader == null) plugin.getLogger().warning("No trader in trades.yml for " + v.trades());
                Villager.Profession prof = trader != null ? trader.profession() : v.profession();
                world.spawn(loc, Villager.class, villager -> {
                    villager.setProfession(prof);
                    if (prof != Villager.Profession.NONE) villager.setVillagerExperience(1);
                    if (trader != null) applyTrades(villager, trader);
                    villager.setPersistent(true);
                    villager.setRemoveWhenFarAway(false);
                    if (v.camp()) {
                        villager.setInvulnerable(true);
                        villager.getPersistentDataContainer().set(campKey, PersistentDataType.BYTE, (byte) 1);
                    }
                });
            }
            case StructurePlan.MapFrame f -> spawnMapFrame(world, f);
            case StructurePlan.SpawnEntity e -> world.spawnEntity(new Location(world, e.x() + 0.5, e.y(), e.z() + 0.5), e.type());
            case StructurePlan.SetSpawner s -> {
                Block block = world.getBlockAt(s.x(), s.y(), s.z());
                if (block.getState() instanceof CreatureSpawner cs) {
                    cs.setSpawnedType(s.type());
                    cs.update(true, false);
                }
            }
            case StructurePlan.SetLoot l -> {
                Block block = world.getBlockAt(l.x(), l.y(), l.z());
                BlockState state = block.getState();
                LootTable table = Bukkit.getLootTable(NamespacedKey.fromString(l.lootTable()));
                if (state instanceof Lootable lootable && table != null) {
                    lootable.setLootTable(table);
                    state.update(true, false);
                }
            }
        }
    }
}
