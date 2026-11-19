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

    public SetupListener(SettlerIslands plugin) {
        this.plugin = plugin;
        this.doneKey = new NamespacedKey(plugin, "setup_done");
        this.campKey = new NamespacedKey(plugin, "camp_trader");
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

    private void run(World world, StructurePlan.Task task) {
        switch (task) {
            case StructurePlan.SpawnVillager v -> {
                Location loc = new Location(world, v.x() + 0.5, v.y(), v.z() + 0.5);
                world.spawn(loc, Villager.class, villager -> {
                    villager.setProfession(v.profession());
                    if (v.profession() != Villager.Profession.NONE) villager.setVillagerExperience(1);
                    villager.setPersistent(true);
                    villager.setRemoveWhenFarAway(false);
                    if (v.camp()) {
                        villager.setInvulnerable(true);
                        villager.getPersistentDataContainer().set(campKey, PersistentDataType.BYTE, (byte) 1);
                    }
                });
            }
            case StructurePlan.SpawnBoat boat -> world.spawnEntity(new Location(world, boat.x() + 0.5, boat.y(), boat.z() + 0.5), boat.type());
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
