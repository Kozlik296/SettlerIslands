package dev.kozlik.settlerislands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The Settler Islands guide: a written book about the islands, what each has and trades, and the
 * rules. Every player gets one the first time they join an island world, it lies on the lectern
 * in the city hall, and /guide gives a new copy.
 */
public final class Guide implements Listener, CommandExecutor {

    private final SettlerIslands plugin;
    private final NamespacedKey gotKey;

    public Guide(SettlerIslands plugin) {
        this.plugin = plugin;
        this.gotKey = new NamespacedKey(plugin, "got_guide");
    }

    private static Component page(String title, String body) {
        return Component.text(title + "\n\n", NamedTextColor.DARK_BLUE).decorate(TextDecoration.BOLD)
                .append(Component.text(body, NamedTextColor.BLACK).decoration(TextDecoration.BOLD, false));
    }

    public ItemStack book() {
        var c = plugin.getConfig();
        int reach = c.getInt("rules.support-reach", 8);
        int spoil = c.getInt("rules.spoil-hours", 12);
        int free = (int) c.getDouble("rules.weight.free-stacks", 12);
        int per = (int) Math.round(c.getDouble("rules.weight.per-stack", 0.025) * 1000);
        int grow = (int) c.getDouble("rules.meadow-growth", 2);
        List<Component> pages = new ArrayList<>();
        pages.add(page("Settler Islands",
                "A world of islands with nothing in between but sea.\n\nEvery island has its own riches. "
                        + "No island has everything, so you travel, trade and build ships and railways.\n\n"
                        + "The big map hangs in the city hall."));
        pages.add(page("The map",
                "North is up.\n\nWest edge: Meadow (you are here)\nEast of it: Timberlands\nNorth: Sandbanks\n"
                        + "Middle: Ironstone, with Gloomreach to its north\nSouth-east: Highlands\n"
                        + "North-east: Far Reef\nFar east: the Nether isle\nSmall islets in between."));
        pages.add(page("Meadow",
                "Safe: no monsters spawn here.\nCrops grow and animals breed " + grow + "x faster.\n\n"
                        + "Ores: coal, copper.\n\nThe big village with the city hall, and the harbour with "
                        + "its market. Village trades never include iron or diamond gear."));
        pages.add(page("Timberlands",
                "East across the strait from the Meadow harbour. Dense forests: all kinds of wood. "
                        + "A little coal.\n\nLogger camp:\nwoodcutter, fletcher, orchard keeper.\nSells logs, saplings, "
                        + "bows, arrows, apples, honey.\nBuys iron, coal, string, food."));
        pages.add(page("Sandbanks",
                "Desert in the west, badlands with mesas and coloured terracotta in the east. "
                        + "Good fishing.\n\nGlassworks:\nglassmaker, potter, fisher.\nSells sand, glass, terracotta, "
                        + "clay, bricks, cooked fish.\nBuys coal, logs, iron, string."));
        pages.add(page("Ironstone",
                "The southern half of the big middle island. Steep mountains full of iron, the most "
                        + "iron anywhere. Also coal and copper.\n\nMining camp:\ntoolsmith, railwright, quartermaster.\n"
                        + "Sells iron tools, rails, buckets, minecarts, torches.\nBuys food, logs, wool."));
        pages.add(page("Gloomreach",
                "The northern half of the middle island. Dark and pale forests, deep caves.\n\n"
                        + "DANGEROUS: under the trees monsters come out even by day.\n\nOres: gold, redstone, coal.\n\nNo settlement."));
        pages.add(page("Highlands",
                "Snowy mountains in the south-east.\n\nOres: copper, lapis, emeralds high up, "
                        + "diamonds deep down.\n\nShepherd lodges:\nshepherd, prospector.\nSells wool, shears, "
                        + "copper, lapis.\nBuys food, coal, iron tools."));
        pages.add(page("Far Reef",
                "A jungle island in the north-east with lush caves.\n\nOres: the most diamonds, deep down. "
                        + "Lapis.\n\nNo settlement."));
        pages.add(page("The Nether isle",
                "Far east. A volcano island, always dark and stormy, with lava lakes.\n\nOres: quartz, "
                        + "nether gold, ancient debris.\n\nThe great fortress: blazes and wither skeletons. "
                        + "Loot in its chests."));
        pages.add(page("Nether outpost",
                "Alchemist.\nSells nether wart, glowstone, quartz, blaze powder, magma cream, golden apples.\n"
                        + "Buys logs, bread, gold, water buckets."));
        pages.add(page("Ships",
                "Craft a Sloop:\n  3 white wool\n  log, chest, log\n  3 boats\n\nRight-click the water (also from a pier) "
                        + "to launch it. At anchor you walk on deck and use its chests."));
        pages.add(page("Sailing",
                "Right-click the helm to set sail. Everyone on deck sits down and comes along (4 seats); "
                        + "others can right-click the ship to board.\n\nW / S: faster / slower\nA / D: steer\n"
                        + "Sneak: drop anchor"));
        pages.add(page("Ships (3)",
                "Sneak + right-click the helm at anchor to pack the ship into an item (empty the chests first).\n\n"
                        + "No building on ships. Ships show on the map in the city hall."));
        pages.add(page("Prices",
                "Pay and get paid in emeralds.\n\nEvery island has one price per trade, the same for everyone. "
                        + "Each time a trade is used there it gets a bit worse (up to 2x). It recovers by itself "
                        + "over a day or two."));
        pages.add(page("Chest shops",
                "Sell to other players:\n1. Put your item in a chest.\n2. Hang a sign on it:\n"
                        + "  [shop]\n  sell 16 for 3\n(or: buy 16 for 3)\n\nOthers right-click the sign. "
                        + "Only you can open or break your shop."));
        pages.add(page("Building",
                "Blocks must be held up. A block more than " + reach + " blocks sideways from something "
                        + "standing on the ground falls down.\n\nWalls and towers are fine. Long bridges "
                        + "need pillars every " + (reach * 2) + " blocks."));
        pages.add(page("Food and load",
                "Food you carry spoils after " + spoil + " hours of your playing time. Chests keep food "
                        + "fresh.\n\nCarrying a lot slows you down: " + free + " stacks are free, every stack "
                        + "more is " + (per / 10.0) + "% slower. Ships and carts don't care."));
        pages.add(page("Not here",
                "- No elytra flying\n- No ender chests or shulker boxes\n- No Nether or End portals\n"
                        + "- No chorus fruit teleporting\n- No ice roads over the sea\n\nTravel by boat, ship "
                        + "or railway."));
        pages.add(page("Lost this book?", "Type /guide for a new one.\n\nGood luck, settler!"));

        ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) stack.getItemMeta();
        meta.setTitle("Settler Islands Guide");
        meta.setAuthor("The City Hall");
        meta.addPages(pages.toArray(new Component[0]));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // every player gets the book once, whatever world they join in (a second tick later, so it is not lost while joining)
        if (p.getPersistentDataContainer().has(gotKey, PersistentDataType.BYTE)) return;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.getPersistentDataContainer().set(gotKey, PersistentDataType.BYTE, (byte) 1);
            p.getInventory().addItem(book()).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            p.sendMessage(Component.text("Welcome, settler! Read the guide book in your inventory. (/guide for a new copy)", NamedTextColor.GOLD));
        }, 40L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can get the guide.");
            return true;
        }
        p.getInventory().addItem(book()).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        p.sendMessage(Component.text("Here is a new copy of the guide.", NamedTextColor.GOLD));
        return true;
    }
}
