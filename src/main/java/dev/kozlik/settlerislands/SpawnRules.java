package dev.kozlik.settlerislands;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawn rules that belong to the world itself:
 * no iron golems from the village, and wither skeletons / blazes inside the fortress.
 * (The full per-zone mob rules come in phase 3.)
 */
public final class SpawnRules implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Location loc = event.getLocation();
        if (!(loc.getWorld().getGenerator() instanceof IslandGenerator)) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        if (event.getEntityType() == EntityType.IRON_GOLEM
                && (reason == CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE || reason == CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION)) {
            event.setCancelled(true);
            return;
        }

        if (reason == CreatureSpawnEvent.SpawnReason.NATURAL && event.getEntity() instanceof Monster
                && event.getEntityType() != EntityType.WITHER_SKELETON && event.getEntityType() != EntityType.BLAZE) {
            long seed = loc.getWorld().getSeed();
            StructurePlan plan = StructurePlan.get(WorldModel.get(seed), seed);
            if (plan.fortress.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                event.setCancelled(true);
                EntityType type = ThreadLocalRandom.current().nextDouble() < 0.7 ? EntityType.WITHER_SKELETON : EntityType.BLAZE;
                loc.getWorld().spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            }
        }
    }
}
