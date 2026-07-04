# ShapeBoard

*Read this in [Español](README_es.md).*

**Scoreboards for areas of ANY shape, not just boxes.** Outline your dig zone, perimeter or build area with a line of marker blocks in the sky, run one command, and ShapeBoard tracks every block broken and placed inside that exact shape. When someone walks in, a leaderboard sidebar appears just for them. When they walk out, it goes away.

100% server-side. Players do not install anything.

## See it in action

![Flying out of the area removes the sidebar, flying back in restores it, live scores included](docs/demo.gif)

Full quality video with sound: [shapeboard-demo.mp4](https://github.com/CodeW4VE/ShapeBoard/releases/download/v1.0.1/shapeboard-demo.mp4)

## Why

Tools like buildevents can only track rectangular boxes. Real projects are not rectangles: perimeters have round corners, rivers you want to skip, weird bulges. If you track a box around a round perimeter, people mining outside the actual project still climb your leaderboard. ShapeBoard follows the exact outline you drew, so the count is fair.

The sidebar is also per-player for real. It is sent with targeted scoreboard packets, so only the people standing inside the shape see it. No teams are touched, no team colors are changed, and two players who happen to share a team color do not leak the board to each other. Whatever your server uses teams and colors for stays intact.

## Install

1. Fabric server for Minecraft 1.21 with [Fabric API](https://modrinth.com/mod/fabric-api).
2. Drop `shapeboard-x.y.z+1.21.jar` into `mods/`.
3. Restart. Done: there is no config file to edit.

## Quick start

Say TVTvirus is admin and wants to track his server's perimeter dig:

1. **Draw the outline.** Fly to a Y above the build (for example y150) and draw one continuous line of a marker block (for example black concrete) tracing the shape of the project, until it closes on itself. Any shape works: circles, blobs, donut holes, cut corners. Small gaps up to 6 blocks are bridged automatically; bigger gaps make the scan fail and it tells you the exact coordinates of the open ends so you can go patch them.

2. **Create the shape.** Stand anywhere within 64 blocks of the line (or pass explicit coordinates from the console) and run:

   ```
   /shapeboard create bigculo minecraft:black_concrete 150
   ```

   ShapeBoard walks the whole line, closes the shape, and reports the area:

   ```
   [ShapeBoard] Shape 'bigculo' created in 45 ms: 797,680 columns inside,
   outline of 4,350 blocks. Objectives: bigculo_break / bigculo_place.
   ```

   **Just want a rectangle?** Skip the drawing entirely: give it two opposite corners and the ceiling Y instead:

   ```
   /shapeboard createbox spawnzone 30640 4768 31631 6079 150
   ```

3. **Give it a pretty name.** This is what players see on the sidebar:

   ```
   /shapeboard rename bigculo Big Culo
   ```

4. **That is it.** From now on, everything mined or placed inside the shape and below the marker line counts into the vanilla scoreboard objectives `bigculo_break` and `bigculo_place`. Anyone who walks in gets:

   ```
   [ShapeBoard] You entered Big Culo. Blocks you mine or place here count toward the leaderboard.
   [ShapeBoard] Sidebar enabled. Run /shapeboard hide to hide it.
   ```

   with a live top 15 sidebar. If they are not in the top 15 but have a score, their own line is shown in the last slot so they always see where they stand.

## Commands

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
| `/shapeboard contains <id> <x> <z>` | OP | Debug: is this column inside the shape? |

## How it works

- **Scan:** starting from a seed block, ShapeBoard walks the connected line of marker blocks at that exact Y (loading only the chunks the line passes through), auto-bridges gaps up to 6 blocks, then runs a flood fill from outside. Everything the flood cannot reach is inside. A diagonal line blocks the fill correctly, so outlines with 45 degree corners are fine.
- **Mask:** the result is stored as z-intervals per x column in `world/shapeboard/shapes.json`. A lookup is a couple of integer comparisons, so tracking costs nothing even with dozens of players digging.
- **Counting:** block breaks are counted through Fabric's break event, block placements through a tiny mixin. Only columns inside the shape and below the marker Y count. Scores go to plain vanilla scoreboard objectives, so everything else on your server (commands, datapacks, other mods) can read them.
- **Sidebar:** each viewer gets a client-only fake objective through targeted packets (`shapeboard_view`), refreshed only when the numbers change. Leaving the area removes it and restores whatever sidebar objective the server had displayed. Player names on the board keep their team colors.

## FAQ

**Does it need anything on the client?** No. Vanilla players see everything.

**What about TNT?** Blocks broken by explosions are not credited to anyone (the game does not attribute them to a player). Hand mining and tools count normally.

**Do carpet bots count?** Their breaks and placements count under the bot's own name, so a bot-heavy dig still shows who ran what. Bots never receive sidebars or chat messages.

**Multiple shapes?** Yes, create as many as you want. A player standing inside one sees that shape's board.

**What if my outline has a hole in it?** The scan fails and prints the coordinates of the open line ends. Patch them (or leave up to 6 blocks, which bridge automatically) and rerun create.

**Can I track a build or decoration area that has no outline?** Yes, two ways. If a rectangle is enough, `/shapeboard createbox <id> <x1> <z1> <x2> <z2> <y>` needs no blocks at all. For a custom shape, the marker line is only needed during the scan: draw a temporary outline with any cheap block at a Y above the build, run create, then remove the blocks; the shape keeps working forever (the mask is stored in `world/shapeboard/`). Either way, set `/shapeboard metric <id> place` so the leaderboard ranks by blocks placed instead of dug (or `both` for the sum).

**Can I track an area that already had digging?** Objectives start at zero when the shape is created. If you have previous numbers in another objective, copy them in with `/scoreboard players operation`.

**Does the scan lag the server?** It loads only the chunks the outline itself touches, once, at create time. A 4,000 block outline takes well under a second on a loaded world.

## Building from source

```
./gradlew build
```

The jar lands in `build/libs/`. Java 21+, Gradle downloads everything else.

## License

MIT
