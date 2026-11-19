package dev.kozlik.settlerislands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The fixed island layout, read from layout.txt. Each island is an ellipse
 * (centre, half width, half depth) whose radius is scaled per degree by a
 * table of factors, which gives the irregular coastline drawn on the map.
 */
public final class Layout {

    public static final class Island {
        public final String id;
        public final Zone zone;
        public final double cx, cz, hw, hh;
        private final float[] radius;

        Island(String id, Zone zone, double cx, double cz, double hw, double hh, float[] radius) {
            this.id = id;
            this.zone = zone;
            this.cx = cx;
            this.cz = cz;
            this.hw = hw;
            this.hh = hh;
            this.radius = radius;
        }

        /** Approximate distance in blocks from the coastline: positive inside, negative outside. */
        public double signedDistance(double x, double z) {
            double dx = (x - cx) / hw;
            double dz = (z - cz) / hh;
            double d = Math.sqrt(dx * dx + dz * dz);
            double t = Math.atan2(dz, dx);
            double deg = Math.toDegrees(t);
            if (deg < 0) deg += 360;
            int a = (int) deg;
            double f = deg - a;
            double r = radius[a % 360] * (1 - f) + radius[(a + 1) % 360] * f;
            double len = Math.hypot(hw * Math.cos(t), hh * Math.sin(t));
            return (r - d) * len;
        }
    }

    private static Layout instance;
    private final List<Island> islands;

    private Layout(List<Island> islands) {
        this.islands = Collections.unmodifiableList(islands);
    }

    public static synchronized Layout get() {
        if (instance == null) {
            try (InputStream in = Layout.class.getResourceAsStream("/layout.txt")) {
                if (in == null) throw new IllegalStateException("layout.txt missing from the jar");
                instance = parse(in);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read layout.txt", e);
            }
        }
        return instance;
    }

    private static Layout parse(InputStream in) throws IOException {
        List<Island> list = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            float[] r = new float[360];
            for (int i = 0; i < 360; i++) r[i] = Float.parseFloat(p[6 + i]);
            list.add(new Island(p[0], Zone.valueOf(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Double.parseDouble(p[4]), Double.parseDouble(p[5]), r));
        }
        return new Layout(list);
    }

    public List<Island> islands() {
        return islands;
    }

    /** Finds an island by id (e.g. "meadow", "islet3") or by zone name (e.g. "nether"). */
    public Island find(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Island i : islands) if (i.id.equals(n)) return i;
        for (Island i : islands) if (i.zone.name().toLowerCase(Locale.ROOT).equals(n)) return i;
        return null;
    }
}
