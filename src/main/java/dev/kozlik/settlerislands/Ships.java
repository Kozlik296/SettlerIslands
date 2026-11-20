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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
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
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import io.papermc.paper.entity.TeleportFlag;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sailing ships made of real blocks. A crafted "Sloop" item placed on water (or next to a pier)
 * builds the ship; you walk around on its deck and use its chests. Right-click the helm to take the
 * wheel: W sets sail, S stops, A and D turn the ship (the wheel turns with it), sneak leaves the
 * helm. Everyone and everything on board moves with the ship. Sneak + right-click the helm (owner,
 * with empty chests) packs the ship back into an item. Ships show on the world map.
 */
public final class Ships implements Listener {

    private static final String TEMPLATE = "ship_sloop";

    /** One ship: its template corner (ox, oy, oz) and turn. oy is the keel layer (water surface - 1). */
    private static final class Ship {
        final UUID id;
        final UUID owner;
        final String world;
        final String ownerName;
        int ox, oy, oz, rot;          // anchored pose
        Sailing sail;                 // set while the ship sails as a model

                Ship(UUID id, UUID owner, String ownerName, String world, int ox, int oy, int oz, int rot) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
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
        Block water = p.getTargetBlockExact(10, FluidCollisionMode.ALWAYS);
        if (water == null || water.getType() != Material.WATER) {
            msg(p, "Aim at the water to launch the ship.");
            return;
        }
        while (water.getRelative(0, 1, 0).getType() == Material.WATER) water = water.getRelative(0, 1, 0);
        int want = Template.rotationFacing(-Math.sin(Math.toRadians(p.getYaw())), Math.cos(Math.toRadians(p.getYaw())));
        World w = water.getWorld();
        Template t = tp();
        // the nearest spot around the aimed water where the whole ship fits (so it can be launched beside a pier)
        int[] spot = null;
        search:
        for (int r = 0; r <= 20; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int turn : new int[]{0, 2, 1, 3}) {
                        int rot = (want + turn) & 3;
                        int sx = t.sizeX(rot), sz = t.sizeZ(rot);
                        int ox = water.getX() + dx - sx / 2, oz = water.getZ() + dz - sz / 2, oy = water.getY() - 1;
                        if (fits(w, ox, oy, oz, rot, water.getY())) {
                            spot = new int[]{ox, oy, oz, rot};
                            break search;
                        }
                    }
                }
            }
        }
        if (spot == null) {
            msg(p, "There is no open water here big enough for the ship (about " + t.w + " x " + t.d + " blocks).");
            return;
        }
        Ship s = new Ship(UUID.randomUUID(), p.getUniqueId(), p.getName(), w.getName(), spot[0], spot[1], spot[2], spot[3]);
        build(w, s, null);
        ships.put(s.id, s);
        indexShip(s, true);
        save();
        // cargo packed into the item (only when a ship could not anchor) goes back into its chests
        List<byte[]> packed = event.getItem().getItemMeta().getPersistentDataContainer().get(cargoKey(), PersistentDataType.LIST.byteArrays());
        if (packed != null && !packed.isEmpty()) {
            List<ItemStack> items = new ArrayList<>();
            for (byte[] b : packed) items.add(ItemStack.deserializeBytes(b));
            for (int[] c : cells(s.ox, s.oy, s.oz, s.rot)) {
                if (c[6] < 2 || items.isEmpty()) continue;
                if (w.getBlockAt(c[3], c[4], c[5]).getState() instanceof Container box) {
                    var left = box.getInventory().addItem(items.toArray(new ItemStack[0]));
                    items = new ArrayList<>(left.values());
                }
            }
            for (ItemStack it : items) w.dropItem(p.getLocation(), it);
        }
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) event.getItem().setAmount(event.getItem().getAmount() - 1);
        p.sendMessage(Component.text("Ship launched! Right-click the helm to set sail: W/S speed, A/D steer, Space drops anchor.", NamedTextColor.GREEN));
    }

    private boolean fits(World w, int ox, int oy, int oz, int rot, int waterTop) {
        for (int[] c : cells(ox, oy, oz, rot)) {
            if (!w.isChunkLoaded(c[3] >> 4, c[5] >> 4)) return false;
            if (index.containsKey(key(w.getName(), c[3], c[4], c[5]))) return false;
            if (!freeForHull(w.getBlockAt(c[3], c[4], c[5]), waterTop)) return false;
        }
        return true;
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
                side.line(1, Component.text("right-click to"));
                side.line(2, Component.text("take the wheel"));
                side.line(3, Component.text("sneak: pack up", NamedTextColor.DARK_GRAY));
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }
        // join the rails (blocks were placed without updates)
        for (int[] c : cells(s.ox, s.oy, s.oz, s.rot)) {
            if (c[6] < 2) continue;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Fence fence) {
                for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                        org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST}) {
                    BlockData n = b.getRelative(f).getBlockData();
                    boolean join = n.getMaterial().isOccluding() || n instanceof org.bukkit.block.data.type.Fence
                            || n instanceof org.bukkit.block.data.type.Gate;
                    fence.setFace(f, join && !(n instanceof org.bukkit.block.data.type.Fence nf && nf.getMaterial().name().contains("NETHER") != fence.getMaterial().name().contains("NETHER")));
                }
                b.setBlockData(fence, false);
            }
        }
    }

    private static int cellId(int[] c) {
        return (c[1] * 64 + c[2]) * 64 + c[0];
    }

    // ---------------------------------------------------------------- helm

    // ================================================================ sailing
    //
    // Anchored, the ship is real blocks. Setting sail turns it into a smooth model (block displays
    // riding one root entity) that moves and turns freely with simple boat physics; everyone who was
    // on deck sits in a seat. Dropping anchor (space) turns it back into blocks at the nearest spot.

    /** Seats in template coordinates (the first is the captain's, behind the wheel). */
    private static final double[][] SEATS = {{3.5, 3.0, 1.5}, {1.5, 2.0, 8.5}, {5.5, 2.0, 8.5}, {3.5, 2.0, 11.0}};
    private static final double WHEEL_X = 3.5, WHEEL_Y = 4.35, WHEEL_Z = 2.95;

    /** State of a ship while it sails. */
    private static final class Sailing {
        double x, z, yaw, speed;       // centre of the ship (world), heading (Minecraft yaw), blocks per tick
        int waterTop;
        UUID root;
        final List<UUID> parts = new ArrayList<>();       // one display per block, same order as partCells
        final List<int[]> partCells = new ArrayList<>();   // template x, y, z, palette index
        final List<UUID> wheel = new ArrayList<>();
        final UUID[] seats = new UUID[SEATS.length];
        final Map<Integer, UUID> chestHandles = new HashMap<>();   // cell id -> interaction entity
        final Map<Integer, org.bukkit.inventory.Inventory> cargo = new HashMap<>();
        UUID captain;
        double shownYaw = Double.NaN;
        float wheelSpin;
        boolean lastJump;
    }

    /** Holder so a cargo window knows which ship and chest it belongs to. */
    private static final class CargoHolder implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv;

        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return inv;
        }
    }

    private BoundingBox box(Ship s) {
        Template t = tp();
        return new BoundingBox(s.ox, s.oy, s.oz, s.ox + t.sizeX(s.rot), s.oy + t.h + 2, s.oz + t.sizeZ(s.rot));
    }

    /** Set while the plugin itself takes people off the seats (then dismounting is allowed). */
    private boolean releasing;

    private void helm(Player p, Ship s) {
        if (p.isSneaking()) {
            pack(p, s);
            return;
        }
        if (s.sail != null) return;
        setSail(p, s);
    }

    /** Local (template) point -> offset from the ship centre, turned by the heading. */
    private static double[] turn(double tx, double tz, Template t, double yaw) {
        double lx = tx - t.w / 2.0, lz = tz - t.d / 2.0;
        double a = Math.toRadians(yaw);
        // template +z (bow) points along the heading: (x, z) = (-sin yaw, cos yaw)
        double wx = lx * Math.cos(a) - lz * Math.sin(a);
        double wz = lx * Math.sin(a) + lz * Math.cos(a);
        return new double[]{wx, wz};
    }

    private Location at(Sailing sl, World w, double tx, double ty, double tz) {
        double[] o = turn(tx, tz, tp(), sl.yaw);
        return new Location(w, sl.x + o[0], sl.waterTop - 1 + ty, sl.z + o[1]);
    }

    private void setSail(Player captain, Ship s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null) return;
        Template t = tp();
        List<int[]> cs = cells(s.ox, s.oy, s.oz, s.rot);
        Sailing sl = new Sailing();
        sl.waterTop = s.oy + 1;
        sl.x = s.ox + t.sizeX(s.rot) / 2.0;
        sl.z = s.oz + t.sizeZ(s.rot) / 2.0;
        sl.yaw = s.rot * 90.0;
        sl.captain = captain.getUniqueId();
        // who is on deck goes into the seats (the captain behind the wheel)
        List<Player> crew = new ArrayList<>();
        crew.add(captain);
        for (Entity e : w.getNearbyEntities(box(s).expand(0.5))) {
            if (e instanceof Player pl && !pl.equals(captain) && crew.size() < SEATS.length) crew.add(pl);
        }
        // cargo into windows, blocks away
        for (int[] c : cs) {
            if (c[6] < 2) continue;
            if (w.getBlockAt(c[3], c[4], c[5]).getState() instanceof Container box) {
                CargoHolder h = new CargoHolder();
                org.bukkit.inventory.Inventory inv = Bukkit.createInventory(h, 27, Component.text(s.ownerName + "'s ship - cargo"));
                h.inv = inv;
                ItemStack[] items = box.getInventory().getContents();
                for (int i = 0; i < items.length && i < 27; i++) if (items[i] != null) inv.setItem(i, items[i].clone());
                sl.cargo.put(cellId(c), inv);
            }
        }
        indexShip(s, false);
        clear(w, s, cs);
        s.sail = sl;
        // the model: one display per block, all riding one root
        Location rootLoc = new Location(w, sl.x, sl.waterTop - 1, sl.z);
        org.bukkit.entity.ItemDisplay root = w.spawn(rootLoc, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(2);
        });
        sl.root = root.getUniqueId();
        for (int y = 0; y < t.h; y++)
            for (int z = 0; z < t.d; z++)
                for (int x = 0; x < t.w; x++) {
                    int idx = t.paletteIndex(x, y, z);
                    if (idx < 2) continue;
                    BlockData data = t.data(idx, 0);
                    if (data instanceof org.bukkit.block.data.type.WallSign) continue;     // the helm sign stays behind
                    int[] cell = {x, y, z, idx};
                    BlockDisplay d = w.spawn(rootLoc, BlockDisplay.class, e -> {
                        e.setBlock(joinedFence(t, cell, data));
                        e.setPersistent(false);
                        e.setInterpolationDuration(2);
                        e.setTransformation(partTransform(t, cell, sl.yaw));
                    });
                    root.addPassenger(d);
                    sl.parts.add(d.getUniqueId());
                    sl.partCells.add(cell);
                }
        for (int i = 0; i < 1 + SPOKES + RIM; i++) {
            int part = i;
            BlockDisplay d = w.spawn(rootLoc, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(part == 0 ? Material.DARK_OAK_PLANKS : part <= SPOKES ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_PLANKS));
                e.setPersistent(false);
                e.setInterpolationDuration(2);
                e.setTransformation(wheelTransform(t, part, sl.yaw, 0));
            });
            root.addPassenger(d);
            sl.wheel.add(d.getUniqueId());
        }
        sl.shownYaw = sl.yaw;
        // seats and chest handles (moved every tick)
        for (int i = 0; i < SEATS.length; i++) {
            Location l = at(sl, w, SEATS[i][0], SEATS[i][1], SEATS[i][2]);
            l.setYaw((float) sl.yaw);
            ArmorStand seat = w.spawn(l, ArmorStand.class, a -> {
                a.setInvisible(true);
                a.setMarker(true);
                a.setGravity(false);
                a.setInvulnerable(true);
                a.setPersistent(false);
            });
            sl.seats[i] = seat.getUniqueId();
        }
        for (int i = 0; i < crew.size(); i++) java.util.Objects.requireNonNull(Bukkit.getEntity(sl.seats[i])).addPassenger(crew.get(i));
        for (int[] c : cs) {
            if (!sl.cargo.containsKey(cellId(c))) continue;
            Location l = at(sl, w, c[0] + 0.5, c[1], c[2] + 0.5);
            org.bukkit.entity.Interaction handle = w.spawn(l, org.bukkit.entity.Interaction.class, it -> {
                it.setInteractionWidth(1.0f);
                it.setInteractionHeight(1.0f);
                it.setPersistent(false);
            });
            sl.chestHandles.put(cellId(c), handle.getUniqueId());
        }
        captain.sendActionBar(Component.text("W/S: speed   A/D: steer   Space: drop anchor (when slow)", NamedTextColor.GOLD));
        for (Player pl : crew) if (!pl.equals(captain)) pl.sendActionBar(Component.text("All aboard! Right-click a chest for the cargo.", NamedTextColor.GOLD));
    }

    /** Fences in the model are joined like the placed ones (connections are fixed per block data). */
    private static BlockData joinedFence(Template t, int[] cell, BlockData data) {
        if (!(data instanceof org.bukkit.block.data.type.Fence fence)) return data;
        fence = (org.bukkit.block.data.type.Fence) fence.clone();
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        org.bukkit.block.BlockFace[] faces = {org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST};
        for (int i = 0; i < 4; i++) {
            int nx = cell[0] + dirs[i][0], nz = cell[2] + dirs[i][1];
            boolean join = false;
            if (nx >= 0 && nz >= 0 && nx < t.w && nz < t.d) {
                int idx = t.paletteIndex(nx, cell[1], nz);
                if (idx >= 2) {
                    BlockData n = t.data(idx, 0);
                    join = n instanceof org.bukkit.block.data.type.Fence || n.getMaterial().isOccluding();
                }
            }
            fence.setFace(faces[i], join);
        }
        return fence;
    }

    /** Where a block of the model sits relative to the root, for a heading. */
    private static Transformation partTransform(Template t, int[] cell, double yaw) {
        Quaternionf q = new Quaternionf(new AxisAngle4f((float) Math.toRadians(-yaw), 0, 1, 0));
        Vector3f corner = new Vector3f((float) (cell[0] - t.w / 2.0), cell[1], (float) (cell[2] - t.d / 2.0)).rotate(q);
        return new Transformation(corner, q, new Vector3f(1, 1, 1), new Quaternionf());
    }

    private static Transformation wheelTransform(Template t, int part, double yaw, float spin) {
        Transformation local = wheelPart(part, spin);
        Quaternionf q = new Quaternionf(new AxisAngle4f((float) Math.toRadians(-yaw), 0, 1, 0));
        Vector3f centre = new Vector3f((float) (WHEEL_X - t.w / 2.0), (float) WHEEL_Y, (float) (WHEEL_Z - t.d / 2.0));
        Vector3f tr = new Vector3f(local.getTranslation()).add(centre).rotate(q);
        Quaternionf rot = new Quaternionf(q).mul(local.getLeftRotation());
        return new Transformation(tr, rot, local.getScale(), new Quaternionf());
    }

    private static final int SPOKES = 4, RIM = 8;

    /** Part 0 is the hub, then the spokes (bars across the whole wheel), then the rim pieces. */
    private static Transformation wheelPart(int part, float spinDeg) {
        float spin = (float) Math.toRadians(spinDeg);
        if (part == 0) {
            float h = 0.22f;
            return new Transformation(new Vector3f(-h / 2, -h / 2, -0.08f), new Quaternionf(), new Vector3f(h, h, 0.16f), new Quaternionf());
        }
        float len, wid = 0.07f, angle;
        Vector3f centre;
        if (part <= SPOKES) {
            len = 1.15f;
            angle = spin + (float) Math.toRadians((part - 1) * 45.0);
            centre = new Vector3f();
        } else {
            int k = part - SPOKES - 1;
            float a = spin + (float) Math.toRadians(22.5 + k * 45.0);
            float r = 0.43f;
            centre = new Vector3f((float) Math.cos(a) * r, (float) Math.sin(a) * r, 0);
            len = 0.36f;
            angle = a + (float) Math.PI / 2;
            wid = 0.09f;
        }
        Quaternionf q = new Quaternionf(new AxisAngle4f(angle, 0, 0, 1));
        Vector3f half = new Vector3f(len / 2, wid / 2, wid / 2).rotate(q);
        return new Transformation(new Vector3f(centre).sub(half), q, new Vector3f(len, wid, wid), new Quaternionf());
    }

    /** Can the ship sit at this centre and heading (every block over water or in the air)? */
    private boolean clearAt(World w, Sailing sl, double cx, double cz, double yaw) {
        Template t = tp();
        for (int[] c : sl.partCells) {
            double[] o = turn(c[0] + 0.5, c[2] + 0.5, t, yaw);
            int bx = (int) Math.floor(cx + o[0]), bz = (int) Math.floor(cz + o[1]), by = sl.waterTop - 1 + c[1];
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            Block b = w.getBlockAt(bx, by, bz);
            Material m = b.getType();
            if (m.isAir() || m == Material.WATER || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP
                    || m == Material.KELP_PLANT || m == Material.BUBBLE_COLUMN || !m.isSolid()) continue;
            return false;
        }
        return w.getWorldBorder().isInside(new Location(w, cx, sl.waterTop, cz));
    }

    private void tick() {
        double max = plugin.getConfig().getDouble("ships.max-speed", 0.32);
        for (Ship s : new ArrayList<>(ships.values())) {
            Sailing sl = s.sail;
            if (sl == null) continue;
            World w = Bukkit.getWorld(s.world);
            Entity root = Bukkit.getEntity(sl.root);
            Player cap = Bukkit.getPlayer(sl.captain);
            if (w == null || root == null) {
                anchor(s, null);
                continue;
            }
            boolean atWheel = cap != null && cap.getVehicle() != null && cap.getVehicle().getUniqueId().equals(sl.seats[0]);
            double turnInput = 0;
            if (atWheel) {
                org.bukkit.Input in = cap.getCurrentInput();
                if (in.isForward()) sl.speed = Math.min(max, sl.speed + 0.006);
                else if (in.isBackward()) sl.speed = Math.max(-max * 0.25, sl.speed - 0.012);
                if (in.isLeft() && !in.isRight()) turnInput = 1;      // A: to port
                if (in.isRight() && !in.isLeft()) turnInput = -1;     // D: to starboard
                if (in.isJump() && !sl.lastJump) {
                    if (Math.abs(sl.speed) > 0.08) cap.sendActionBar(Component.text("Too fast to drop anchor - slow down with S.", NamedTextColor.RED));
                    else anchor(s, cap);
                }
                sl.lastJump = in.isJump();
                if (s.sail == null) continue;
            }
            if (!atWheel || turnInput == 0) sl.speed *= 0.992;                 // the ship drifts and slows by itself
            if (Math.abs(sl.speed) < 0.001) sl.speed = 0;
            // turning: faster when moving, a little even when standing still
            double rate = 0.6 + 2.4 * Math.min(1, Math.abs(sl.speed) / max);
            double nyaw = sl.yaw + turnInput * rate * (sl.speed < 0 ? -1 : 1) * -1;
            double a = Math.toRadians(nyaw);
            double nx = sl.x - Math.sin(a) * sl.speed, nz = sl.z + Math.cos(a) * sl.speed;
            if (clearAt(w, sl, nx, nz, nyaw)) {
                sl.x = nx;
                sl.z = nz;
                sl.yaw = nyaw;
            } else if (clearAt(w, sl, sl.x, sl.z, nyaw)) {
                sl.yaw = nyaw;
                sl.speed = 0;
            } else {
                if (Math.abs(sl.speed) > 0.05 && atWheel) cap.sendActionBar(Component.text("Bump! Something is in the way.", NamedTextColor.RED));
                sl.speed = 0;
            }
            sl.wheelSpin += (float) (turnInput * 6);
            show(w, s, sl, root);
            if (atWheel && sl.speed != 0 && w.getGameTime() % 10 == 0) {
                cap.sendActionBar(Component.text(String.format("Speed %.1f blocks/s   (Space: drop anchor when slow)", Math.abs(sl.speed) * 20), NamedTextColor.AQUA));
            }
        }
    }

    /** Moves the model, seats and chest handles to the current position and heading. */
    private void show(World w, Ship s, Sailing sl, Entity root) {
        Template t = tp();
        root.teleport(new Location(w, sl.x, sl.waterTop - 1, sl.z), TeleportFlag.EntityState.RETAIN_PASSENGERS);
        boolean turned = Double.isNaN(sl.shownYaw) || Math.abs(sl.yaw - sl.shownYaw) > 0.05;
        if (turned) {
            for (int i = 0; i < sl.parts.size(); i++) {
                if (Bukkit.getEntity(sl.parts.get(i)) instanceof BlockDisplay d) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(2);
                    d.setTransformation(partTransform(t, sl.partCells.get(i), sl.yaw));
                }
            }
            sl.shownYaw = sl.yaw;
        }
        for (int i = 0; i < sl.wheel.size(); i++) {
            if (Bukkit.getEntity(sl.wheel.get(i)) instanceof BlockDisplay d) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2);
                d.setTransformation(wheelTransform(t, i, sl.yaw, sl.wheelSpin));
            }
        }
        for (int i = 0; i < SEATS.length; i++) {
            Entity seat = Bukkit.getEntity(sl.seats[i]);
            if (seat == null) continue;
            Location l = at(sl, w, SEATS[i][0], SEATS[i][1], SEATS[i][2]);
            l.setYaw((float) sl.yaw);
            seat.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        for (Map.Entry<Integer, UUID> e : sl.chestHandles.entrySet()) {
            Entity h = Bukkit.getEntity(e.getValue());
            if (h == null) continue;
            int id = e.getKey();
            int x = id % 64, z = (id / 64) % 64, y = id / 4096;
            h.teleport(at(sl, w, x + 0.5, y, z + 0.5));
        }
    }

    /**
     * Drops anchor: the ship becomes blocks again at the nearest free spot (snapped to the grid and to
     * a quarter turn), cargo goes back into its chests and the crew stands up on deck. If there is no
     * room anywhere near, the ship is packed into an item for its owner with the cargo inside.
     */
    private void anchor(Ship s, Player by) {
        Sailing sl = s.sail;
        if (sl == null) return;
        World w = Bukkit.getWorld(s.world);
        Template t = tp();
        List<Player> crew = new ArrayList<>();
        for (UUID id : sl.seats) {
            Entity seat = id == null ? null : Bukkit.getEntity(id);
            if (seat == null) continue;
            for (Entity p : seat.getPassengers()) if (p instanceof Player pl) crew.add(pl);
        }
        int rot = Template.rotationFacing(-Math.sin(Math.toRadians(sl.yaw)), Math.cos(Math.toRadians(sl.yaw)));
        int[] spot = null;
        if (w != null) {
            search:
            for (int r = 0; r <= 12; r++)
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        for (int turn : new int[]{0, 1, 3, 2}) {
                            int rr = (rot + turn) & 3;
                            int ox = (int) Math.round(sl.x - t.sizeX(rr) / 2.0) + dx, oz = (int) Math.round(sl.z - t.sizeZ(rr) / 2.0) + dz;
                            if (fits(w, ox, sl.waterTop - 1, oz, rr, sl.waterTop)) {
                                spot = new int[]{ox, oz, rr};
                                break search;
                            }
                        }
                    }
        }
        s.sail = null;
        removeModel(sl);
        Map<Integer, ItemStack[]> cargo = new HashMap<>();
        for (Map.Entry<Integer, org.bukkit.inventory.Inventory> e : sl.cargo.entrySet()) {
            for (org.bukkit.entity.HumanEntity v : new ArrayList<>(e.getValue().getViewers())) v.closeInventory();
            cargo.put(e.getKey(), e.getValue().getContents());
        }
        if (spot == null || w == null) {
            // nowhere to anchor: give the ship back as an item, cargo inside it
            ships.remove(s.id);
            save();
            ItemStack item = item();
            ItemMeta meta = item.getItemMeta();
            List<byte[]> all = new ArrayList<>();
            for (ItemStack[] arr : cargo.values()) for (ItemStack it : arr) if (it != null && !it.getType().isAir()) all.add(it.serializeAsBytes());
            meta.getPersistentDataContainer().set(cargoKey(), PersistentDataType.LIST.byteArrays(), all);
            item.setItemMeta(meta);
            Player owner = Bukkit.getPlayer(s.owner);
            Player to = by != null ? by : owner;
            if (to != null) {
                to.getInventory().addItem(item).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
                to.sendMessage(Component.text("No room to anchor here - the ship was packed up with its cargo inside.", NamedTextColor.YELLOW));
            }
            return;
        }
        s.ox = spot[0];
        s.oz = spot[1];
        s.rot = spot[2];
        build(w, s, cargo);
        indexShip(s, true);
        save();
        // the crew stands up where they sat
        for (int i = 0; i < crew.size(); i++) {
            double[] seat = SEATS[Math.min(i, SEATS.length - 1)];
            int[] off = t.offset((int) seat[0], (int) seat[2], s.rot);
            crew.get(i).teleport(new Location(w, s.ox + off[0] + 0.5, s.oy + seat[1] + 0.05, s.oz + off[1] + 0.5,
                    crew.get(i).getLocation().getYaw(), crew.get(i).getLocation().getPitch()));
        }
        if (by != null) by.sendActionBar(Component.text("Anchor dropped. Right-click the helm to sail again.", NamedTextColor.GREEN));
    }

    private void removeModel(Sailing sl) {
        for (UUID id : sl.seats) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) {
                releasing = true;
                e.eject();
                releasing = false;
                e.remove();
            }
        }
        List<UUID> all = new ArrayList<>(sl.parts);
        all.addAll(sl.wheel);
        all.addAll(sl.chestHandles.values());
        all.add(sl.root);
        for (UUID id : all) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    private NamespacedKey cargoKey() {
        return new NamespacedKey(plugin, "ship_cargo");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (releasing || (event.getEntity() instanceof Player dead && (dead.isDead() || !dead.isOnline()))) return;
        for (Ship s : ships.values()) {
            if (s.sail == null) continue;
            for (UUID id : s.sail.seats) {
                if (id != null && id.equals(event.getDismounted().getUniqueId()) && event.getEntity() instanceof Player p) {
                    // nobody falls off a sailing ship by accident: drop anchor first
                    event.setCancelled(true);
                    p.sendActionBar(Component.text("The ship is sailing - the captain drops anchor with Space.", NamedTextColor.YELLOW));
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Ship s : new ArrayList<>(ships.values())) {
            if (s.sail == null) continue;
            if (event.getPlayer().getUniqueId().equals(s.sail.captain)) anchor(s, null);
            else for (UUID id : s.sail.seats) {
                Entity seat = id == null ? null : Bukkit.getEntity(id);
                if (seat != null && seat.getPassengers().contains(event.getPlayer())) {
                    releasing = true;
                    seat.removePassenger(event.getPlayer());
                    releasing = false;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHandle(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Interaction it)) return;
        for (Ship s : ships.values()) {
            if (s.sail == null) continue;
            for (Map.Entry<Integer, UUID> e : s.sail.chestHandles.entrySet()) {
                if (e.getValue().equals(it.getUniqueId())) {
                    event.setCancelled(true);
                    event.getPlayer().openInventory(s.sail.cargo.get(e.getKey()));
                    return;
                }
            }
        }
    }

    /** Drops anchor everywhere (on shutdown). */
    public void shutdown() {
        for (Ship s : new ArrayList<>(ships.values())) if (s.sail != null) anchor(s, null);
        save();
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

    /** Ships in a world for the map: {x, z} and "Name's ship". */
    public List<Map.Entry<double[], String>> markers(World w) {
        List<Map.Entry<double[], String>> out = new ArrayList<>();
        Template t = tp();
        for (Ship s : ships.values()) {
            if (!s.world.equals(w.getName())) continue;
            double[] at = s.sail != null ? new double[]{s.sail.x, s.sail.z} : new double[]{s.ox + t.sizeX(s.rot) / 2.0, s.oz + t.sizeZ(s.rot) / 2.0};
            out.add(Map.entry(at, s.ownerName + "'s ship"));
        }
        return out;
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
                Ship s = new Ship(UUID.fromString(id), UUID.fromString(y.getString(id + ".owner")), y.getString(id + ".name", "Someone"), y.getString(id + ".world"),
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
            y.set(id + ".name", s.ownerName);
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
