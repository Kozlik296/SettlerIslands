package dev.kozlik.settlerislands;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Location prices. Every place (the island the villager stands on: Meadow, Timberlands,
 * Sandbanks...) has one price per trade, the same for everyone. Each time a trade is used there it
 * gets a little worse (the villager asks more emeralds, or wants more goods for its emeralds), up
 * to a limit; with time it drifts back to normal. Reputation, curing and demand discounts are
 * switched off. The village villagers also never offer iron or diamond gear, diamonds or bells.
 */
public final class Economy implements Listener {

    private final SettlerIslands plugin;
    private final NamespacedKey baseKey, fixedKey;
    private final File file;
    /** place -> trade -> {pressure, last update time in ms} */
    private final Map<String, Map<String, double[]>> pressure = new HashMap<>();

    public Economy(SettlerIslands plugin, NamespacedKey fixedKey) {
        this.plugin = plugin;
        this.fixedKey = fixedKey;
        this.baseKey = new NamespacedKey(plugin, "base_prices");
        this.file = new File(plugin.getDataFolder(), "prices.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    private double step() {
        return plugin.getConfig().getDouble("rules.prices.step", 0.02);
    }

    private double max() {
        return plugin.getConfig().getDouble("rules.prices.max", 2.0);
    }

    private double halfLifeMs() {
        return plugin.getConfig().getDouble("rules.prices.half-life-hours", 12) * 3600_000.0;
    }

    // ---------------------------------------------------------------- state

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String place : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(place);
            if (s == null) continue;
            Map<String, double[]> m = new HashMap<>();
            for (String trade : s.getKeys(false)) {
                List<Double> v = s.getDoubleList(trade);
                if (v.size() == 2) m.put(trade.replace('|', '.'), new double[]{v.get(0), v.get(1)});
            }
            pressure.put(place, m);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : pressure.entrySet()) {
            for (var t : e.getValue().entrySet()) {
                double v = current(t.getValue());
                if (v < 0.01) continue;
                y.set(e.getKey() + "." + t.getKey().replace('.', '|'), List.of(v, (double) System.currentTimeMillis()));
            }
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save prices.yml: " + ex.getMessage());
        }
    }

    /** Pressure after decay up to now. */
    private double current(double[] p) {
        double dt = System.currentTimeMillis() - p[1];
        return p[0] * Math.pow(0.5, dt / halfLifeMs());
    }

    private static String place(Entity v) {
        if (!(v.getWorld().getGenerator() instanceof IslandGenerator)) return null;
        WorldModel.Sample sm = WorldModel.get(v.getWorld().getSeed()).sample(v.getLocation().getBlockX(), v.getLocation().getBlockZ());
        return sm.land() ? sm.island().id : "sea";
    }

    private static String tradeKey(MerchantRecipe r) {
        List<ItemStack> in = r.getIngredients();
        return r.getResult().getType().name() + "_for_" + (in.isEmpty() ? "NOTHING" : in.get(0).getType().name());
    }

    /** How much worse the trade is here now: 1.0 = normal, up to 'max'. */
    private double factor(String place, MerchantRecipe r) {
        double[] p = pressure.getOrDefault(place, Map.of()).get(tradeKey(r));
        if (p == null) return 1;
        return Math.min(max(), Math.pow(1 + step(), current(p)));
    }

    // ---------------------------------------------------------------- filter

    private static boolean banned(ItemStack result) {
        String n = result.getType().name();
        if (result.getType() == Material.DIAMOND || result.getType() == Material.DIAMOND_BLOCK || result.getType() == Material.BELL) return true;
        if (!(n.startsWith("IRON_") || n.startsWith("DIAMOND_"))) return false;
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SWORD")
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_SPEAR");
    }

    private boolean fixed(AbstractVillager v) {
        return v.getPersistentDataContainer().has(fixedKey, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAcquire(VillagerAcquireTradeEvent event) {
        if (!plugin.getConfig().getBoolean("rules.trade-filter", true)) return;
        if (fixed(event.getEntity()) || place(event.getEntity()) == null) return;
        if (banned(event.getRecipe().getResult())) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- applying prices

    /** Sets the villager's trades to this place's current prices (from the base prices it was born with). */
    public void apply(AbstractVillager v) {
        String place = place(v);
        if (place == null) return;
        List<MerchantRecipe> recipes = new ArrayList<>(v.getRecipes());
        if (plugin.getConfig().getBoolean("rules.trade-filter", true) && !fixed(v)) recipes.removeIf(r -> banned(r.getResult()));
        // base amount of the first ingredient of every trade, remembered on the villager
        String stored = v.getPersistentDataContainer().getOrDefault(baseKey, PersistentDataType.STRING, "");
        Map<String, Integer> base = new HashMap<>();
        for (String part : stored.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq > 0) base.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
        }
        StringBuilder sb = new StringBuilder();
        List<MerchantRecipe> out = new ArrayList<>();
        for (MerchantRecipe r : recipes) {
            List<ItemStack> in = new ArrayList<>(r.getIngredients());
            if (in.isEmpty()) {
                out.add(r);
                continue;
            }
            String key = tradeKey(r);
            int b = base.computeIfAbsent(key, k -> in.get(0).getAmount());
            sb.append(key).append('=').append(b).append(';');
            int amount = (int) Math.min(in.get(0).getMaxStackSize(), Math.max(1, Math.round(b * factor(place, r))));
            ItemStack first = in.get(0).clone();
            first.setAmount(amount);
            in.set(0, first);
            MerchantRecipe c = new MerchantRecipe(r.getResult(), r.getUses(), r.getMaxUses(), r.hasExperienceReward(),
                    r.getVillagerExperience(), 0f, 0, 0);
            c.setIngredients(in);
            out.add(c);
        }
        v.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, sb.toString());
        v.setRecipes(out);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof AbstractVillager v) || place(v) == null) return;
        // no hero-of-the-village discount either: one price for everyone
        event.getPlayer().removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        if (v instanceof Villager vil && vil.getProfession() == Villager.Profession.NONE) return;
        apply(v);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        AbstractVillager v = event.getVillager();
        String place = place(v);
        if (place == null) return;
        String key = tradeKey(event.getTrade());
        double[] p = pressure.computeIfAbsent(place, k -> new HashMap<>()).computeIfAbsent(key, k -> new double[]{0, System.currentTimeMillis()});
        p[0] = current(p) + 1;
        p[1] = System.currentTimeMillis();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (v.isValid()) apply(v);
        });
    }
}
