package dev.kozlik.settlerislands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Chest shops. Put the item you trade in a chest (or barrel), then a sign on its side:
 *   line 1: [shop]
 *   line 2: sell 16 for 3   (you sell 16 of the item for 3 emeralds)
 *        or buy 16 for 3    (you buy 16 of the item and pay 3 emeralds)
 * The sign then shows the item and the owner. Others right-click the sign to trade; only the owner
 * can open or break the chest and the sign, hoppers can't take from it and explosions can't break it.
 */
public final class ChestShops implements Listener {

    private final SettlerIslands plugin;
    private final NamespacedKey ownerKey, modeKey, amountKey, priceKey, itemKey;

    public ChestShops(SettlerIslands plugin) {
        this.plugin = plugin;
        ownerKey = new NamespacedKey(plugin, "shop_owner");
        modeKey = new NamespacedKey(plugin, "shop_mode");
        amountKey = new NamespacedKey(plugin, "shop_amount");
        priceKey = new NamespacedKey(plugin, "shop_price");
        itemKey = new NamespacedKey(plugin, "shop_item");
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("rules.chest-shops", true);
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c).trim();
    }

    private static String pretty(Material m) {
        String[] w = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : w) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }

    /** The container a wall sign hangs on, or null. */
    private static Block attached(Block sign) {
        if (!(sign.getBlockData() instanceof WallSign ws)) return null;
        Block b = sign.getRelative(ws.getFacing().getOppositeFace());
        return b.getState() instanceof Container ? b : null;
    }

    /** Both halves of a double chest (or just the block). */
    private static List<Block> halves(Block container) {
        List<Block> out = new ArrayList<>();
        out.add(container);
        if (container.getState() instanceof Chest c) {
            InventoryHolder h = c.getInventory().getHolder();
            if (h instanceof DoubleChest dc) {
                for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()}) {
                    if (side instanceof Chest ch && !ch.getBlock().equals(container)) out.add(ch.getBlock());
                }
            }
        }
        return out;
    }

    /** The shop sign on this container (either half), or null. */
    private Sign shopSign(Block container) {
        for (Block half : halves(container)) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                Block s = half.getRelative(f);
                if (s.getState() instanceof Sign sign && sign.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)
                        && half.equals(attached(s))) return sign;
            }
        }
        return null;
    }

    private UUID owner(Sign s) {
        String o = s.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return o == null ? null : UUID.fromString(o);
    }

    private boolean mayManage(Player p, Sign s) {
        return p.getUniqueId().equals(owner(s)) || p.hasPermission("settlerislands.admin");
    }

    // ---------------------------------------------------------------- making a shop

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!on() || !plain(event.line(0)).equalsIgnoreCase("[shop]")) return;
        Player p = event.getPlayer();
        Block container = attached(event.getBlock());
        if (container == null) {
            p.sendMessage(Component.text("A shop sign has to hang on the side of a chest or barrel.", NamedTextColor.RED));
            return;
        }
        Sign existing = shopSign(container);
        if (existing != null && !existing.getBlock().equals(event.getBlock())) {
            p.sendMessage(Component.text("That chest is already a shop.", NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }
        String[] w = plain(event.line(1)).toLowerCase(Locale.ROOT).replace("for", " ").trim().split("\\s+");
        int amount, price;
        String mode;
        try {
            mode = w[0];
            amount = Integer.parseInt(w[1]);
            price = Integer.parseInt(w[2]);
            if (!(mode.equals("sell") || mode.equals("buy")) || amount < 1 || amount > 64 || price < 1 || price > 64) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            p.sendMessage(Component.text("Line 2 should be like 'sell 16 for 3' or 'buy 16 for 3' (1-64).", NamedTextColor.RED));
            return;
        }
        Material item = null;
        for (ItemStack it : ((Container) container.getState()).getInventory().getContents()) {
            if (it != null && it.getType() != Material.EMERALD && it.getType() != Material.AIR) {
                item = it.getType();
                break;
            }
        }
        if (item == null) {
            p.sendMessage(Component.text("Put one of the item you want to trade in the chest first.", NamedTextColor.RED));
            return;
        }
        event.line(0, Component.text("[Shop]", NamedTextColor.DARK_GREEN).decorate(TextDecoration.BOLD));
        event.line(1, Component.text((mode.equals("sell") ? "Sells " : "Buys ") + amount + " for " + price));
        event.line(2, Component.text(pretty(item)));
        event.line(3, Component.text(p.getName(), NamedTextColor.DARK_GRAY));
        Material fItem = item;
        Block signBlock = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(signBlock.getState() instanceof Sign s)) return;
            PersistentDataContainer pdc = s.getPersistentDataContainer();
            pdc.set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
            pdc.set(modeKey, PersistentDataType.STRING, mode);
            pdc.set(amountKey, PersistentDataType.INTEGER, amount);
            pdc.set(priceKey, PersistentDataType.INTEGER, price);
            pdc.set(itemKey, PersistentDataType.STRING, fItem.name());
            s.setWaxed(true);
            s.update();
        });
        p.sendMessage(Component.text("Shop made: you " + mode + " " + amount + " " + pretty(item) + " for " + price + " emeralds.", NamedTextColor.GREEN));
    }

    // ---------------------------------------------------------------- trading and protection

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        if (!on() || event.getClickedBlock() == null) return;
        Block b = event.getClickedBlock();
        Player p = event.getPlayer();
        if (b.getState() instanceof Sign sign && sign.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            event.setCancelled(true);                    // never open the sign editor
            Block container = attached(b);
            if (container == null) return;
            if (p.getUniqueId().equals(owner(sign)) && !p.isSneaking()) {
                p.sendMessage(Component.text("This is your shop. Sneak + right-click the sign to test-buy.", NamedTextColor.GRAY));
                return;
            }
            trade(p, sign, ((Container) container.getState()).getInventory());
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && b.getState() instanceof Container) {
            Sign s = shopSign(b);
            if (s != null && !mayManage(p, s)) {
                event.setCancelled(true);
                p.sendActionBar(Component.text("This chest is a shop - right-click its sign to trade", NamedTextColor.YELLOW));
            }
        }
    }

    private void trade(Player p, Sign sign, Inventory chest) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String mode = pdc.get(modeKey, PersistentDataType.STRING);
        int amount = pdc.getOrDefault(amountKey, PersistentDataType.INTEGER, 1);
        int price = pdc.getOrDefault(priceKey, PersistentDataType.INTEGER, 1);
        Material item = Material.matchMaterial(pdc.getOrDefault(itemKey, PersistentDataType.STRING, "STONE"));
        if (item == null || mode == null) return;
        Inventory inv = p.getInventory();
        ItemStack money = new ItemStack(Material.EMERALD, price);
        if (mode.equals("sell")) {
            if (count(chest, item) < amount) { msg(p, "The shop is sold out."); return; }
            if (count(inv, Material.EMERALD) < price) { msg(p, "You need " + price + " emeralds."); return; }
            if (!fits(chest, money)) { msg(p, "The shop chest is full."); return; }
            take(inv, Material.EMERALD, price);
            List<ItemStack> got = take(chest, item, amount);
            chest.addItem(money.clone());
            give(p, got);
            p.sendMessage(Component.text("Bought " + amount + " " + pretty(item) + " for " + price + " emeralds.", NamedTextColor.GREEN));
        } else {
            if (count(inv, item) < amount) { msg(p, "You need " + amount + " " + pretty(item) + "."); return; }
            if (count(chest, Material.EMERALD) < price) { msg(p, "The shop is out of emeralds."); return; }
            if (!fits(chest, new ItemStack(item, amount))) { msg(p, "The shop chest is full."); return; }
            List<ItemStack> goods = take(inv, item, amount);
            take(chest, Material.EMERALD, price);
            for (ItemStack g : goods) chest.addItem(g);
            give(p, List.of(money.clone()));
            p.sendMessage(Component.text("Sold " + amount + " " + pretty(item) + " for " + price + " emeralds.", NamedTextColor.GREEN));
        }
    }

    private static void msg(Player p, String s) {
        p.sendMessage(Component.text(s, NamedTextColor.RED));
    }

    /** How many of a material are in the inventory (whatever their lore, e.g. food freshness). */
    private static int count(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (it != null && it.getType() == m) n += it.getAmount();
        return n;
    }

    /** Takes n of a material out of the inventory and returns the taken stacks (keeping their data). */
    private static List<ItemStack> take(Inventory inv, Material m, int n) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && n > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            int k = Math.min(n, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(k);
            out.add(part);
            it.setAmount(it.getAmount() - k);
            contents[i] = it.getAmount() <= 0 ? null : it;
            n -= k;
        }
        inv.setStorageContents(contents);
        return out;
    }

    private static void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
    }

    private static boolean fits(Inventory inv, ItemStack stack) {
        int need = stack.getAmount(), max = stack.getMaxStackSize();
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType() == Material.AIR) need -= max;
            else if (it.isSimilar(stack)) need -= max - it.getAmount();
            if (need <= 0) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!on()) return;
        Block b = event.getBlock();
        Sign s = null;
        if (b.getState() instanceof Sign sign && sign.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) s = sign;
        else if (b.getState() instanceof Container) s = shopSign(b);
        if (s != null && !mayManage(event.getPlayer(), s)) {
            event.setCancelled(true);
            OfflinePlayer o = owner(s) != null ? Bukkit.getOfflinePlayer(owner(s)) : null;
            msg(event.getPlayer(), "This shop belongs to " + (o != null && o.getName() != null ? o.getName() : "someone else") + ".");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (!on()) return;
        if (event.getSource().getHolder() instanceof BlockState st && st.getBlock().getState() instanceof Container && shopSign(st.getBlock()) != null)
            event.setCancelled(true);
        else if (event.getSource().getHolder() instanceof DoubleChest dc && dc.getLeftSide() instanceof Chest c && shopSign(c.getBlock()) != null)
            event.setCancelled(true);
    }

    private boolean isShopBlock(Block b) {
        if (b.getState() instanceof Sign sign) return sign.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
        return b.getState() instanceof Container && shopSign(b) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (on()) event.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (on()) event.blockList().removeIf(this::isShopBlock);
    }
}
