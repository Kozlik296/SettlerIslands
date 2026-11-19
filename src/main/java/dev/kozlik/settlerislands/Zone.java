package dev.kozlik.settlerislands;

/** The zones of the island world. SEA is everything that is not land. */
public enum Zone {
    MEADOW, SANDBANKS, IRONSTONE, GLOOMREACH, TIMBERLANDS, HIGHLANDS, NETHER, FAR_REEF, ISLET, SEA;

    public boolean isLand() {
        return this != SEA;
    }
}
