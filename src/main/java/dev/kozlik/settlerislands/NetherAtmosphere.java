package dev.kozlik.settlerislands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Around the Nether zone the sky darkens step by step as you get closer (only for that player),
 * it rains, and lightning keeps flashing. Deep inside it is always midnight.
 */
public final class NetherAtmosphere implements Runnable {

    private static final double START = -450;   // blocks out at sea where it starts getting darker
    private static final double FULL = 160;     // blocks inland where it is fully dark

    private final Set<UUID> affected = new HashSet<>();
    private final Random random = new Random();

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            double f = 0;
            if (w.getGenerator() instanceof IslandGenerator) {
                WorldModel m = WorldModel.get(w.getSeed());
                Location loc = p.getLocation();
                WorldModel.Sample sm = m.sample(loc.getBlockX(), loc.getBlockZ());
                if (sm.nearZone() == Zone.NETHER) f = smooth(sm.s(), START, FULL);
            }
            if (f > 0.02) {
                affected.add(p.getUniqueId());
                long now = w.getTime() % 24000;
                long ahead = Math.floorMod(18000 - now, 24000);   // forward through dusk to midnight
                p.setPlayerTime((now + (long) (ahead * f)) % 24000, false);
                if (f > 0.2) p.setPlayerWeather(WeatherType.DOWNFALL);
                else p.resetPlayerWeather();
                if (f > 0.3 && random.nextDouble() < 0.06 * f) strike(p);
            } else if (affected.remove(p.getUniqueId())) {
                p.resetPlayerTime();
                p.resetPlayerWeather();
            }
        }
    }

    private void strike(Player p) {
        double a = random.nextDouble() * Math.PI * 2, r = 30 + random.nextDouble() * 70;
        Location l = p.getLocation().clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
        l.setY(p.getWorld().getHighestBlockYAt(l) + 1);
        p.getWorld().strikeLightningEffect(l);
    }

    private static double smooth(double v, double a, double b) {
        double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
