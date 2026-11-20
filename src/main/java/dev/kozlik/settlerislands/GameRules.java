package dev.kozlik.settlerislands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The smaller gameplay rules of the island worlds: banned items and travel, food that spoils in
 * the inventory, and faster crops and breeding on Meadow.
 */
public final class GameRules implements Listener {

    private static final Set<Material> NEVER_SPOILS = EnumSet.of(Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
            Material.GOLDEN_CARROT, Material.ROTTEN_FLESH, Material.SPIDER_EYE, Material.POISONOUS_POTATO,
            Material.CHORUS_FRUIT, Material.HONEY_BOTTLE);

    private final SettlerIslands plugin;
    private final NamespacedKey freshKey, onlineKey;

    public GameRules(SettlerIslands plugin) {
        this.plugin = plugin;
        this.freshKey = new NamespacedKey(plugin, "fresh_hours");
        this.onlineKey = new NamespacedKey(plugin, "online_minutes");
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 1200L, 1200L);
        this.weightKey = new NamespacedKey(plugin, "carry_weight");
        Bukkit.getScheduler().runTaskTimer(plugin, this::weight, 40L, 10L);
    }

    // ================================================================ carrying weight

    private final NamespacedKey weightKey;
    private final java.util.Map<java.util.UUID, Integer> lastTier = new java.util.HashMap<>();

    /**
     * The fuller your inventory, the slower you walk. Every slot counts as much of a full stack as
     * it holds (16 of 64 cobblestone = a quarter, one sword = a whole stack); the first 'free-stacks'
     * cost nothing, each one above that slows you by 'per-stack', up to 'max'.
     */
    private void weight() {
        double free = plugin.getConfig().getDouble("rules.weight.free-stacks", 12);
        double per = plugin.getConfig().getDouble("rules.weight.per-stack", 0.025);
        double max = plugin.getConfig().getDouble("rules.weight.max", 0.5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.attribute.AttributeInstance speed = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
            if (speed == null) continue;
            double slow = 0;
            boolean counts = island(p.getWorld()) && per > 0
                    && (p.getGameMode() == org.bukkit.GameMode.SURVIVAL || p.getGameMode() == org.bukkit.GameMode.ADVENTURE);
            if (counts) {
                double load = 0;
                PlayerInventory inv = p.getInventory();
                for (ItemStack it : inv.getStorageContents()) if (it != null && !it.getType().isAir()) load += it.getAmount() / (double) it.getMaxStackSize();
                ItemStack off = inv.getItemInOffHand();
                if (!off.getType().isAir()) load += off.getAmount() / (double) off.getMaxStackSize();
                slow = Math.min(max, Math.max(0, load - free) * per);
            }
            org.bukkit.attribute.AttributeModifier old = speed.getModifier(weightKey);
            double current = old == null ? 0 : -old.getAmount();
            if (Math.abs(current - slow) < 0.005) continue;
            if (old != null) speed.removeModifier(weightKey);
            if (slow > 0) {
                speed.addTransientModifier(new org.bukkit.attribute.AttributeModifier(weightKey, -slow,
                        org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1, org.bukkit.inventory.EquipmentSlotGroup.ANY));
            }
            int tier = slow <= 0 ? 0 : slow < max * 0.4 ? 1 : slow < max * 0.8 ? 2 : 3;
            Integer was = lastTier.put(p.getUniqueId(), tier);
            if (was != null && was != tier && tier > 0) {
                String[] words = {"", "Your pack is getting heavy", "Your pack is heavy - you walk slowly", "You are overloaded"};
                p.sendActionBar(Component.text(words[tier] + " (" + Math.round(slow * 100) + "% slower)", NamedTextColor.GOLD));
            }
        }
    }

    private static boolean island(World w) {
        return w != null && w.getGenerator() instanceof IslandGenerator;
    }

    private boolean bans() {
        return plugin.getConfig().getBoolean("rules.bans", true);
    }

    private static void deny(Player p, String text) {
        p.sendActionBar(Component.text(text, NamedTextColor.RED));
    }

    // ================================================================ bans

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && bans() && island(event.getEntity().getWorld())) {
            event.setCancelled(true);
            if (event.getEntity() instanceof Player p) deny(p, "Elytra don't work here - take a boat or a train");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (!bans() || !island(event.getPlayer().getWorld())) return;
        Player p = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Material m = event.getClickedBlock().getType();
            if (m == Material.ENDER_CHEST || Tag.SHULKER_BOXES.isTagged(m)) {
                event.setCancelled(true);
                deny(p, "Ender chests and shulker boxes are banned");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceBanned(BlockPlaceEvent event) {
        if (!bans() || !island(event.getBlock().getWorld())) return;
        Material m = event.getBlockPlaced().getType();
        Player p = event.getPlayer();
        if (m == Material.ENDER_CHEST || Tag.SHULKER_BOXES.isTagged(m)) {
            event.setCancelled(true);
            deny(p, "Ender chests and shulker boxes are banned");
            return;
        }
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE) {
            Block b = event.getBlockPlaced();
            WorldModel model = WorldModel.get(b.getWorld().getSeed());
            if (!model.sample(b.getX(), b.getZ()).land()) {
                event.setCancelled(true);
                deny(p, "No ice roads over the sea - build a ship or a bridge");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrostWalker(EntityBlockFormEvent event) {
        Block b = event.getBlock();
        if (!bans() || !island(b.getWorld()) || event.getNewState().getType() != Material.FROSTED_ICE) return;
        if (!WorldModel.get(b.getWorld().getSeed()).sample(b.getX(), b.getZ()).land()) event.setCancelled(true);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        ItemStack r = event.getInventory().getResult();
        if (r == null || !bans() || !island(event.getView().getPlayer().getWorld())) return;
        if (r.getType() == Material.ENDER_CHEST || Tag.SHULKER_BOXES.isTagged(r.getType())) event.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (bans() && island(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (bans() && island(event.getFrom().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer(), "Portals don't work here");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (bans() && island(event.getFrom().getWorld())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!bans() || !island(event.getFrom().getWorld())) return;
        String cause = event.getCause().name();
        if (cause.equals("CHORUS_FRUIT") || cause.equals("CONSUMABLE_EFFECT")) {
            event.setCancelled(true);
        }
    }

    // ================================================================ food spoilage

    private int spoilHours() {
        return plugin.getConfig().getInt("rules.spoil-hours", 12);
    }

    private static boolean spoils(ItemStack it) {
        return it != null && it.getType().isEdible() && !NEVER_SPOILS.contains(it.getType());
    }

    /** Every minute: count the online time; every full hour of it, the food a player carries ages. */
    private void minute() {
        int hours = spoilHours();
        if (hours <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!island(p.getWorld())) continue;
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            int min = pdc.getOrDefault(onlineKey, PersistentDataType.INTEGER, 0) + 1;
            pdc.set(onlineKey, PersistentDataType.INTEGER, min);
            PlayerInventory inv = p.getInventory();
            boolean hour = min % 60 == 0;
            for (int slot = 0; slot < inv.getSize(); slot++) {
                ItemStack it = inv.getItem(slot);
                if (!spoils(it)) continue;
                ItemMeta meta = it.getItemMeta();
                Integer left = meta.getPersistentDataContainer().get(freshKey, PersistentDataType.INTEGER);
                if (left == null) left = hours;                 // food new to the inventory starts fresh
                else if (hour) left--;
                else continue;
                if (left <= 0) {
                    inv.setItem(slot, new ItemStack(Material.ROTTEN_FLESH, it.getAmount()));
                    continue;
                }
                meta.getPersistentDataContainer().set(freshKey, PersistentDataType.INTEGER, left);
                NamedTextColor c = left > hours / 2 ? NamedTextColor.GREEN : left > 2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
                meta.lore(List.of(Component.text("Fresh for " + left + " h", c).decoration(TextDecoration.ITALIC, false)));
                it.setItemMeta(meta);
            }
        }
    }

    // ================================================================ Meadow: faster crops and breeding

    private boolean meadow(Entity e) {
        return island(e.getWorld()) && WorldModel.get(e.getWorld().getSeed())
                .sample(e.getLocation().getBlockX(), e.getLocation().getBlockZ()).zone() == Zone.MEADOW;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        Block b = event.getBlock();
        if (!island(b.getWorld())) return;
        if (WorldModel.get(b.getWorld().getSeed()).sample(b.getX(), b.getZ()).zone() != Zone.MEADOW) return;
        double factor = plugin.getConfig().getDouble("rules.meadow-growth", 2);
        if (factor <= 1 || Math.random() > factor - 1) return;
        // one extra growth step for every natural one (2x speed)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.getBlockData() instanceof Ageable age && age.getAge() < age.getMaximumAge()) {
                age.setAge(age.getAge() + 1);
                b.setBlockData(age);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!meadow(event.getEntity())) return;
        double factor = plugin.getConfig().getDouble("rules.meadow-growth", 2);
        if (factor <= 1) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity parent : new Entity[]{event.getMother(), event.getFather()}) {
                if (parent instanceof Animals a && a.getAge() > 0) a.setAge((int) (a.getAge() / factor));
            }
            if (event.getEntity() instanceof org.bukkit.entity.Ageable baby && baby.getAge() < 0) {
                baby.setAge((int) (baby.getAge() / factor));
            }
        });
    }
}
