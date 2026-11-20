package dev.kozlik.settlerislands;

import org.bukkit.Location;
import org.bukkit.entity.Enemy;
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
 * Meadow has no hostile mobs; everywhere else spawning is vanilla.
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

        // Meadow is safe: no hostile mobs spawn there on their own (no patrols either)
        if ((reason == CreatureSpawnEvent.SpawnReason.NATURAL || reason == CreatureSpawnEvent.SpawnReason.PATROL
                || reason == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) && event.getEntity() instanceof Enemy) {
            long seed = loc.getWorld().getSeed();
            WorldModel.Sample sm = WorldModel.get(seed).sample(loc.getBlockX(), loc.getBlockZ());
            // Meadow and a 48-block band around its coast (beaches, shallows) stay free of monsters
            if (sm.zone() == Zone.MEADOW || (sm.nearZone() == Zone.MEADOW && sm.s() > -48)) {
                event.setCancelled(true);
                return;
            }
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
