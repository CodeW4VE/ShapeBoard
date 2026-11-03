# ShapeBoard

*Read this in [Español](README_es.md).*

Scoreboards for areas of any shape. Outline a dig or build zone with a line of blocks in the sky,
run one command, and every block broken or placed inside that exact shape is counted.

![Flying out of the area removes the sidebar, flying back in restores it, live scores included](docs/demo.gif)

Full quality video with sound: [shapeboard-demo.mp4](https://github.com/CodeW4VE/ShapeBoard/releases/download/v1.0.1/shapeboard-demo.mp4)

- Any outline works: round corners, bulges, holes. Mining outside the real project does not count.
- A leaderboard sidebar appears only for players standing inside the shape, and goes away when they leave.
- Rank by blocks broken, placed, or both. Both are always counted, so a dig can turn into a build later.
- Progress bars for digs: how much is left in total, per layer, or down to a target Y.
- Server-side only. Players install nothing. Teams and team colors are left alone.

## Installation

1. A Fabric server with [Fabric API](https://modrinth.com/mod/fabric-api), Minecraft 1.21 to 26.3.
2. Put the jar for your Minecraft version in `mods/` (see [Which jar](#which-jar)). Keep only one.
3. Restart. There is no config file.

Minecraft 26.x needs Java 25 and, for 26.3, Fabric Loader 0.19.5 or newer.

## Usage

1. Draw one closed line of a marker block (for example black concrete) at a Y above the build,
   say y150. Gaps up to 6 blocks are bridged; a bigger gap makes the scan fail and prints the
   coordinates of the open ends.
2. Within 64 blocks of the line, run:

   ```
   /shapeboard create bigculo minecraft:black_concrete 150
   ```

   ```
   [ShapeBoard] Shape 'bigculo' created in 45 ms: 797,680 columns inside,
   outline of 4,350 blocks. Objectives: bigculo_break / bigculo_place.
   ```

   For a plain rectangle, skip the drawing: `/shapeboard createbox spawnzone 30640 4768 31631 6079 150`
   (two corners and the ceiling Y).
3. Name it for the sidebar: `/shapeboard rename bigculo Big Culo`
4. Choose what it ranks by. The default is blocks broken, so set it for builds:

   ```
   /shapeboard metric bigculo break    # digs (default)
   /shapeboard metric bigculo place    # builds and decoration
   /shapeboard metric bigculo both     # the sum
   ```

Everything broken or placed inside the shape and below the marker Y now counts into the vanilla
objectives `bigculo_break` and `bigculo_place`. Players walking in get a short message (once
every 15 minutes per zone) and a live top 15 sidebar; if they are not in the top 15, their own
line shows in the last slot. `/shapeboard hide` hides the sidebar, `/shapeboard quiet` mutes the
messages.

<!-- modrinth:end -->

## Details

### All commands

| Command | Who | What |
|---|---|---|
| `/shapeboard create <id> <block> <y> [x z]` | OP | Scan the marker line and create a shape. `x z` optional start point (required from console) |
| `/shapeboard createbox <id> <x1> <z1> <x2> <z2> <y>` | OP | Rectangular zone with no drawing at all: two opposite corners + the ceiling Y (only blocks below it count) |
| `/shapeboard rename <id> <name...>` | OP | Set the display name shown on the sidebar |
| `/shapeboard metric <id> <break\|place\|both>` | OP | What the leaderboard ranks by: blocks broken (default), placed, or the sum. Perfect for build/decoration zones |
| `/shapeboard total <id> <on\|off>` | OP | Show a **Total** line at the top of the sidebar with everyone's combined count (on by default) |
| `/shapeboard delete <id>` | OP | Delete the shape (scoreboard objectives are kept) |
| `/shapeboard list` | all | List shapes with area and marker info |
| `/shapeboard info <id>` | all | Details of one shape |
| `/shapeboard top [id]` | all | Top 10 + totals in chat |
| `/shapeboard hide` / `show` | all | Per-player sidebar toggle, remembered across sessions |
| `/shapeboard view <break\|place\|both\|reset>` | all | What **your** sidebar ranks by, whatever the zone is set to. `reset` follows the zone again |
| `/shapeboard quiet [on\|off]` | all | Mute the entered/left chat lines. The sidebar keeps working. No argument toggles it |
| `/shapeboard suffix <id> <word\|auto\|off>` | OP | Word after the display name on the sidebar title. `auto` turns into Dig / Build / Both, following what is being ranked |
| `/shapeboard prefix <id> <word\|off>` | OP | Word before the display name |
| `/shapeboard contains <id> <x> <z>` | OP | Debug: is this column inside the shape? |
| `/shapeboard scan <id>` | OP | Count the blocks still standing inside the shape (see [Dig progress](#dig-progress)) |
| `/shapeboard progress [id]` | all | Progress bar and remaining block count from the last scan |
| `/shapeboard layer [y] [id]` | all | One Y layer, measured against what that layer started with |
| `/shapeboard down <y> [id]` | all | Everything still standing from the top of the scan down to that Y: how much is left before the shape is clear to y |
| `/shapeboard blocks [id]` | all | Every block type still standing, best first (what you will be hauling out) |
| `/shapeboard range <id> <ymin> <ymax>` | OP | Y slice the scan covers (default: above the bedrock floor, up to the marker Y) |
| `/shapeboard baseline <id> <blocks>\|fromscan` | OP | How many blocks were there before digging started. `fromscan` freezes the last scan, `0` = use the raw volume |
| `/shapeboard untracked <id> <name>` | OP | Hold the digs nobody was credited for (pre-shape digs, explosions, world edits) under a fake name, resynced after every scan. `off` removes it |

### Two boards, one zone

A zone that gets dug out first and built up later needs two leaderboards in the
same place. It already has them: **breaks and placements are always counted into
two separate objectives**, `<id>_break` and `<id>_place`. The `metric` only picks
which one the sidebar shows, so nothing has to be duplicated and there is no
second shape to create (overlapping shapes would not work anyway: a column
belongs to the first shape that contains it).

```
/shapeboard metric virusia break     # dig phase, everyone sees the digs
/shapeboard metric virusia place     # building day, one command, same zone

/shapeboard view place               # any player, any time, just for them
/shapeboard view reset               # back to whatever the zone shows
```

`view` is per player and remembered across sessions, so a builder can watch the
placements while the diggers keep their own board. To make it obvious which one
you are looking at, give the shape an auto suffix:

```
/shapeboard rename virusia Virusia
/shapeboard suffix virusia auto
```

The sidebar title then reads **Virusia Dig**, **Virusia Build** or **Virusia
Both**, following whatever that viewer is ranking by. A fixed word works too
(`/shapeboard suffix virusia Build`), and `/shapeboard prefix` puts one in front.

### Dig progress

Break counters answer "who dug the most". They cannot answer "how much is left",
because TNT and world edits are not credited to anyone. `/shapeboard scan` answers
that directly: it counts the blocks **still standing** inside the shape.

```
/shapeboard scan bigculo
/shapeboard progress bigculo
```

```
Big Culo progress
████████░░░░░░░░░░░░ 41.74%
Progress: 280,374 / 671,744 blocks cleared, 391,370 remaining
Left to dig: deepslate 202,334, stone 115,216, tuff 15,297, diorite 12,677
```

A block counts as "still to dig" when it is solid, collidable and breakable.
Air, water, lava, leaves, grass and other decoration, and bedrock are all
skipped, so the bar reaches 100% when the hole is actually finished.

**The denominator.** By default it is the raw volume of the Y slice
(columns x height). That is exact and needs no setup, but natural caves and the
air above the terrain count as "already dug", so the bar starts above zero. For a
true reading, measure how many blocks were there before you started and set it:

```
/shapeboard baseline bigculo 148300000
```

You can get that number by scanning the same area on a copy of the world from
before the dig, or on a freshly generated world with the same seed.

**Making the board add up.** The per-player counters only see what players are
credited for. Blocks dug before the shape existed, explosions and world edits
are invisible to them, so the leaderboard total can sit well below what the scan
measured. Point a fake name at the difference and it stops getting lost:

```
/shapeboard untracked bigculo Untracked
```

It is recalculated after every scan, so the board always adds up to the real
hole. `/shapeboard untracked <id> off` removes it.

**Digging to a target Y.** Layer by layer is `/shapeboard layer`, and everything
above a target depth at once is `/shapeboard down`. It adds up every block still
standing from the top of the scan down to that Y, which is the question crews
actually ask ("how much before we are clear to y-20?"):

```
/shapeboard down -20 bigculo
```

```
Big Culo, down to y-20
███████░░░░░░░░░░░░░ 38.60%
Down to y-20: 210,455 / 545,180 blocks cleared, 334,725 remaining
Layers: 43 of 96 cleared from y99 down to y-20, highest one left is y41.
```

Both read from the last scan, so they answer instantly and cost nothing.

**Cost.** The scan runs on a worker thread and reads chunk data straight from
storage, so the server keeps ticking. A 4,096 column zone takes ~50 ms; a
800,000 column perimeter takes a few seconds. It saves the world first, so
freshly dug chunks are always included. Run it from a cron job (hourly or daily)
and `/shapeboard progress` answers instantly from the stored snapshot.

### How it works

- **Scan:** starting from a seed block, ShapeBoard walks the connected line of marker blocks at that exact Y (loading only the chunks the line passes through), auto-bridges gaps up to 6 blocks, then runs a flood fill from outside. Everything the flood cannot reach is inside. A diagonal line blocks the fill correctly, so outlines with 45 degree corners are fine.
- **Mask:** the result is stored as z-intervals per x column in `world/shapeboard/shapes.json`. A lookup is a couple of integer comparisons, so tracking costs nothing even with dozens of players digging.
- **Counting:** block breaks are counted through Fabric's break event, block placements through a tiny mixin. Only columns inside the shape and below the marker Y count. Scores go to plain vanilla scoreboard objectives, so everything else on your server (commands, datapacks, other mods) can read them.
- **Sidebar:** each viewer gets a client-only fake objective through targeted packets (`shapeboard_view`), refreshed only when the numbers change. Leaving the area removes it and restores whatever sidebar objective the server had displayed. Player names on the board keep their team colors.

### FAQ

**Does it need anything on the client?** No. Vanilla players see everything.

**What about TNT?** Blocks broken by explosions are not credited to anyone (the game does not attribute them to a player). Hand mining and tools count normally.

**Do carpet bots count?** Their breaks and placements count under the bot's own name, so a bot-heavy dig still shows who ran what. Bots never receive sidebars or chat messages.

**Multiple shapes?** Yes, create as many as you want. A player standing inside one sees that shape's board.

**What if my outline has a hole in it?** The scan fails and prints the coordinates of the open line ends. Patch them (or leave up to 6 blocks, which bridge automatically) and rerun create.

**Can I track a build or decoration area that has no outline?** Yes, two ways. If a rectangle is enough, `/shapeboard createbox <id> <x1> <z1> <x2> <z2> <y>` needs no blocks at all. For a custom shape, the marker line is only needed during the scan: draw a temporary outline with any cheap block at a Y above the build, run create, then remove the blocks; the shape keeps working forever (the mask is stored in `world/shapeboard/`). Either way, set `/shapeboard metric <id> place` so the leaderboard ranks by blocks placed instead of dug (or `both` for the sum).

**Can I track an area that already had digging?** Objectives start at zero when the shape is created. If you have previous numbers in another objective, copy them in with `/scoreboard players operation`.

**Does the scan lag the server?** It loads only the chunks the outline itself touches, once, at create time. A 4,000 block outline takes well under a second on a loaded world.

### Which jar

Every version gets its own jar, compiled against that version. The name always ends in the Minecraft version it is for.

| Minecraft | Jar | Java |
| --- | --- | --- |
| 1.21, 1.21.1 | `shapeboard-x.y.z+1.21.jar` | 21 |
| 1.21.2 | `shapeboard-x.y.z+1.21.2.jar` | 21 |
| 1.21.3 | `shapeboard-x.y.z+1.21.3.jar` | 21 |
| 1.21.4 | `shapeboard-x.y.z+1.21.4.jar` | 21 |
| 1.21.5 | `shapeboard-x.y.z+1.21.5.jar` | 21 |
| 1.21.6 | `shapeboard-x.y.z+1.21.6.jar` | 21 |
| 1.21.7 | `shapeboard-x.y.z+1.21.7.jar` | 21 |
| 1.21.8 | `shapeboard-x.y.z+1.21.8.jar` | 21 |
| 1.21.9 | `shapeboard-x.y.z+1.21.9.jar` | 21 |
| 1.21.10 | `shapeboard-x.y.z+1.21.10.jar` | 21 |
| 1.21.11 | `shapeboard-x.y.z+1.21.11.jar` | 21 |
| 26.1, 26.1.1, 26.1.2 | `shapeboard-x.y.z+26.1.2.jar` | 25 |
| 26.2 | `shapeboard-x.y.z+26.2.jar` | 25 |
| 26.3 | `shapeboard-x.y.z+26.3.jar` | 25 |

Minecraft 26.1 and up need Java 25: that is the game's requirement, not the mod's. The jar for a version will refuse to load on a different one rather than half work.

Minecraft 26.3 requires Fabric Loader 0.19.5 or newer and Fabric API for 26.3.

### Updating an existing world

Stop the server cleanly and back up the world. Replace the old ShapeBoard jar with the jar for your target Minecraft version, update Fabric Loader and Fabric API as needed, then start the server again. Keep only one ShapeBoard jar in `mods/`.

Existing shapes and totals are retained when you keep the same world. Shape definitions, scan snapshots, baselines and player preferences live in `world/shapeboard/`; individual break and placement scores live in the world's vanilla scoreboard data. Keep both when copying a world. Shapes do not need to be recreated or scanned again to resume counting. If `level-name` is customized, use that world directory instead of `world/`.

## Building from source

```
./gradlew build
```

The jar lands in `build/libs/`. Java 21+, Gradle downloads everything else. That builds for 1.21, which is the version the source tree is written against.

For every other Minecraft version:

```
python3 tools/multiversion.py            # all of them, into build/multiversion/
python3 tools/multiversion.py 26.2       # just one
python3 tools/multiversion.py 26.2 --errors   # compile only, list every error
```

One source tree, no branch per version: the differences between Minecraft releases are renames, and they live in a substitution table at the top of `tools/multiversion.py`. The 26 series also needs its own toolchain, since Minecraft stopped shipping obfuscated in 26.1 and there are no mappings to ask for any more, and that build script is derived from this one rather than kept beside it. Building the 26 jars locally needs a JDK 25 as well as the 21.

## License

MIT
