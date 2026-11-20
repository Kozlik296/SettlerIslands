package dev.kozlik.settlerislands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sailing ships made of real blocks. A crafted "Sloop" item placed on water builds the ship; you
 * walk around on its deck, use its three chests, and the captain at the helm sails it: right-click
 * the helm sign to set sail, look where you want to go, right-click again to stop. Everyone and
 * everything on board moves with the ship. Sneak + right-click the helm (owner, with empty chests)
 * packs the ship back into an item.
 */
public final class Ships implements Listener {

    private static final String TEMPLATE = "ship_sloop";

    /** One ship: its template corner (ox, oy, oz) and turn. oy is the keel layer (water surface - 1). */
    private static final class Ship {
        final UUID id;
        final UUID owner;
        final String world;
        int ox, oy, oz, rot;
        UUID captain;
        int blockedTicks;

        Ship(UUID id, UUID owner, String world, int ox, int oy, int oz, int rot) {
            this.id = id;
            this.owner = owner;
            this.world = world;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.rot = rot;
        }
    }

    private final SettlerIslands plugin;
    private final NamespacedKey itemKey, recipeKey;
    private final File file;
    private final Map<UUID, Ship> ships = new HashMap<>();
    private final Map<Long, UUID> index = new HashMap<>();     // block position -> ship
    private Template tp;

    public Ships(SettlerIslands plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "ship_item");
        this.recipeKey = new NamespacedKey(plugin, "sloop");
        this.file = new File(plugin.getDataFolder(), "ships.yml");
        load();
        addRecipe();
        int every = Math.max(2, plugin.getConfig().getInt("ships.ticks-per-block", 3));
        Bukkit.getScheduler().runTaskTimer(plugin, this::sail, every, every);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
    }

    private Template tp() {
        if (tp == null) tp = Template.get(TEMPLATE);
        return tp;
    }

    // ---------------------------------------------------------------- item and recipe

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.OAK_CHEST_BOAT);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Sloop", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("A small sailing ship with three chests.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click on open water to launch it.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isShipItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void addRecipe() {
        List<Material> boats = new ArrayList<>();
        for (Material m : Material.values()) {
            String n = m.name();
            if (!m.isLegacy() && (n.endsWith("_BOAT") || n.endsWith("_RAFT")) && !n.contains("CHEST")) boats.add(m);
        }
        ShapedRecipe r = new ShapedRecipe(recipeKey, item());
        r.shape("WWW", "LCL", "BBB");
        r.setIngredient('W', Material.WHITE_WOOL);
        r.setIngredient('L', new RecipeChoice.MaterialChoice(Tag.LOGS));
        r.setIngredient('C', Material.CHEST);
        r.setIngredient('B', new RecipeChoice.MaterialChoice(boats));
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(r);
    }

    // ---------------------------------------------------------------- geometry

    private static long key(String world, int x, int y, int z) {
        return ((long) world.hashCode() << 52) ^ (((long) x & 0x3ffffff) << 26) ^ ((long) z & 0x3ffffff) ^ ((long) (y + 512) << 40);
    }

    /** Every template cell of the ship at a pose: {template x, y, z, world x, y, z, palette index}. */
    private List<int[]> cells(int ox, int oy, int oz, int rot) {
        Template t = tp();
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < t.h; y++)
            for (int z = 0; z < t.d; z++)
                for (int x = 0; x < t.w; x++) {
                    int idx = t.paletteIndex(x, y, z);
                    if (idx == 0) continue;
                    int[] off = t.offset(x, z, rot);
                    out.add(new int[]{x, y, z, ox + off[0], oy + y, oz + off[1], idx});
                }
        return out;
    }

    private void indexShip(Ship s, boolean add) {
        for (int[] c : cells(s.ox, s.oy, s.oz, s.rot)) {
            if (c[6] < 2) continue;
            long k = key(s.world, c[3], c[4], c[5]);
            if (add) index.put(k, s.id);
            else index.remove(k);
        }
    }

    private Ship shipAt(Block b) {
        UUID id = index.get(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        return id == null ? null : ships.get(id);
    }

    private static boolean freeForHull(Block b, int waterTop) {
        Material m = b.getType();
        if (b.getY() > waterTop) return m.isAir();
        return m == Material.WATER || m.isAir() || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                || m == Material.KELP || m == Material.KELP_PLANT || m == Material.BUBBLE_COLUMN;
    }

    private static int[] facing(int rot) {
        return switch (rot & 3) {
            case 0 -> new int[]{0, 1};
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    // ---------------------------------------------------------------- launching

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        // the helm
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getState() instanceof Sign) {
            Ship s = shipAt(event.getClickedBlock());
            if (s != null) {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND) return;
                helm(p, s);
                return;
            }
        }
        if (!isShipItem(event.getItem())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block water = p.getTargetBlockExact(8, FluidCollisionMode.ALWAYS);
        if (water == null || water.getType() != Material.WATER) {
            msg(p, "Aim at open water to launch the ship.");
            return;
        }
        while (water.getRelative(0, 1, 0).getType() == Material.WATER) water = water.getRelative(0, 1, 0);
        int rot = Template.rotationFacing(-Math.sin(Math.toRadians(p.getYaw())), Math.cos(Math.toRadians(p.getYaw())));
        Template t = tp();
        int sx = t.sizeX(rot), sz = t.sizeZ(rot);
        int ox = water.getX() - sx / 2, oz = water.getZ() - sz / 2, oy = water.getY() - 1;
        World w = water.getWorld();
        for (int[] c : cells(ox, oy, oz, rot)) {
            if (!freeForHull(w.getBlockAt(c[3], c[4], c[5]), water.getY())) {
                msg(p, "Not enough open water here (the ship is " + sx + " x " + sz + " blocks).");
                return;
            }
        }
        Ship s = new Ship(UUID.randomUUID(), p.getUniqueId(), w.getName(), ox, oy, oz, rot);
        build(w, s, null);
        ships.put(s.id, s);
        indexShip(s, true);
        save();
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) event.getItem().setAmount(event.getItem().getAmount() - 1);
        p.sendMessage(Component.text("Ship launched! Right-click the helm sign to sail; look where you want to go.", NamedTextColor.GREEN));
    }

    /** Places the ship's blocks; cargo maps template cell -> chest contents to put back. */
    private void build(World w, Ship s, Map<Integer, ItemStack[]> cargo) {
        Template t = tp();
        for (int[] c : cells(s.ox, s.oy, s.oz, s.rot)) {
            if (c[6] < 2) continue;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            BlockData data = t.data(c[6], s.rot);
            b.setBlockData(data, false);
            if (cargo != null && b.getState() instanceof Container box) {
                ItemStack[] items = cargo.get(cellId(c));
                if (items != null) box.getInventory().setContents(items);
            }
            if (b.getState() instanceof Sign sign) {
                var side = sign.getSide(Side.FRONT);
                side.line(0, Component.text("[ Helm ]", NamedTextColor.DARK_BLUE));
                side.line(1, Component.text("right-click:"));
                side.line(2, Component.text("sail / stop"));
                side.line(3, Component.text("sneak: pack up", NamedTextColor.DARK_GRAY));
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }
    }

    private static int cellId(int[] c) {
        return (c[1] * 64 + c[2]) * 64 + c[0];
    }

    // ---------------------------------------------------------------- helm

    private void helm(Player p, Ship s) {
        if (p.isSneaking()) {
            pack(p, s);
            return;
        }
        if (s.captain != null && s.captain.equals(p.getUniqueId())) {
            s.captain = null;
            p.sendActionBar(Component.text("Sails down - the ship stops.", NamedTextColor.YELLOW));
            return;
        }
        s.captain = p.getUniqueId();
        s.blockedTicks = 0;
        p.sendActionBar(Component.text("Sails up! Look where you want to go. Right-click the helm to stop.", NamedTextColor.GREEN));
    }

    private void pack(Player p, Ship s) {
        if (!p.getUniqueId().equals(s.owner) && !p.hasPermission("settlerislands.admin")) {
            msg(p, "Only the owner can pack up this ship.");
            return;
        }
        World w = Bukkit.getWorld(s.world);
        if (w == null) return;
        List<int[]> cs = cells(s.ox, s.oy, s.oz, s.rot);
        for (int[] c : cs) {
            if (c[6] < 2) continue;
            if (w.getBlockAt(c[3], c[4], c[5]).getState() instanceof Container box && !box.getInventory().isEmpty()) {
                msg(p, "Empty the ship's chests before packing it up.");
                return;
            }
        }
        indexShip(s, false);
        clear(w, s, cs);
        ships.remove(s.id);
        save();
        p.getInventory().addItem(item()).values().forEach(left -> w.dropItem(p.getLocation(), left));
        p.sendMessage(Component.text("Ship packed up.", NamedTextColor.GREEN));
    }

    private void clear(World w, Ship s, List<int[]> cs) {
        int waterTop = s.oy + 1;
        for (int[] c : cs) {
            if (c[6] < 2) continue;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            if (b.getState() instanceof Container box) box.getInventory().clear();
            b.setType(c[4] <= waterTop ? Material.WATER : Material.AIR, false);
        }
    }

    // ---------------------------------------------------------------- sailing

    private void sail() {
        for (Ship s : new ArrayList<>(ships.values())) {
            if (s.captain == null) continue;
            Player cap = Bukkit.getPlayer(s.captain);
            World w = Bukkit.getWorld(s.world);
            if (cap == null || w == null || !cap.getWorld().equals(w) || !onBoard(s, cap.getLocation())) {
                s.captain = null;
                if (cap != null) cap.sendActionBar(Component.text("You left the helm - the ship stops.", NamedTextColor.YELLOW));
                continue;
            }
            float yaw = cap.getLocation().getYaw();
            int want = Template.rotationFacing(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)));
            boolean ok;
            if (want != s.rot) {
                int diff = (want - s.rot) & 3;
                ok = move(w, s, 0, 0, diff == 3 ? -1 : 1);
            } else {
                int[] f = facing(s.rot);
                ok = move(w, s, f[0], f[1], 0);
            }
            if (!ok && ++s.blockedTicks == 1) cap.sendActionBar(Component.text("Something is in the way - steer around it.", NamedTextColor.RED));
            if (ok) s.blockedTicks = 0;
        }
    }

    private BoundingBox box(Ship s) {
        Template t = tp();
        return new BoundingBox(s.ox, s.oy, s.oz, s.ox + t.sizeX(s.rot), s.oy + t.h + 2, s.oz + t.sizeZ(s.rot));
    }

    private boolean onBoard(Ship s, Location l) {
        return box(s).expand(0.5).contains(l.toVector());
    }

    /**
     * Moves the ship by (dx, dz) or turns it a quarter clockwise (turn 1) or anticlockwise (-1)
     * about its middle, with everything on board. Returns false if something is in the way.
     */
    private boolean move(World w, Ship s, int dx, int dz, int turn) {
        Template t = tp();
        int nrot = (s.rot + turn) & 3;
        double cx = s.ox + t.sizeX(s.rot) / 2.0, cz = s.oz + t.sizeZ(s.rot) / 2.0;
        int nox = s.ox + dx, noz = s.oz + dz;
        if (turn != 0) {
            nox = (int) Math.round(cx - t.sizeX(nrot) / 2.0);
            noz = (int) Math.round(cz - t.sizeZ(nrot) / 2.0);
        }
        List<int[]> old = cells(s.ox, s.oy, s.oz, s.rot);
        List<int[]> next = cells(nox, s.oy, noz, nrot);
        int waterTop = s.oy + 1;
        for (int[] c : next) {
            if (!w.isChunkLoaded(c[3] >> 4, c[5] >> 4)) return false;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            UUID here = index.get(key(s.world, c[3], c[4], c[5]));
            if (s.id.equals(here)) continue;
            if (!freeForHull(b, waterTop)) return false;
        }
        // remember the cargo and who is on board
        Map<Integer, ItemStack[]> cargo = new HashMap<>();
        for (int[] c : old) {
            if (c[6] < 2) continue;
            if (w.getBlockAt(c[3], c[4], c[5]).getState() instanceof Container box) {
                ItemStack[] items = box.getInventory().getContents();
                for (int i = 0; i < items.length; i++) if (items[i] != null) items[i] = items[i].clone();
                cargo.put(cellId(c), items);
            }
        }
        List<Entity> riders = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(box(s).expand(0.5))) {
            if (e instanceof Hanging || e.isInsideVehicle()) continue;
            riders.add(e);
        }
        indexShip(s, false);
        clear(w, s, old);
        s.ox = nox;
        s.oz = noz;
        s.rot = nrot;
        build(w, s, cargo);
        indexShip(s, true);
        for (Entity e : riders) {
            Location l = e.getLocation();
            if (turn == 0) {
                l.add(dx, 0, dz);
            } else {
                double rx = l.getX() - cx, rz = l.getZ() - cz;
                double nx = turn > 0 ? -rz : rz, nz = turn > 0 ? rx : -rx;
                double ncx = nox + t.sizeX(nrot) / 2.0, ncz = noz + t.sizeZ(nrot) / 2.0;
                l.setX(ncx + nx);
                l.setZ(ncz + nz);            // (the view is not turned: the captain keeps steering by looking)
            }
            e.teleport(l);
        }
        return true;
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (shipAt(event.getBlock()) != null) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("Part of a ship - sneak + right-click its helm to pack it up", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        for (Ship s : ships.values()) {
            if (s.world.equals(b.getWorld().getName()) && box(s).contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(Component.text("You can't build on a ship", NamedTextColor.YELLOW));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> shipAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> shipAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> shipAt(b) != null)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> shipAt(b) != null)) event.setCancelled(true);
    }

    private static void msg(Player p, String s) {
        p.sendMessage(Component.text(s, NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- saving

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            try {
                Ship s = new Ship(UUID.fromString(id), UUID.fromString(y.getString(id + ".owner")), y.getString(id + ".world"),
                        y.getInt(id + ".x"), y.getInt(id + ".y"), y.getInt(id + ".z"), y.getInt(id + ".rot"));
                ships.put(s.id, s);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("ships.yml: can't read ship " + id);
            }
        }
        // the template loads lazily; index once it is available
        Bukkit.getScheduler().runTask(plugin, () -> ships.values().forEach(s -> indexShip(s, true)));
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Ship s : ships.values()) {
            String id = s.id.toString();
            y.set(id + ".owner", s.owner.toString());
            y.set(id + ".world", s.world);
            y.set(id + ".x", s.ox);
            y.set(id + ".y", s.oy);
            y.set(id + ".z", s.oz);
            y.set(id + ".rot", s.rot);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save ships.yml: " + e.getMessage());
        }
    }

    /** Positions change while sailing; save every so often and on shutdown. */
    public void saveNow() {
        save();
    }
}
