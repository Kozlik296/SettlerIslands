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
        int ox, oy, oz, rot;
        UUID captain, seat;
        final List<UUID> wheel = new ArrayList<>();
        float wheelAngle;
        boolean sailing;
        int moveCooldown, turnCooldown, blockedTicks;
        boolean lastLeft, lastRight, lastForward, lastBack;

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
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) event.getItem().setAmount(event.getItem().getAmount() - 1);
        p.sendMessage(Component.text("Ship launched! Right-click the helm to take the wheel: W sail, S stop, A/D turn, sneak to leave.", NamedTextColor.GREEN));
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

    private void helm(Player p, Ship s) {
        if (p.isSneaking()) {
            pack(p, s);
            return;
        }
        if (s.captain != null) {
            Player c = Bukkit.getPlayer(s.captain);
            if (c != null && c.isOnline()) {
                msg(p, c.getName() + " is at the wheel.");
                return;
            }
            leaveHelm(s);
        }
        World w = Bukkit.getWorld(s.world);
        if (w == null) return;
        // the captain sits behind the wheel, looking towards the bow
        Location seatLoc = point(s, 3.5, 2.75, 1.5);
        seatLoc.setYaw(s.rot * 90f);
        ArmorStand seat = w.spawn(seatLoc, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSmall(true);
            a.setPersistent(false);
        });
        seat.addPassenger(p);
        s.captain = p.getUniqueId();
        s.seat = seat.getUniqueId();
        s.sailing = false;
        s.lastLeft = s.lastRight = s.lastForward = s.lastBack = false;
        spawnWheel(w, s);
        p.sendActionBar(Component.text("W: set sail   S: stop   A / D: turn   Sneak: leave the wheel", NamedTextColor.GOLD));
    }

    private void leaveHelm(Ship s) {
        if (s.seat != null) {
            Entity e = Bukkit.getEntity(s.seat);
            if (e != null) {
                e.eject();
                e.remove();
            }
        }
        for (UUID id : s.wheel) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        s.wheel.clear();
        s.seat = null;
        s.captain = null;
        s.sailing = false;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        for (Ship s : ships.values()) {
            if (s.seat != null && s.seat.equals(event.getDismounted().getUniqueId())) {
                Player p = event.getEntity() instanceof Player pl ? pl : null;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    leaveHelm(s);
                    if (p != null) p.sendActionBar(Component.text("You left the wheel - the ship stops.", NamedTextColor.YELLOW));
                });
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Ship s : ships.values()) if (event.getPlayer().getUniqueId().equals(s.captain)) leaveHelm(s);
    }

    /** Removes seats and wheels (on shutdown). */
    public void shutdown() {
        for (Ship s : ships.values()) leaveHelm(s);
        save();
    }

    // ---------------------------------------------------------------- the wheel

    /** A point given in template coordinates (blocks, may be fractional) as a world location. */
    private Location point(Ship s, double tx, double ty, double tz) {
        Template t = tp();
        double x = tx, z = tz;
        int w = t.w, d = t.d;
        // the same turn as Template.offset, for points instead of cells
        for (int i = 0; i < (s.rot & 3); i++) {
            double nx = d - z, nz = x;
            x = nx;
            z = nz;
            int tmp = w;
            w = d;
            d = tmp;
        }
        return new Location(Bukkit.getWorld(s.world), s.ox + x, s.oy + ty, s.oz + z);
    }

    private static final int SPOKES = 4, RIM = 8;

    private void spawnWheel(World w, Ship s) {
        Location at = point(s, 3.5, 4.35, 2.95);
        at.setYaw(s.rot * 90f);
        at.setPitch(0);
        int n = 1 + SPOKES + RIM;
        for (int i = 0; i < n; i++) {
            int part = i;
            BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(part == 0 ? Material.DARK_OAK_PLANKS : part <= SPOKES ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_PLANKS));
                e.setPersistent(false);
                e.setTeleportDuration(Math.max(1, plugin.getConfig().getInt("ships.ticks-per-block", 3)));
                e.setTransformation(wheelPart(part, s.wheelAngle));
                e.setBrightness(new Display.Brightness(12, 12));
            });
            s.wheel.add(d.getUniqueId());
        }
    }

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

    private void turnWheel(Ship s, int dir) {
        s.wheelAngle += dir * 90f;
        for (int i = 0; i < s.wheel.size(); i++) {
            if (Bukkit.getEntity(s.wheel.get(i)) instanceof BlockDisplay d) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(10);
                d.setTransformation(wheelPart(i, s.wheelAngle));
            }
        }
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
        leaveHelm(s);
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

    private void tick() {
        int every = Math.max(1, plugin.getConfig().getInt("ships.ticks-per-block", 3));
        for (Ship s : new ArrayList<>(ships.values())) {
            if (s.captain == null) continue;
            Player cap = Bukkit.getPlayer(s.captain);
            World w = Bukkit.getWorld(s.world);
            if (cap == null || w == null || cap.getVehicle() == null || !cap.getVehicle().getUniqueId().equals(s.seat)) {
                leaveHelm(s);
                continue;
            }
            org.bukkit.Input in = cap.getCurrentInput();
            boolean left = in.isLeft(), right = in.isRight(), fwd = in.isForward(), back = in.isBackward();
            if (fwd && !s.lastForward && !s.sailing) {
                s.sailing = true;
                cap.sendActionBar(Component.text("Sails up!", NamedTextColor.GREEN));
            }
            if (back && !s.lastBack && s.sailing) {
                s.sailing = false;
                cap.sendActionBar(Component.text("Sails down - the ship stops.", NamedTextColor.YELLOW));
            }
            if (s.turnCooldown > 0) s.turnCooldown--;
            int turn = right && !left ? 1 : left && !right ? -1 : 0;
            boolean pressed = (right && !s.lastRight) || (left && !s.lastLeft);
            if (turn != 0 && (pressed || s.turnCooldown == 0) && s.turnCooldown == 0) {
                if (move(w, s, 0, 0, turn)) {
                    turnWheel(s, turn);
                    s.turnCooldown = 16;
                } else if (pressed) {
                    cap.sendActionBar(Component.text("Not enough room to turn here.", NamedTextColor.RED));
                }
            }
            s.lastLeft = left;
            s.lastRight = right;
            s.lastForward = fwd;
            s.lastBack = back;
            if (s.sailing && ++s.moveCooldown >= every) {
                s.moveCooldown = 0;
                int[] f = facing(s.rot);
                if (move(w, s, f[0], f[1], 0)) s.blockedTicks = 0;
                else if (++s.blockedTicks == 1) {
                    cap.sendActionBar(Component.text("Something is in the way - turn with A / D.", NamedTextColor.RED));
                    s.sailing = false;
                }
            }
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
                l.setZ(ncz + nz);
                l.setYaw(l.getYaw() + 90f * turn);
            }
            e.teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS, TeleportFlag.EntityState.RETAIN_OPEN_INVENTORY);
        }
        return true;
    }

    /** Ships in a world for the map: {x, z} and "Name's ship". */
    public List<Map.Entry<double[], String>> markers(World w) {
        List<Map.Entry<double[], String>> out = new ArrayList<>();
        Template t = tp();
        for (Ship s : ships.values()) {
            if (!s.world.equals(w.getName())) continue;
            out.add(Map.entry(new double[]{s.ox + t.sizeX(s.rot) / 2.0, s.oz + t.sizeZ(s.rot) / 2.0}, s.ownerName + "'s ship"));
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
