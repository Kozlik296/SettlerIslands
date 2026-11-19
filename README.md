# SettlerIslands

World generator for the settler island server (Paper 26.3). Phase 1: terrain, biomes, sea and ores.
Structures (village, fortress, trader camps, ports) and zone rules come in later phases.

## Download
Every push builds the plugin on GitHub Actions. Open the **Actions** tab, click the newest green run,
and download the **SettlerIslands** artifact (a zip with the jar inside).

## Test it without touching your main world
1. Put the jar in `plugins/` and restart the server.
2. In game (as op): `/si create islandtest` makes a new world and teleports you to spawn on Meadow.
3. Look around:
   - `/si tp <island>`: meadow, sandbanks, ironstone, gloomreach, timberlands, highlands, nether, farreef, islet1 to islet7
   - `/si where`: which zone and biome you stand in
   - `/si stats`: land per zone and the ore totals from config.yml

## Use it as the main world (later)
Stop the server, delete or move the old `world` folders, and add this to `bukkit.yml`:

```yaml
worlds:
  world:
    generator: SettlerIslands
```

Then pre-generate the whole map once with Chunky (radius 3000, centred on 0,0) before players join.

## Config
`config.yml` sets the world border size and the ore amounts per zone (approximate number of ore blocks
on each island). Changes only affect chunks generated after the change.
