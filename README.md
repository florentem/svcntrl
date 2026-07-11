# svcntrl

A server-side version control mod for Minecraft Fabric (1.21.x).

svcntrl brings a Git-like workflow directly into Minecraft for builders and server administrators. It allows you to select areas, save snapshots, create branches, and instantly restore states without causing server lag.

## Key Features
- **Git-like Workflow:** Create branches for different build designs (e.g. `main`, `wood_roof`, `stone_roof`) and seamlessly switch between them.
- **Safe Restores:** Instantly rollback changes. The mod automatically creates safety snapshots before restoring, so you never lose progress.
- **Litematica Export:** Export entire projects or precise patch diffs to `.litematic` format to share with others.
- **High Performance:** All heavy operations (NBT parsing, I/O, Litematica conversions) run asynchronously in the background.

## Build
```bash
./gradlew build
```
