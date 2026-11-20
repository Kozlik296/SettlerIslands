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
 * captain takes the helm the ship becomes a smoothly moving model with boat-like handling (W/S speed,
 * A/D steer at any angle, the wheel turns). Everyone on deck sits down on a seat and travels along;
 * others can board by right-clicking the ship. The captain sneaking drops anchor: the ship turns back
 * into blocks where it is, with everything in its chests, and the crew stands on its deck.
 * A crafted "Sloop" item placed on water (also beside a pier) launches a ship; sneak + right-click
 * the helm at anchor (owner, empty chests) packs it up again. Ships show on the world map.
 */
public final class Ships implements Listener {

    private static final String TEMPLATE = "ship_sloop";
    // the ship turns about this point (template coordinates, the middle of the deck)
    private static final double PIV_X = 3.5, PIV_Z = 7.0;
    // seats while sailing (template x, y of the bench block, z): the captain's bench on the raised stern deck,
    // then the three crew benches near the bow
    private static final double[][] SEATS = {{3.5, 4, 1.65}, {2.5, 3, 10.65}, {3.5, 3, 10.65}, {4.5, 3, 10.65}};
    // the seats are benches (stairs): sitting height above the deck
    private static final double SEAT_Y = 0.5;
    // edge points of the hull checked for collisions while sailing (template coordinates)
    private static final double[][] HULL = {{3.5, 13.7}, {1.3, 11.6}, {5.7, 11.6}, {0.2, 9}, {6.8, 9}, {0.2, 5}, {6.8, 5},
            {0.2, 1}, {6.8, 1}, {3.5, 0.2}};
    private static final double MAX_SPEED = 0.30, ACCEL = 0.006, BRAKE = 0.012, MAX_BACK = -0.06;

    private static final class Ship {
        final UUID id, owner;
        final String ownerName, world;
        int ox, oy, oz, rot;                 // pose at anchor (oy = keel layer = water surface - 1)
        boolean sailing;
        UUID root, interaction, captain;
        final List<UUID> parts = new ArrayList<>();
        final List<BlockDisplay> partList = new ArrayList<>();
        final List<Vector3f> partPos = new ArrayList<>();
        final List<BlockDisplay> wheel = new ArrayList<>();
        final List<org.bukkit.entity.ItemDisplay> seats = new ArrayList<>();
        Map<Integer, ItemStack[]> cargo = new HashMap<>();
        float wheelAngle, yaw, shownYaw;
        double lastX, lastZ, y, speed;
        boolean anchoring;

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
    private final Map<Long, UUID> index = new HashMap<>();     // block position -> anchored ship
    private final Map<UUID, UUID> benchSeats = new HashMap<>(); // seat entity on a bench at anchor -> ship
    private Template tp;

    public Ships(SettlerIslands plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "ship_item");
        this.recipeKey = new NamespacedKey(plugin, "sloop");
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

    private static int[] facing(int rot) {
        return switch (rot & 3) {
            case 0 -> new int[]{0, 1};
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    /** Players sitting on this ship's benches at anchor stand up (the seats go away). */
    private List<Player> clearBenches(Ship s) {
        List<Player> out = new ArrayList<>();
        for (var it = benchSeats.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().equals(s.id)) continue;
            Entity seat = Bukkit.getEntity(e.getKey());
            it.remove();
            if (seat == null) continue;
            for (Entity rider : new ArrayList<>(seat.getPassengers())) {
                seat.removePassenger(rider);
                if (rider instanceof Player pl) out.add(pl);
            }
            seat.remove();
        }
        return out;
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
                && event.getClickedBlock().getType() == Material.SPRUCE_STAIRS && !p.isSneaking()) {
            Ship s = shipAt(event.getClickedBlock());
            if (s != null && !s.sailing) {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND || p.getVehicle() != null) return;
                Block b = event.getClickedBlock();
                org.bukkit.entity.ItemDisplay seat = b.getWorld().spawn(b.getLocation().add(0.5, SEAT_Y, 0.5).setDirection(
                        new org.bukkit.util.Vector(facing(s.rot)[0], 0, facing(s.rot)[1])), org.bukkit.entity.ItemDisplay.class, e -> e.setPersistent(false));
                seat.addPassenger(p);
                benchSeats.put(seat.getUniqueId(), s.id);
                return;
            }
        }
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

    /** Template point (fractions allowed) at a free pose: pivot at (px, pz), heading yaw (degrees). */
    private static double[] at(double px, double pz, float yaw, double tx, double tz) {
        double th = Math.toRadians(yaw), lx = tx - PIV_X, lz = tz - PIV_Z;
        return new double[]{px + lx * Math.cos(th) - lz * Math.sin(th), pz + lx * Math.sin(th) + lz * Math.cos(th)};
    }

    private static Quaternionf turnQ(float yaw) {
        return new Quaternionf().rotationY((float) -Math.toRadians(yaw));
    }

    /** At anchor -> sailing: the blocks become a model, everyone on deck takes a seat. */
    private void setSail(Player captain, Ship s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || s.sailing) return;
        List<Player> crew = new ArrayList<>(clearBenches(s));
        crew.remove(captain);
        if (captain.getVehicle() != null) captain.getVehicle().removePassenger(captain);
        for (Entity e : w.getNearbyEntities(box(s).expand(0.5))) {
            if (e instanceof Player other && !other.equals(captain) && other.getVehicle() == null && !crew.contains(other)) crew.add(other);
        }
        double[] piv = point(s.ox, s.oz, s.rot, PIV_X, PIV_Z);
        s.yaw = s.rot * 90f;
        s.shownYaw = s.yaw;
        s.y = s.oy + 2;                       // the deck top
        s.lastX = piv[0];
        s.lastZ = piv[1];
        s.speed = 0;
        indexShip(s, false);
        clear(w, s);
        Location base = new Location(w, piv[0], s.y, piv[1]);
        org.bukkit.entity.ItemDisplay root = w.spawn(base, org.bukkit.entity.ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setTeleportDuration(2);
        });
        s.root = root.getUniqueId();
        Template t = tp();
        Quaternionf q = turnQ(s.yaw);
        for (int y = 0; y < t.h; y++)
            for (int z = 0; z < t.d; z++)
                for (int x = 0; x < t.w; x++) {
                    int idx = t.paletteIndex(x, y, z);
                    if (idx < 2) continue;
                    BlockData data = t.data(idx, 0);
                    Vector3f local = new Vector3f((float) (x - PIV_X), y - 2, (float) (z - PIV_Z));
                    BlockDisplay d = w.spawn(base, BlockDisplay.class, e -> {
                        e.setBlock(data);
                        e.setPersistent(false);
                        e.setTransformation(new Transformation(q.transform(new Vector3f(local)), new Quaternionf(q), new Vector3f(1, 1, 1), new Quaternionf()));
                    });
                    root.addPassenger(d);
                    s.parts.add(d.getUniqueId());
                    s.partList.add(d);
                    s.partPos.add(local);
                }
        for (int i = 0; i < 1 + SPOKES + RIM; i++) {
            int part = i;
            BlockDisplay d = w.spawn(base, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(part == 0 ? Material.DARK_OAK_PLANKS : part <= SPOKES ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_PLANKS));
                e.setPersistent(false);
                e.setTransformation(wheelPart(part, s.wheelAngle, s.yaw));
            });
            root.addPassenger(d);
            s.parts.add(d.getUniqueId());
            s.wheel.add(d);
        }
        for (double[] seat : SEATS) {
            double[] p = at(piv[0], piv[1], s.yaw, seat[0], seat[2]);
            org.bukkit.entity.ItemDisplay st = w.spawn(new Location(w, p[0], s.y - 2 + seat[1] + SEAT_Y, p[1], s.yaw, 0), org.bukkit.entity.ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setTeleportDuration(2);
            });
            s.seats.add(st);
            s.parts.add(st.getUniqueId());
        }
        org.bukkit.entity.Interaction click = w.spawn(new Location(w, piv[0], s.y - 1, piv[1]), org.bukkit.entity.Interaction.class, e -> {
            e.setPersistent(false);
            e.setInteractionWidth(6f);
            e.setInteractionHeight(4f);
            e.setResponsive(true);
        });
        s.interaction = click.getUniqueId();
        s.parts.add(click.getUniqueId());
        s.captain = captain.getUniqueId();
        s.sailing = true;
        s.seats.get(0).addPassenger(captain);
        int seat = 1;
        for (Player p : crew) {
            if (seat < s.seats.size()) s.seats.get(seat++).addPassenger(p);
            else p.sendActionBar(Component.text("The ship is full - you stayed behind.", NamedTextColor.RED));
        }
        save();
        captain.sendActionBar(Component.text("W / S: faster / slower   A / D: steer   Sneak: drop anchor", NamedTextColor.GOLD));
    }

    // the wheel sits on the helm post (template 3.5, y 4.75, z 2.95), facing the captain
    private static final int SPOKES = 4, RIM = 8;

    private static Transformation wheelPart(int part, float spinDeg, float shipYaw) {
        Vector3f base = new Vector3f((float) (3.5 - PIV_X), 2.75f, (float) (2.95 - PIV_Z));
        float spin = (float) Math.toRadians(spinDeg);
        Vector3f tr, scale;
        Quaternionf q;
        if (part == 0) {
            float h = 0.2f;
            tr = new Vector3f(base).add(-h / 2, -h / 2, -0.08f);
            q = new Quaternionf();
            scale = new Vector3f(h, h, 0.16f);
        } else {
            float len, wid = 0.06f, angle;
            Vector3f centre;
            if (part <= SPOKES) {
                len = 0.95f;
                angle = spin + (float) Math.toRadians((part - 1) * 45.0);
                centre = new Vector3f();
            } else {
                int k = part - SPOKES - 1;
                float a = spin + (float) Math.toRadians(22.5 + k * 45.0);
                float r = 0.36f;
                centre = new Vector3f((float) Math.cos(a) * r, (float) Math.sin(a) * r, 0);
                len = 0.3f;
                angle = a + (float) Math.PI / 2;
                wid = 0.08f;
            }
            q = new Quaternionf(new AxisAngle4f(angle, 0, 0, 1));
            Vector3f half = new Vector3f(len / 2, wid / 2, wid / 2).rotate(q);
            tr = new Vector3f(base).add(centre).sub(half);
            scale = new Vector3f(len, wid, wid);
        }
        Quaternionf ship = turnQ(shipYaw);
        return new Transformation(ship.transform(new Vector3f(tr)), new Quaternionf(ship).mul(q), scale, new Quaternionf());
    }

    /** Can the hull be at this pose? (water or air at the waterline and below, nothing solid) */
    private boolean hullFree(World w, double px, double pz, float yaw, int waterTop) {
        for (double[] h : HULL) {
            double[] p = at(px, pz, yaw, h[0], h[1]);
            int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            for (int y = waterTop - 1; y <= waterTop + 2; y++) {
                Block b = w.getBlockAt(bx, y, bz);
                if (index.containsKey(key(w.getName(), bx, y, bz))) return false;
                if (!freeForHull(b, waterTop) && !b.getType().isAir()) return false;
            }
        }
        return true;
    }

    /** Sailing -> at anchor, where the ship is now. Returns false if there is no room. */
    private boolean dropAnchor(Ship s, int radius) {
        World w = Bukkit.getWorld(s.world);
        if (w == null) return false;
        int waterTop = (int) Math.round(s.y) - 1;
        if (s.root == null) {
            int found = waterTop(w, s.lastX, s.lastZ, 70);
            if (found == Integer.MIN_VALUE) return false;
            waterTop = found;
        }
        int[] spot = findSpot(w, s.lastX, s.lastZ, waterTop, rotationOf(s.yaw), radius, PIV_X, PIV_Z);
        if (spot == null) return false;
        List<Player> riders = new ArrayList<>();
        for (var seat : s.seats) for (Entity e : seat.getPassengers()) if (e instanceof Player p) riders.add(p);
        s.anchoring = true;
        removeModel(s);
        s.anchoring = false;
        s.ox = spot[0];
        s.oy = spot[1];
        s.oz = spot[2];
        s.rot = spot[3];
        s.sailing = false;
        build(w, s);
        indexShip(s, true);
        // the crew stands on the deck again, each where they sat
        for (int i = 0; i < riders.size(); i++) {
            double[] seat = SEATS[Math.min(i, SEATS.length - 1)];
            double[] p = point(s.ox, s.oz, s.rot, seat[0], seat[2]);
            riders.get(i).teleport(new Location(w, p[0], s.oy + seat[1] + SEAT_Y + 0.05, p[1], s.rot * 90f, 0));
        }
        save();
        return true;
    }

    private static int waterTop(World w, double x, double z, double fromY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = (int) Math.floor(fromY) + 1; y > fromY - 12; y--) {
            if (w.getBlockAt(bx, y, bz).getType() == Material.WATER) return y;
        }
        return Integer.MIN_VALUE;
    }

    private void removeModel(Ship s) {
        for (var seat : s.seats) for (Entity e : new ArrayList<>(seat.getPassengers())) seat.removePassenger(e);
        for (UUID id : s.parts) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        Entity root = s.root == null ? null : Bukkit.getEntity(s.root);
        if (root != null) root.remove();
        s.parts.clear();
        s.partList.clear();
        s.partPos.clear();
        s.wheel.clear();
        s.seats.clear();
        s.root = null;
        s.interaction = null;
        s.captain = null;
    }

    // ================================================================ while sailing

    private void tick() {
        for (Ship s : new ArrayList<>(ships.values())) {
            if (!s.sailing || s.root == null) continue;
            World w = Bukkit.getWorld(s.world);
            Entity root = Bukkit.getEntity(s.root);
            Player cap = s.captain == null ? null : Bukkit.getPlayer(s.captain);
            if (w == null || root == null) continue;
            if (cap == null || s.seats.isEmpty() || !s.seats.get(0).getPassengers().contains(cap)) {
                if (!dropAnchor(s, 16)) s.speed = 0;
                continue;
            }
            org.bukkit.Input in = cap.getCurrentInput();
            if (in.isForward()) s.speed = Math.min(MAX_SPEED, s.speed + ACCEL);
            else if (in.isBackward()) s.speed = Math.max(MAX_BACK, s.speed - BRAKE);
            else s.speed *= 0.992;
            int dir = in.isRight() && !in.isLeft() ? 1 : in.isLeft() && !in.isRight() ? -1 : 0;
            int waterTop = (int) Math.round(s.y) - 1;
            float newYaw = s.yaw;
            if (dir != 0) {
                float rate = (float) (0.8 + 2.2 * Math.min(1, Math.abs(s.speed) / MAX_SPEED));
                newYaw = s.yaw + dir * rate;
                if (hullFree(w, s.lastX, s.lastZ, newYaw, waterTop)) {
                    s.yaw = newYaw;
                    s.wheelAngle += dir * 8f;
                }
            }
            double th = Math.toRadians(s.yaw);
            double nx = s.lastX - Math.sin(th) * s.speed, nz = s.lastZ + Math.cos(th) * s.speed;
            if (Math.abs(s.speed) > 0.0005) {
                if (hullFree(w, nx, nz, s.yaw, waterTop)) {
                    s.lastX = nx;
                    s.lastZ = nz;
                } else {
                    if (Math.abs(s.speed) > 0.05) cap.sendActionBar(Component.text("Bump! Steer around it.", NamedTextColor.RED));
                    s.speed = 0;
                }
            }
            // move the model and the seats
            root.teleport(new Location(w, s.lastX, s.y, s.lastZ), io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
            for (int i = 0; i < s.seats.size(); i++) {
                double[] p = at(s.lastX, s.lastZ, s.yaw, SEATS[i][0], SEATS[i][2]);
                s.seats.get(i).teleport(new Location(w, p[0], s.y - 2 + SEATS[i][1] + SEAT_Y, p[1], s.yaw, 0), io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
            }
            Entity click = s.interaction == null ? null : Bukkit.getEntity(s.interaction);
            if (click != null) click.teleport(new Location(w, s.lastX, s.y - 1, s.lastZ));
            if (Math.abs(s.yaw - s.shownYaw) > 0.01f) {
                Quaternionf q = turnQ(s.yaw);
                for (int i = 0; i < s.partList.size(); i++) {
                    BlockDisplay d = s.partList.get(i);
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(2);
                    d.setTransformation(new Transformation(q.transform(new Vector3f(s.partPos.get(i))), new Quaternionf(q), new Vector3f(1, 1, 1), new Quaternionf()));
                }
                s.shownYaw = s.yaw;
            }
            if (dir != 0 || Math.abs(s.yaw - s.shownYaw) > 0) {
                for (int i = 0; i < s.wheel.size(); i++) {
                    BlockDisplay d = s.wheel.get(i);
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(2);
                    d.setTransformation(wheelPart(i, s.wheelAngle, s.yaw));
                }
            }
        }
    }

    private Ship shipOfSeat(Entity e) {
        for (Ship s : ships.values()) {
            for (var seat : s.seats) if (seat.equals(e)) return s;
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        Entity dismounted = event.getDismounted();
        if (benchSeats.remove(dismounted.getUniqueId()) != null) {
            Bukkit.getScheduler().runTask(plugin, dismounted::remove);
            Entity rider = event.getEntity();
            Bukkit.getScheduler().runTask(plugin, () -> rider.teleport(rider.getLocation().add(0, 0.6, 0)));
            return;
        }
        Ship s = shipOfSeat(event.getDismounted());
        if (s == null || !s.sailing || s.anchoring || !(event.getEntity() instanceof Player p)) return;
        event.setCancelled(true);
        if (!p.getUniqueId().equals(s.captain)) {
            p.sendActionBar(Component.text("You can get up when the captain drops anchor.", NamedTextColor.YELLOW));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (dropAnchor(s, 5)) p.sendActionBar(Component.text("Anchor dropped.", NamedTextColor.GREEN));
            else p.sendActionBar(Component.text("No room to drop anchor here - sail into open water.", NamedTextColor.RED));
        });
    }

    /** Right-clicking a sailing ship takes a free seat. */
    @EventHandler(ignoreCancelled = true)
    public void onBoard(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        for (Ship s : ships.values()) {
            if (!s.sailing || s.interaction == null || !s.interaction.equals(event.getRightClicked().getUniqueId())) continue;
            event.setCancelled(true);
            Player p = event.getPlayer();
            if (p.getVehicle() != null) return;
            for (var seat : s.seats) {
                if (seat.getPassengers().isEmpty()) {
                    seat.addPassenger(p);
                    p.sendActionBar(Component.text("Welcome aboard!", NamedTextColor.GREEN));
                    return;
                }
            }
            p.sendActionBar(Component.text("The ship is full.", NamedTextColor.RED));
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Ship s : ships.values()) {
            if (s.sailing && event.getPlayer().getUniqueId().equals(s.captain) && !dropAnchor(s, 16)) {
                plugin.getLogger().warning("Ship of " + s.ownerName + " could not drop anchor; it will at the next start.");
            }
        }
    }

    /** On shutdown every sailing ship drops anchor (as near as it fits). */
    public void shutdown() {
        for (Ship s : ships.values()) {
            clearBenches(s);
            if (s.sailing) dropAnchor(s, 24);
        }
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
        clearBenches(s);
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
