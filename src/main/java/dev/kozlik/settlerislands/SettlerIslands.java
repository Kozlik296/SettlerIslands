package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SettlerIslands extends JavaPlugin implements Listener {

    private final Map<Zone, List<OreSpec>> ores = new EnumMap<>(Zone.class);
    private int borderSize = 6000;
    private double glowstonePerChunk = 0.35;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        loadSettings();
    }

    private SetupListener setup;
    private Economy economy;

    @Override
    public void onDisable() {
        if (economy != null) economy.save();
    }


    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        worldMap = new WorldMap(this);
        getServer().getPluginManager().registerEvents(worldMap, this);
        setup = new SetupListener(this);
        getServer().getPluginManager().registerEvents(setup, this);
        getServer().getPluginManager().registerEvents(new SupportRule(this), this);
        getServer().getPluginManager().registerEvents(new GameRules(this), this);
        economy = new Economy(this, setup.tradesKey);
        getServer().getPluginManager().registerEvents(economy, this);
        getServer().getPluginManager().registerEvents(new ChestShops(this), this);
        for (World w : Bukkit.getWorlds()) setupWorld(w);
        NetherAtmosphere atmosphere = new NetherAtmosphere();
        getServer().getScheduler().runTaskTimer(this, atmosphere, 20L, 10L);
        getServer().getPluginManager().registerEvents(new SpawnRules(), this);
    }

    private void loadSettings() {
        reloadConfig();
        Trades.load(this);
        borderSize = getConfig().getInt("border-size", 6000);
        glowstonePerChunk = getConfig().getDouble("nether-glowstone-per-chunk", 0.35);
        ores.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("ores");
        if (sec == null) return;
        for (String zoneName : sec.getKeys(false)) {
            Zone zone;
            try {
                zone = Zone.valueOf(zoneName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Unknown zone in config: " + zoneName);
                continue;
            }
            ConfigurationSection zs = sec.getConfigurationSection(zoneName);
            if (zs == null) continue;
            List<OreSpec> list = new ArrayList<>();
            for (String oreName : zs.getKeys(false)) {
                Material mat = Material.matchMaterial(oreName);
                if (mat == null) {
                    getLogger().warning("Unknown ore in config: " + oreName);
                    continue;
                }
                ConfigurationSection os = zs.getConfigurationSection(oreName);
                if (os == null) continue;
                list.add(OreSpec.of(mat, os.getInt("total"), os.getInt("min-y"), os.getInt("max-y"), os.getInt("vein", 6)));
            }
            ores.put(zone, list);
        }
    }

    public Map<Zone, List<OreSpec>> ores() {
        return ores;
    }

    public int borderSize() {
        return borderSize;
    }

    public double glowstonePerChunk() {
        return glowstonePerChunk;
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new IslandGenerator(this);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        setupWorld(event.getWorld());
    }

    private void setupWorld(World world) {
        if (!(world.getGenerator() instanceof IslandGenerator)) return;
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(borderSize);
        if (worldMap != null) {
            worldMap.restore();
            worldMap.prepare(world);
        }
    }

    private WorldMap worldMap;

    public WorldMap worldMap() {
        return worldMap;
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/si create <name> - make a new island world and go there");
            sender.sendMessage("/si tp <island|zone> - go to an island (meadow, ironstone, islet3, nether...)");
            sender.sendMessage("/si tp village|fortress|harbour - go to a structure");
            sender.sendMessage("/si tp settlement <timberlands|sandbanks|ironstone|highlands|nether> - go to a settlement");
            sender.sendMessage("/si retrade - give the settlement villager you look at its trades from trades.yml again");
            sender.sendMessage("/si where - show the zone and biome you stand in");
            sender.sendMessage("/si stats - land per zone and planned ore totals");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /si create <name>");
                    return true;
                }
                sender.sendMessage("Creating world " + args[1] + " (the first chunks take a moment)...");
                World w = new WorldCreator(args[1]).generator(new IslandGenerator(this)).createWorld();
                if (w == null) {
                    sender.sendMessage("Could not create the world.");
                    return true;
                }
                setupWorld(w);
                if (sender instanceof Player p) p.teleport(w.getSpawnLocation());
                sender.sendMessage("Done. Spawn is on Meadow. Use /si tp <island> to look around.");
            }
            case "tp" -> {
                if (!(sender instanceof Player p)) return true;
                if (!(p.getWorld().getGenerator() instanceof IslandGenerator)) {
                    sender.sendMessage("You are not in an island world. Use /si create <name> first.");
                    return true;
                }
                if (args.length < 2) {
                    StringBuilder sb = new StringBuilder("Islands: ");
                    for (Layout.Island i : Layout.get().islands()) sb.append(i.id).append(' ');
                    sender.sendMessage(sb.toString());
                    return true;
                }
                StructurePlan plan = StructurePlan.get(WorldModel.get(p.getWorld().getSeed()), p.getWorld().getSeed());
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "village" -> {
                        p.teleport(new Location(p.getWorld(), plan.spawnX() + 0.5, plan.spawnY(), plan.spawnZ() + 0.5));
                        return true;
                    }
                    case "fortress" -> {
                        p.teleport(new Location(p.getWorld(), plan.fortress.fx + 0.5, Fortress.DECK + 1, plan.fortress.fz - 40 + 0.5));
                        return true;
                    }
                    case "harbour", "harbor" -> {
                        int[] h = plan.harbour.spot();
                        p.teleport(new Location(p.getWorld(), h[0] + 0.5, Harbour.QUAY + 1, h[1] + 0.5));
                        return true;
                    }
                    case "settlement" -> {
                        String zone = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                        for (Settlement st : plan.settlements) {
                            if (st.zone.equals(zone)) {
                                int[] h = st.spot();
                                p.teleport(new Location(p.getWorld(), h[0] + 0.5, h[1] + 1, h[2] + 0.5));
                                return true;
                            }
                        }
                        sender.sendMessage("Settlements: timberlands sandbanks ironstone highlands nether");
                        return true;
                    }
                    default -> {
                    }
                }
                Layout.Island isl = Layout.get().find(args[1]);
                if (isl == null) {
                    sender.sendMessage("No island called " + args[1]);
                    return true;
                }
                WorldModel m = WorldModel.get(p.getWorld().getSeed());
                int x = (int) isl.cx, z = (int) isl.cz;
                for (int r = 0; r < 600; r += 10) {
                    WorldModel.Sample sm = m.sample(x + r, z);
                    if (sm.land() && sm.zone() == isl.zone) {
                        x += r;
                        break;
                    }
                }
                Location loc = new Location(p.getWorld(), x + 0.5, 0, z + 0.5);
                loc.setY(p.getWorld().getHighestBlockYAt(x, z) + 2);
                p.teleport(loc);
                sender.sendMessage("Teleported to " + isl.id + " (" + isl.zone + ")");
            }
            case "where" -> {
                if (!(sender instanceof Player p)) return true;
                WorldModel m = WorldModel.get(p.getWorld().getSeed());
                int x = p.getLocation().getBlockX(), z = p.getLocation().getBlockZ();
                WorldModel.Sample sm = m.sample(x, z);
                sender.sendMessage("Zone: " + sm.zone() + " (island " + sm.island().id + "), "
                        + (int) Math.abs(sm.s()) + " blocks " + (sm.land() ? "inland" : "out to sea")
                        + ", biome " + p.getLocation().getBlock().getBiome().getKey().getKey());
            }
            case "stats" -> {
                World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                WorldModel m = WorldModel.get(w.getSeed());
                Map<Zone, Integer> counts = m.zoneChunks(borderSize);
                for (Zone zone : Zone.values()) {
                    if (!zone.isLand()) continue;
                    int chunks = counts.getOrDefault(zone, 0);
                    StringBuilder sb = new StringBuilder(zone + ": " + chunks + " chunks (~" + (chunks * 256L) + " blocks of land)");
                    for (OreSpec spec : ores.getOrDefault(zone, List.of())) {
                        sb.append(", ").append(spec.ore().name().toLowerCase(Locale.ROOT)).append(' ').append(spec.total());
                    }
                    sender.sendMessage(sb.toString());
                }
            }
            case "retrade" -> {
                if (!(sender instanceof Player p)) return true;
                if (p.getTargetEntity(8) instanceof org.bukkit.entity.Villager v) {
                    String id = v.getPersistentDataContainer().get(setup.tradesKey, org.bukkit.persistence.PersistentDataType.STRING);
                    Trades.Trader t = id != null ? Trades.get(id) : null;
                    if (t == null) {
                        sender.sendMessage("That villager is not a settlement trader.");
                        return true;
                    }
                    setup.applyTrades(v, t);
                    sender.sendMessage("Trades of " + id + " re-applied.");
                } else sender.sendMessage("Look at a villager.");
            }
            case "reload" -> {
                loadSettings();
                sender.sendMessage("Config reloaded (affects chunks generated from now on).");
            }
            default -> sender.sendMessage("Unknown subcommand. Use /si for help.");
        }
        return true;
    }
}
