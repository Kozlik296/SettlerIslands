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
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sailing ships. At anchor a ship is real blocks: you walk on its deck and use its chests. When the
 * captain takes the helm the ship becomes a model riding an ordinary boat, so it sails exactly like a
 * boat (smooth, any direction, WASD): the captain and one passenger travel seated. Sneaking drops
 * anchor: the ship turns back into blocks where it is, with everything in its chests.
 * A crafted "Sloop" item placed on water (also beside a pier) launches a ship; sneak + right-click
 * the helm at anchor (owner, empty chests) packs it up again. Ships show on the world map.
 */
public final class Ships implements Listener {

    private static final String TEMPLATE = "ship_sloop";
    // where the captain sits, in template coordinates (between the chests at the stern)
    private static final double SEAT_X = 3.5, SEAT_Z = 1.5;
    // the boat is a little behind the captain: a boat's driver sits 0.2 forward of its middle
    private static final double BOAT_X = 3.5, BOAT_Z = SEAT_Z - 0.2;
    // display passengers ride 0.1875 above the boat and 0.6 behind its middle
    private static final double RIDE_Y = 0.1875, RIDE_BACK = 0.6;
    // while sailing, the deck (template layer 1) starts this far below the boat, so the deck top is at seat height
    private static final double DECK_BELOW = 0.45;

    private static final class Ship {
        final UUID id, owner;
        final String ownerName, world;
        int ox, oy, oz, rot;                 // pose at anchor (oy = keel layer = water surface - 1)
        boolean sailing;
        UUID boat, captain;
        final List<UUID> parts = new ArrayList<>();
        final List<UUID> wheel = new ArrayList<>();
        Map<Integer, ItemStack[]> cargo = new HashMap<>();
        float wheelAngle;
        double lastX, lastZ;

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
    private final NamespacedKey itemKey, recipeKey, boatKey;
    private final File file;
    private final Map<UUID, Ship> ships = new HashMap<>();
    private final Map<Long, UUID> index = new HashMap<>();     // block position -> anchored ship
    private Template tp;

    public Ships(SettlerIslands plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "ship_item");
        this.recipeKey = new NamespacedKey(plugin, "sloop");
        this.boatKey = new NamespacedKey(plugin, "ship_boat");
        this.file = new File(plugin.getDataFolder(), "ships.yml");
        addRecipe();
        Bukkit.getScheduler().runTask(plugin, this::load);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
    }

    private Template tp() {
        if (tp == null) tp = Template.get(TEMPLATE);
        return tp;
    }

    // ================================================================ item and recipe

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.OAK_CHEST_BOAT);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Sloop", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("A small sailing ship with three chests.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click the water to launch it.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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

    // ================================================================ geometry (at anchor)

    private static long key(String world, int x, int y, int z) {
        return ((long) world.hashCode() << 52) ^ (((long) x & 0x3ffffff) << 26) ^ ((long) z & 0x3ffffff) ^ ((long) (y + 512) << 40);
    }

    /** Every template cell at a pose: {template x, y, z, world x, y, z, palette index}. */
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

    private static int cellId(int[] c) {
        return (c[1] * 64 + c[2]) * 64 + c[0];
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

    private boolean fits(World w, int ox, int oy, int oz, int rot, int waterTop) {
        for (int[] c : cells(ox, oy, oz, rot)) {
            if (!w.isChunkLoaded(c[3] >> 4, c[5] >> 4)) return false;
            if (index.containsKey(key(w.getName(), c[3], c[4], c[5]))) return false;
            if (!freeForHull(w.getBlockAt(c[3], c[4], c[5]), waterTop)) return false;
        }
        return true;
    }

    /** A point in template coordinates (fractions allowed) at a pose, as world x/z. */
    private double[] point(int ox, int oz, int rot, double tx, double tz) {
        Template t = tp();
        double x = tx, z = tz;
        int w = t.w, d = t.d;
        for (int i = 0; i < (rot & 3); i++) {     // the same turn as Template.offset, for points
            double nx = d - z, nz = x;
            x = nx;
            z = nz;
            int tmp = w;
            w = d;
            d = tmp;
        }
        return new double[]{ox + x, oz + z};
    }

    /** The nearest pose around (cx, cz) where the ship fits, trying the wanted turn first; null if none. */
    private int[] findSpot(World w, double cx, double cz, int waterTop, int want, int radius, double anchorTx, double anchorTz) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int turn : new int[]{0, 1, 3, 2}) {
                        int rot = (want + turn) & 3;
                        double[] p0 = point(0, 0, rot, anchorTx, anchorTz);
                        int ox = (int) Math.round(cx - p0[0]) + dx, oz = (int) Math.round(cz - p0[1]) + dz;
                        if (fits(w, ox, waterTop - 1, oz, rot, waterTop)) return new int[]{ox, waterTop - 1, oz, rot};
                    }
                }
            }
        }
        return null;
    }

    private static int rotationOf(float yaw) {
        return Template.rotationFacing(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)));
    }

    // ================================================================ building at anchor

    private void build(World w, Ship s) {
        Template t = tp();
        List<int[]> cs = cells(s.ox, s.oy, s.oz, s.rot);
        for (int[] c : cs) {
            if (c[6] < 2) continue;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            b.setBlockData(t.data(c[6], s.rot), false);
            if (b.getState() instanceof Container box) {
                ItemStack[] items = s.cargo.get(cellId(c));
                if (items != null) box.getInventory().setContents(items);
            }
            if (b.getState() instanceof Sign sign) {
                var side = sign.getSide(Side.FRONT);
                side.line(0, Component.text("[ Helm ]", NamedTextColor.DARK_BLUE));
                side.line(1, Component.text("right-click"));
                side.line(2, Component.text("to set sail"));
                side.line(3, Component.text("sneak: pack up", NamedTextColor.DARK_GRAY));
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }
        s.cargo = new HashMap<>();
        // join the rails (the blocks were placed without updates)
        for (int[] c : cs) {
            if (c[6] < 2) continue;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Fence fence) {
                for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                    BlockData n = b.getRelative(f).getBlockData();
                    fence.setFace(f, n.getMaterial().isOccluding() || n instanceof org.bukkit.block.data.type.Fence
                            || n instanceof org.bukkit.block.data.type.Gate);
                }
                b.setBlockData(fence, false);
            }
        }
    }

    /** Takes the blocks away (water comes back below the surface), keeping what is in the chests. */
    private void clear(World w, Ship s) {
        int waterTop = s.oy + 1;
        for (int[] c : cells(s.ox, s.oy, s.oz, s.rot)) {
            if (c[6] < 2) continue;
            Block b = w.getBlockAt(c[3], c[4], c[5]);
            if (b.getState() instanceof Container box) {
                ItemStack[] items = box.getInventory().getContents();
                for (int i = 0; i < items.length; i++) if (items[i] != null) items[i] = items[i].clone();
                s.cargo.put(cellId(c), items);
                box.getInventory().clear();
            }
            b.setType(c[4] <= waterTop ? Material.WATER : Material.AIR, false);
        }
    }

    private BoundingBox box(Ship s) {
        Template t = tp();
        return new BoundingBox(s.ox, s.oy, s.oz, s.ox + t.sizeX(s.rot), s.oy + t.h + 2, s.oz + t.sizeZ(s.rot));
    }

    // ================================================================ launching and the helm

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getState() instanceof Sign) {
            Ship s = shipAt(event.getClickedBlock());
            if (s != null) {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND) return;
                if (p.isSneaking()) pack(p, s);
                else setSail(p, s);
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
        Template t = tp();
        int[] spot = findSpot(water.getWorld(), water.getX() + 0.5, water.getZ() + 0.5, water.getY(), rotationOf(p.getYaw()), 20,
                t.w / 2.0, t.d / 2.0);
        if (spot == null) {
            msg(p, "There is no open water here big enough for the ship (about " + t.w + " x " + t.d + " blocks).");
            return;
        }
        Ship s = new Ship(UUID.randomUUID(), p.getUniqueId(), p.getName(), water.getWorld().getName(), spot[0], spot[1], spot[2], spot[3]);
        build(water.getWorld(), s);
        ships.put(s.id, s);
        indexShip(s, true);
        save();
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) event.getItem().setAmount(event.getItem().getAmount() - 1);
        p.sendMessage(Component.text("Ship launched! Right-click the helm to set sail; sneak to drop anchor.", NamedTextColor.GREEN));
    }

    /** At anchor -> sailing: the blocks become a model on a boat, the captain takes the helm. */
    private void setSail(Player captain, Ship s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || s.sailing) return;
        // one passenger can come along: the nearest other player on deck
        Player mate = null;
        double best = 99;
        for (Entity e : w.getNearbyEntities(box(s))) {
            if (e instanceof Player other && !other.equals(captain) && other.getVehicle() == null) {
                double d = other.getLocation().distanceSquared(captain.getLocation());
                if (d < best) {
                    best = d;
                    mate = other;
                }
            }
        }
        float yaw = s.rot * 90f;
        double[] bp = point(s.ox, s.oz, s.rot, BOAT_X, BOAT_Z);
        Location boatLoc = new Location(w, bp[0], s.oy + 1.9, bp[1], yaw, 0);
        indexShip(s, false);
        clear(w, s);
        Entity boat = w.spawnEntity(boatLoc, EntityType.OAK_BOAT);
        boat.setInvulnerable(true);
        boat.setPersistent(false);
        boat.getPersistentDataContainer().set(boatKey, PersistentDataType.STRING, s.id.toString());
        boat.addPassenger(captain);                 // the driver has to be the first passenger
        if (mate != null) boat.addPassenger(mate);
        s.boat = boat.getUniqueId();
        s.captain = captain.getUniqueId();
        s.sailing = true;
        s.lastX = bp[0];
        s.lastZ = bp[1];
        spawnModel(w, s, boat, boatLoc);
        save();
        captain.sendActionBar(Component.text("Sails up! Steer with W A S D like a boat. Sneak to drop anchor.", NamedTextColor.GOLD));
    }

    /** The ship's blocks as displays riding the boat, plus the wheel. */
    private void spawnModel(World w, Ship s, Entity boat, Location at) {
        Template t = tp();
        for (int y = 0; y < t.h; y++)
            for (int z = 0; z < t.d; z++)
                for (int x = 0; x < t.w; x++) {
                    int idx = t.paletteIndex(x, y, z);
                    if (idx < 2) continue;
                    BlockData data = t.data(idx, 0);
                    Vector3f tr = new Vector3f((float) (x - BOAT_X), (float) (y - 1 - DECK_BELOW - RIDE_Y), (float) (z - BOAT_Z + RIDE_BACK));
                    BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
                        e.setBlock(data);
                        e.setPersistent(false);
                        e.setTransformation(new Transformation(tr, new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
                    });
                    boat.addPassenger(d);
                    s.parts.add(d.getUniqueId());
                }
        for (int i = 0; i < 1 + SPOKES + RIM; i++) {
            int part = i;
            BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(part == 0 ? Material.DARK_OAK_PLANKS : part <= SPOKES ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_PLANKS));
                e.setPersistent(false);
                e.setTransformation(wheelPart(part, s.wheelAngle));
            });
            boat.addPassenger(d);
            s.parts.add(d.getUniqueId());
            s.wheel.add(d.getUniqueId());
        }
    }

    // the wheel sits on the helm post (template 3.5, 3.4, 2.95), facing the captain
    private static final int SPOKES = 4, RIM = 8;

    private static Transformation wheelPart(int part, float spinDeg) {
        Vector3f base = new Vector3f((float) (3.5 - BOAT_X), (float) (3.4 - 1 - DECK_BELOW - RIDE_Y), (float) (2.95 - BOAT_Z + RIDE_BACK));
        float spin = (float) Math.toRadians(spinDeg);
        if (part == 0) {
            float h = 0.22f;
            return new Transformation(new Vector3f(base).add(-h / 2, -h / 2, -0.08f), new Quaternionf(), new Vector3f(h, h, 0.16f), new Quaternionf());
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
        return new Transformation(new Vector3f(base).add(centre).sub(half), q, new Vector3f(len, wid, wid), new Quaternionf());
    }

    /** Sailing -> at anchor, where the ship is now. Returns false if there is no room. */
    private boolean dropAnchor(Ship s, int radius) {
        Entity boat = s.boat == null ? null : Bukkit.getEntity(s.boat);
        World w = Bukkit.getWorld(s.world);
        if (w == null) return false;
        double bx = boat != null ? boat.getLocation().getX() : s.lastX, bz = boat != null ? boat.getLocation().getZ() : s.lastZ;
        float yaw = boat != null ? boat.getLocation().getYaw() : s.rot * 90f;
        int waterTop = waterTop(w, bx, bz, boat != null ? boat.getLocation().getY() : s.oy + 2);
        if (waterTop == Integer.MIN_VALUE) return false;
        int[] spot = findSpot(w, bx, bz, waterTop, rotationOf(yaw), radius, BOAT_X, BOAT_Z);
        if (spot == null) return false;
        List<Entity> riders = boat != null ? new ArrayList<>(boat.getPassengers()) : List.of();
        removeModel(s);
        s.ox = spot[0];
        s.oy = spot[1];
        s.oz = spot[2];
        s.rot = spot[3];
        s.sailing = false;
        build(w, s);
        indexShip(s, true);
        // the crew stands on the deck again
        int n = 0;
        for (Entity e : riders) {
            if (!(e instanceof Player p)) continue;
            double[] at = point(s.ox, s.oz, s.rot, SEAT_X, SEAT_Z + n * 3);
            p.teleport(new Location(w, at[0], s.oy + 2, at[1], s.rot * 90f, 0));
            n++;
        }
        save();
        return true;
    }

    private static int waterTop(World w, double x, double z, double fromY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = (int) Math.floor(fromY) + 1; y > fromY - 4; y--) {
            if (w.getBlockAt(bx, y, bz).getType() == Material.WATER) return y;
        }
        return Integer.MIN_VALUE;
    }

    private void removeModel(Ship s) {
        Entity boat = s.boat == null ? null : Bukkit.getEntity(s.boat);
        if (boat != null) {
            for (Entity e : new ArrayList<>(boat.getPassengers())) boat.removePassenger(e);
            boat.remove();
        }
        for (UUID id : s.parts) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        s.parts.clear();
        s.wheel.clear();
        s.boat = null;
        s.captain = null;
    }

    // ================================================================ while sailing

    private void tick() {
        for (Ship s : ships.values()) {
            if (!s.sailing) continue;
            Entity boat = s.boat == null ? null : Bukkit.getEntity(s.boat);
            if (boat == null) continue;
            s.lastX = boat.getLocation().getX();
            s.lastZ = boat.getLocation().getZ();
            // the wheel turns while the captain steers
            Player cap = s.captain == null ? null : Bukkit.getPlayer(s.captain);
            if (cap == null) continue;
            org.bukkit.Input in = cap.getCurrentInput();
            int dir = in.isRight() && !in.isLeft() ? 1 : in.isLeft() && !in.isRight() ? -1 : 0;
            if (dir == 0) continue;
            s.wheelAngle += dir * 9f;
            for (int i = 0; i < s.wheel.size(); i++) {
                if (Bukkit.getEntity(s.wheel.get(i)) instanceof BlockDisplay d) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(2);
                    d.setTransformation(wheelPart(i, s.wheelAngle));
                }
            }
        }
    }

    private Ship shipOfBoat(Entity e) {
        String id = e.getPersistentDataContainer().get(boatKey, PersistentDataType.STRING);
        return id == null ? null : ships.get(UUID.fromString(id));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        Ship s = shipOfBoat(event.getDismounted());
        if (s == null || !s.sailing || !(event.getEntity() instanceof Player p)) return;
        if (!p.getUniqueId().equals(s.captain)) return;          // a passenger may jump off
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (dropAnchor(s, 4)) p.sendActionBar(Component.text("Anchor dropped.", NamedTextColor.GREEN));
            else p.sendActionBar(Component.text("No room to drop anchor here - sail into open water.", NamedTextColor.RED));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Ship s : ships.values()) {
            if (s.sailing && event.getPlayer().getUniqueId().equals(s.captain) && !dropAnchor(s, 16)) {
                plugin.getLogger().warning("Ship of " + s.ownerName + " could not drop anchor; it will at the next start.");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoatDamage(VehicleDamageEvent event) {
        if (shipOfBoat(event.getVehicle()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoatDestroy(VehicleDestroyEvent event) {
        if (shipOfBoat(event.getVehicle()) != null) event.setCancelled(true);
    }

    /** On shutdown every sailing ship drops anchor (as near as it fits). */
    public void shutdown() {
        for (Ship s : ships.values()) if (s.sailing) dropAnchor(s, 24);
        save();
    }

    // ================================================================ packing up

    private void pack(Player p, Ship s) {
        if (!p.getUniqueId().equals(s.owner) && !p.hasPermission("settlerislands.admin")) {
            msg(p, "Only the owner can pack up this ship.");
            return;
        }
        World w = Bukkit.getWorld(s.world);
        if (w == null || s.sailing) return;
        for (int[] c : cells(s.ox, s.oy, s.oz, s.rot)) {
            if (c[6] < 2) continue;
            if (w.getBlockAt(c[3], c[4], c[5]).getState() instanceof Container box && !box.getInventory().isEmpty()) {
                msg(p, "Empty the ship's chests before packing it up.");
                return;
            }
        }
        indexShip(s, false);
        clear(w, s);
        ships.remove(s.id);
        save();
        p.getInventory().addItem(item()).values().forEach(left -> w.dropItem(p.getLocation(), left));
        p.sendMessage(Component.text("Ship packed up.", NamedTextColor.GREEN));
    }

    // ================================================================ map

    /** Ships in a world for the map: {x, z} and "Name's ship". */
    public List<Map.Entry<double[], String>> markers(World w) {
        List<Map.Entry<double[], String>> out = new ArrayList<>();
        Template t = tp();
        for (Ship s : ships.values()) {
            if (!s.world.equals(w.getName())) continue;
            double[] at = s.sailing ? new double[]{s.lastX, s.lastZ} : new double[]{s.ox + t.sizeX(s.rot) / 2.0, s.oz + t.sizeZ(s.rot) / 2.0};
            out.add(Map.entry(at, s.ownerName + "'s ship"));
        }
        return out;
    }

    // ================================================================ protection

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
            if (!s.sailing && s.world.equals(b.getWorld().getName()) && box(s).contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)) {
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

    // ================================================================ saving

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            try {
                Ship s = new Ship(UUID.fromString(id), UUID.fromString(y.getString(id + ".owner")), y.getString(id + ".name", "Someone"),
                        y.getString(id + ".world"), y.getInt(id + ".x"), y.getInt(id + ".y"), y.getInt(id + ".z"), y.getInt(id + ".rot"));
                var cargo = y.getConfigurationSection(id + ".cargo");
                if (cargo != null) {
                    for (String cell : cargo.getKeys(false)) {
                        List<String> enc = cargo.getStringList(cell);
                        ItemStack[] items = new ItemStack[enc.size()];
                        for (int i = 0; i < items.length; i++) {
                            items[i] = enc.get(i).isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(enc.get(i)));
                        }
                        s.cargo.put(Integer.parseInt(cell), items);
                    }
                }
                ships.put(s.id, s);
                if (y.getBoolean(id + ".sailing")) {
                    // the server stopped while it was at sea: it drops anchor where it was
                    s.lastX = y.getDouble(id + ".sea-x");
                    s.lastZ = y.getDouble(id + ".sea-z");
                    World w = Bukkit.getWorld(s.world);
                    if (w != null) {
                        w.getChunkAt((int) Math.floor(s.lastX) >> 4, (int) Math.floor(s.lastZ) >> 4);
                        if (!dropAnchor(s, 24)) plugin.getLogger().warning("Could not place the ship of " + s.ownerName + " at " + (int) s.lastX + " " + (int) s.lastZ);
                    }
                } else {
                    indexShip(s, true);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("ships.yml: can't read ship " + id + ": " + e);
            }
        }
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
            y.set(id + ".sailing", s.sailing);
            if (s.sailing) {
                y.set(id + ".sea-x", s.lastX);
                y.set(id + ".sea-z", s.lastZ);
                for (var e : s.cargo.entrySet()) {
                    List<String> enc = new ArrayList<>();
                    for (ItemStack it : e.getValue()) {
                        enc.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
                    }
                    y.set(id + ".cargo." + e.getKey(), enc);
                }
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save ships.yml: " + e.getMessage());
        }
    }
}
