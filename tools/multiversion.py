#!/usr/bin/env python3
"""Builds the mod for every Minecraft version we ship, from the one source tree.

ShapeBoard is server side only, so the churn that makes client mods expensive to port, the
drawing layer being replaced twice in a year, never touches it. What is left is renames, and a
table of substitutions says them in one place better than the same file forked across branches.

The source tree is the 1.21 one. Each version is built by copying the tree, applying that
version's substitutions, and compiling. The compiler checks the result against the real mappings,
which matters because most of these builds are never played: a method that does not exist fails
the build here rather than crashing someone's server later.

Two build scripts, not one, because 26.x is a different toolchain: Mojang stopped shipping the
mappings, so `officialMojangMappings()` has nothing to find and Loom split in two. The 26.x
script is derived from the 1.21 one rather than kept beside it, so the two cannot drift.

    python3 tools/multiversion.py                 # build every shipped version
    python3 tools/multiversion.py 26.2            # just one
    python3 tools/multiversion.py 26.2 --errors   # compile only, list every error

`--errors` skips the jar and prints the full compiler output grouped by file, which is what you
want while a port is still red.
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORK = ROOT / "build" / "multiversion"
VARIANTS = ROOT / "variants"
# Fedora has no java-21 package, so locally this is the Temurin unpacked by hand. On CI the
# runner has already set JAVA_HOME to a 21, and that is the one to use.
JDK = pathlib.Path(os.environ.get("JAVA_HOME") or pathlib.Path.home() / ".local/opt/jdk-21")

# Minecraft version -> the Fabric API build for it. Anything not listed is not shipped.
TARGETS = {
    "1.21": "0.102.0+1.21",
    "1.21.1": "0.115.6+1.21.1",
    "1.21.2": "0.106.1+1.21.2",
    "1.21.3": "0.114.1+1.21.3",
    "1.21.4": "0.119.4+1.21.4",
    "1.21.5": "0.128.2+1.21.5",
    "1.21.6": "0.128.2+1.21.6",
    "1.21.7": "0.129.0+1.21.7",
    "1.21.8": "0.136.1+1.21.8",
    "1.21.9": "0.134.1+1.21.9",
    "1.21.10": "0.138.4+1.21.10",
    "1.21.11": "0.141.6+1.21.11",
    "26.1.2": "0.155.2+26.1.2",
    "26.2": "0.156.0+26.2",
}

# Versions a shipped jar also runs on, so they are declared rather than built. 26.1 and 26.1.1
# share a Fabric API build with 26.1.2 and nothing this mod touches changed between them.
COVERS = {
    "1.21": ["1.21.1"],
    "26.1.2": ["26.1", "26.1.1"],
}

# Built from the same jar as the version above it, so there is no separate row here: the jar for
# 1.21 declares 1.21.1 through COVERS. Kept out of TARGETS so nothing tries to compile it.
SKIP = {"1.21.1"}

# Release order. Variant directories inherit forwards along it, so it has to be the real order
# and not the order of the table above.
ORDER = ["1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8",
         "1.21.9", "1.21.10", "1.21.11", "26.1.2", "26.2"]


def unobfuscated(version):
    """True from 26.1 on, which is where Minecraft stopped shipping obfuscated.

    Everything about how a mod is built changes at that line. Mojang no longer publishes the
    mappings at all, so `officialMojangMappings()` has nothing to find, and Loom split in two:
    `fabric-loom-remap` still remaps a jar written against mapped names, and the plain
    `fabric-loom` builds against the game as it is. A mod cannot use one build script for both.

    Told apart by the version number rather than a list, because the numbering changed at the
    same time: 1.x is the old scheme, 26.x is year-and-release.
    """
    return not version.startswith("1.")


def jdk_for(version):
    """Which JDK compiles this version. The 26 series refuses to configure on anything but 25.

    Loom checks the game's own `javaVersion` before it does anything else, so a 21 does not get
    as far as an error you could read: it stops at "Minecraft 26.2 requires Java 25".
    """
    if not unobfuscated(version):
        return JDK

    candidates = [os.environ.get("SHAPEBOARD_JDK25"),
                  os.environ.get("JAVA_HOME_25_X64"),
                  os.environ.get("JAVA_HOME_25"),
                  pathlib.Path.home() / ".local/opt/jdk-25",
                  "/usr/lib/jvm/java-25-openjdk"]

    for candidate in candidates:
        if candidate and pathlib.Path(candidate).is_dir():
            return pathlib.Path(candidate)

    return JDK


# What changed and when. Applied in order to every source file, for versions at or above the key.
#
# 1.21.2 dropped Level.getMinBuildHeight. The replacement, dimensionType().minY(), exists in every
# version from 1.21 on, so the source tree already uses it and there is no row for 1.21.2 here.

# 1.21.5 turned the chat click and hover events into sealed interfaces with a record per action,
# and every NBT getter into an Optional with an `OrElse` twin beside it. The twins return exactly
# what the old getters returned when a key was missing: an empty compound, a zero, an empty
# string. So all of this is renaming, which is why it is a table and not a fork.
SINCE_1_21_5 = [
    ("new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)", "new ClickEvent.RunCommand(cmd)"),
    ('new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to run"))',
     'new HoverEvent.ShowText(Component.literal("Click to run"))'),
    # All the NBT reads in the mod are in VolumeScanner, walking chunk sections off disk.
    ('root.getList("sections", Tag.TAG_COMPOUND)', 'root.getListOrEmpty("sections")'),
    ('states.getList("palette", Tag.TAG_COMPOUND)', 'states.getListOrEmpty("palette")'),
    ('sections.getCompound(i)', 'sections.getCompoundOrEmpty(i)'),
    ('palette.getCompound(i)', 'palette.getCompoundOrEmpty(i)'),
    ('section.getByte("Y")', 'section.getByteOr("Y", (byte) 0)'),
    ('section.contains("block_states", Tag.TAG_COMPOUND)', 'section.contains("block_states")'),
    ('section.getCompound("block_states")', 'section.getCompoundOrEmpty("block_states")'),
    ('.getString("Name")', '.getStringOr("Name", "")'),
    # An absent long array used to read as a zero length one, and the caller checks for that on
    # the next line, so the empty array keeps the old meaning.
    ('states.getLongArray("data")', 'states.getLongArray("data").orElse(new long[0])'),
    # Tag was imported for the type constants the getters above no longer take.
    ("import net.minecraft.nbt.Tag;\n", ""),
]

# 1.21.11 renamed a pile of things that had kept their names since forever, none of which change
# what anything does: ResourceLocation is called Identifier, the key inside a ResourceKey is
# reached with identifier() rather than location(), and a command's permission is a check object
# rather than a number. Level 2, what every editing subcommand asked for, is LEVEL_GAMEMASTERS.
SINCE_1_21_11 = SINCE_1_21_5 + [
    ("ResourceLocation", "Identifier"),
    (".dimension().location()", ".dimension().identifier()"),
    (".requires(s -> s.hasPermission(2))",
     ".requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))"),
]

# 26.1 is the unobfuscated one. For a server side mod the whole of it is the toolchain, which is
# handled in unobfuscate_build and in the manifest, so this row exists to be the place the next
# rename goes rather than because it carries one today.
SINCE_26_1 = SINCE_1_21_11 + []

RULES = {
    "1.21.5": SINCE_1_21_5,
    "1.21.6": SINCE_1_21_5,
    "1.21.7": SINCE_1_21_5,
    "1.21.8": SINCE_1_21_5,
    "1.21.9": SINCE_1_21_5,
    "1.21.10": SINCE_1_21_5,
    "1.21.11": SINCE_1_21_11,
    "26.1.2": SINCE_26_1,
    "26.2": SINCE_26_1,
}


def rules_for(version):
    return RULES.get(version, [])


def variant_dirs_for(version):
    """Every variant directory that applies to this version, oldest first.

    Nothing needs one today: every difference so far has been a rename. The mechanism is here
    because the moment one of them is not, forking a single file beats bending the table around
    it, and by then the shape of the answer should already be in the repository.
    """
    upto = ORDER[:ORDER.index(version) + 1]
    return [VARIANTS / name for name in upto if (VARIANTS / name).is_dir()]


def apply_variants(version, target):
    """Copy variant files over the working tree. Returns the paths that came from a variant.

    Those paths are then left out of the substitution pass: a variant file is already written for
    the version it is under, so running the rename table over it would be a second guess at
    something that is already right.
    """
    overridden = set()

    for directory in variant_dirs_for(version):
        for source in directory.rglob("*"):
            if not source.is_file():
                continue

            destination = target / source.relative_to(directory)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            overridden.add(destination.resolve())

    return overridden


def unobfuscate_build(path):
    """Turn the build script into the one a 26.x mod uses.

    Rewritten rather than kept as a second file next to it: the rest of the script, the version
    string, the LICENSE in the jar, the sources jar, is the same in both worlds, and two copies
    of it would drift apart the first time either is touched.

    What actually differs is small and all of it is forced:
      - the plugin no longer remaps anything, so it is `fabric-loom`, not `fabric-loom-remap`
      - there are no mappings to ask for
      - a dependency is a plain dependency: nothing needs remapping on the way in either
      - Java 25, because that is what the game runs on
    """
    text = path.read_text(encoding="utf-8")
    text = text.replace("id 'net.fabricmc.fabric-loom-remap'", "id 'net.fabricmc.fabric-loom'")
    text = text.replace("\tmappings loom.officialMojangMappings()\n", "")
    text = text.replace("modImplementation ", "implementation ")
    text = text.replace("modCompileOnly ", "compileOnly ")
    text = text.replace("it.options.release = 21", "it.options.release = 25")
    text = text.replace("JavaVersion.VERSION_21", "JavaVersion.VERSION_25")
    text = text.replace("JavaLanguageVersion.of(21)", "JavaLanguageVersion.of(25)")
    path.write_text(text, encoding="utf-8")


def mod_version():
    text = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    return text.split("mod_version=")[1].splitlines()[0].strip()


def report(errors, target, limit=12):
    """Print compiler errors grouped by file, worst file first.

    javac emits them in whatever order it got to them, which for a port reads as noise. Grouped
    by file it reads as a plan: this file is the work, that one is two lines.
    """
    by_file = {}

    for line in errors:
        name = line.split(".java:")[0].split("/")[-1] + ".java" if ".java:" in line else "?"
        by_file.setdefault(name, []).append(line.strip().replace(str(target) + "/", ""))

    for name, lines in sorted(by_file.items(), key=lambda item: -len(item[1])):
        print(f"     {name}: {len(lines)}")

        for line in lines if limit is None else lines[:limit]:
            print("        ", line)


def accepted_range(version):
    """What the jar's manifest says it runs on.

    A jar covers the versions listed for it in COVERS as well as its own, and Fabric wants that
    as a range. Without this every jar would keep the range from the source tree and Fabric would
    happily load a 1.21 build on 26.2, where it dies on the first command it registers.
    """
    covered = sorted([version] + COVERS.get(version, []),
                     key=lambda name: ORDER.index(name) if name in ORDER else 0)

    if len(covered) == 1:
        return version

    return f">={covered[0]} <={covered[-1]}"


def build(version, api, errors_only=False):
    target = WORK / version
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)

    for item in ["src", "gradle", "gradlew", "build.gradle", "settings.gradle",
                 "gradle.properties"]:
        source = ROOT / item
        destination = target / item

        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)

    properties = target / "gradle.properties"
    lines = []

    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("minecraft_version="):
            line = f"minecraft_version={version}"
        elif line.startswith("fabric_api_version="):
            line = f"fabric_api_version={api}"

        lines.append(line)

    properties.write_text("\n".join(lines) + "\n", encoding="utf-8")

    manifest = target / "src" / "main" / "resources" / "fabric.mod.json"
    text = manifest.read_text(encoding="utf-8")
    text = re.sub(r'"minecraft": "[^"]*"', f'"minecraft": "{accepted_range(version)}"', text)

    # 26.x runs on Java 25 and nothing older, and its mixins are compiled to match.
    if unobfuscated(version):
        text = re.sub(r'"java": ">=\d+"', '"java": ">=25"', text)

    manifest.write_text(text, encoding="utf-8")

    if unobfuscated(version):
        unobfuscate_build(target / "build.gradle")
        mixins = target / "src" / "main" / "resources" / "shapeboard.mixins.json"
        mixins.write_text(mixins.read_text(encoding="utf-8")
                          .replace('"JAVA_21"', '"JAVA_25"'), encoding="utf-8")

    overridden = apply_variants(version, target)
    applied = 0

    for path in (target / "src").rglob("*.java"):
        if path.resolve() in overridden:
            continue

        original = path.read_text(encoding="utf-8")
        patched = original

        for old, new in rules_for(version):
            patched = patched.replace(old, new)

        if patched != original:
            path.write_text(patched, encoding="utf-8")
            applied += 1

    print(f"  {version}: {len(overridden)} variant files, patched {applied}, building...",
          flush=True)
    task = "compileJava" if errors_only else "build"
    result = subprocess.run(
        ["./gradlew", task, "-q", "--console=plain"],
        cwd=target, capture_output=True, text=True,
        env={**os.environ, "JAVA_HOME": str(jdk_for(version))})

    if result.returncode != 0:
        errors = [line for line in (result.stdout + result.stderr).splitlines()
                  if "error:" in line]
        print(f"  {version}: FAILED, {len(errors)} errors")
        report(errors, target, limit=None if errors_only else 12)
        return None

    if errors_only:
        print(f"  {version}: compiles clean")
        return None

    jars = [jar for jar in (target / "build" / "libs").glob("*.jar")
            if "sources" not in jar.name]

    if not jars:
        print(f"  {version}: built but produced no jar")
        return None

    out = WORK / f"shapeboard-{mod_version()}+{version}.jar"
    shutil.copy2(jars[0], out)
    print(f"  {version}: OK -> {out.name}")
    return out


def main():
    arguments = sys.argv[1:]
    errors_only = "--errors" in arguments
    wanted = [item for item in arguments if not item.startswith("--")] or list(TARGETS)
    WORK.mkdir(parents=True, exist_ok=True)
    built = {}

    for version in wanted:
        if version in SKIP:
            print(f"  {version}: covered by another jar, not built")
            continue

        if version not in TARGETS:
            print(f"  {version}: not a version we know about")
            continue

        jar = build(version, TARGETS[version], errors_only)

        if jar:
            built[version] = jar.name

    if errors_only:
        return 0

    print("\nbuilt:", json.dumps(built, indent=2))
    return 0 if len(built) == len([v for v in wanted if v not in SKIP]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
