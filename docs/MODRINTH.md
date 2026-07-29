# Modrinth publishing checklist (ShapeBoard)

Internal note. Not part of the mod.

## Why the first submission was rejected

Content Rules 5.1 (Environment Metadata). Nothing to do with the code or the
description. Modrinth replaced the old two-field `Client-side / Server-side`
(required / optional / unsupported) system with a nine-option picker in
**Project settings -> Environments**, and the project was still carrying the
old defaults.

Reference: https://modrinth.com/news/article/new-environments

## The nine options, and ours

| Option | API value |
|---|---|
| Client-side only | `client_only` |
| **Server-side only / Works in singleplayer** | **`server_only`** |
| Server-side only / Dedicated server only | `dedicated_server_only` |
| Client and server / Required on both | `client_and_server` |
| Client and server / Optional on client | `server_only_client_optional` |
| Client and server / Optional on server | `client_only_server_optional` |
| Client or server / Works best on both | `client_or_server_prefers_both` |
| Client or server / Works the same on either | `client_or_server` |
| Singleplayer only | `singleplayer_only` |

ShapeBoard is **`server_only`** ("Server-side only / Works in singleplayer"):
it runs entirely on the server, vanilla clients install nothing, and because
`fabric.mod.json` declares `"environment": "*"` it also loads inside the
integrated server, so singleplayer and LAN worlds work.

Do NOT pick `dedicated_server_only` unless `fabric.mod.json` is changed to
`"environment": "server"` -- that would stop it loading in singleplayer, and
then the two would disagree again.

Set it in **both** places:
- Project settings -> Environments
- Each version's settings (the per-version environment fields)

## Project fields

- **Name:** ShapeBoard
- **Slug:** `shapeboard`
- **Summary:** Scoreboards for areas of ANY shape, not just boxes. Outline your
  dig or build zone with marker blocks and track every block broken and placed
  inside it, with a per-player sidebar. Server-side only.
- **Categories:** Utility, Management
- **Loaders:** Fabric
- **Game versions:** 1.21, 1.21.1
- **License:** MIT
- **Source code:** https://github.com/CodeW4VE/ShapeBoard
- **Issue tracker:** https://github.com/CodeW4VE/ShapeBoard/issues
- **Description:** the contents of `README.md`, minus the "Building from
  source" section. Point the demo GIF at the raw GitHub URL so it renders:
  `https://raw.githubusercontent.com/CodeW4VE/ShapeBoard/main/docs/demo.gif`

## Version upload

- **Version number:** matches the jar, e.g. `1.3.0+1.21`
- **Channel:** Release
- **Files:** `build/libs/shapeboard-1.3.0+1.21.jar` (primary).
  Optionally the `-sources.jar` as an additional file.
- **Dependencies:** Fabric API -- **required**
- **Environment:** `server_only` (see above)
- **Changelog:** copy from the GitHub release / git tag

## Before hitting resubmit

- [ ] Environments set on the project
- [ ] Environments set on every uploaded version
- [ ] Fabric API listed as a required dependency
- [ ] Description renders (GIF loads, tables look right)
- [ ] Gallery has at least one image
- [ ] Reply in the moderation thread saying the environment metadata was fixed
