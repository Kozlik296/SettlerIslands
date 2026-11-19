package dev.kozlik.settlerislands;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The fixed trade lists of the settlement villagers, read from trades.yml. */
public final class Trades {

    public record Trader(String id, Villager.Profession profession, List<MerchantRecipe> recipes) {
    }

    private static final Map<String, String> BEDS = new HashMap<>();          // "zone/colour" -> trader id
    private static final Map<String, Trader> TRADERS = new HashMap<>();

    private Trades() {
    }

    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "trades.yml");
        if (!file.exists()) plugin.saveResource("trades.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        BEDS.clear();
        TRADERS.clear();
        int sellUses = cfg.getInt("uses.sell", 8), buyUses = cfg.getInt("uses.buy", 12);
        ConfigurationSection st = cfg.getConfigurationSection("settlements");
        if (st != null) {
            for (String zone : st.getKeys(false)) {
                ConfigurationSection z = st.getConfigurationSection(zone);
                if (z == null) continue;
                for (String colour : z.getKeys(false)) BEDS.put(zone + "/" + colour, z.getString(colour));
            }
        }
        ConfigurationSection tr = cfg.getConfigurationSection("traders");
        if (tr == null) return;
        for (String id : tr.getKeys(false)) {
            String profName = tr.getString(id + ".profession", "none").toLowerCase(Locale.ROOT);
            Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(profName));
            if (prof == null) {
                plugin.getLogger().warning("trades.yml: unknown profession '" + profName + "' for " + id);
                prof = Villager.Profession.MASON;
            }
            List<MerchantRecipe> recipes = new ArrayList<>();
            for (String line : tr.getStringList(id + ".trades")) {
                String[] p = line.trim().split("\\s+");
                try {
                    Material item = Material.valueOf(p[1].toUpperCase(Locale.ROOT));
                    int amount = Integer.parseInt(p[2]), emeralds = Integer.parseInt(p[3]);
                    MerchantRecipe r;
                    if (p[0].equalsIgnoreCase("sell")) {
                        r = new MerchantRecipe(new ItemStack(item, amount), 0, sellUses, false, 0, 0f);
                        r.addIngredient(new ItemStack(Material.EMERALD, emeralds));
                    } else {
                        r = new MerchantRecipe(new ItemStack(Material.EMERALD, emeralds), 0, buyUses, false, 0, 0f);
                        r.addIngredient(new ItemStack(item, amount));
                    }
                    recipes.add(r);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("trades.yml: can't read trade '" + line + "' of " + id);
                }
            }
            TRADERS.put(id, new Trader(id, prof, recipes));
        }
        plugin.getLogger().info("Loaded " + TRADERS.size() + " settlement traders");
    }

    /** The trader for a "zone/colour" bed key or a trader id. */
    public static Trader get(String key) {
        String id = BEDS.getOrDefault(key, key);
        return TRADERS.get(id);
    }

    /** Fresh copies of the recipes (a villager keeps its own use counters). */
    public static List<MerchantRecipe> copy(Trader t) {
        List<MerchantRecipe> out = new ArrayList<>();
        for (MerchantRecipe r : t.recipes()) {
            MerchantRecipe c = new MerchantRecipe(r.getResult().clone(), 0, r.getMaxUses(), false, 0, 0f);
            for (ItemStack i : r.getIngredients()) c.addIngredient(i.clone());
            out.add(c);
        }
        return out;
    }
}
