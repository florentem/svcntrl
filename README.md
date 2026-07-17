# svcntrl

A server-side version control mod for Minecraft Fabric (1.21.8).

svcntrl brings a simplified Git-like workflow (based on snapshots) directly into Minecraft for builders and redstoners. It allows you to select areas, save snapshots, create branches, and instantly restore states without causing server lag.

## Features & Commands

svcntrl uses a straightforward snapshot system designed specifically to handle large amounts of blocks and entities safely. All heavy operations (NBT parsing, I/O) are offloaded to background threads.

### Projects & Selection
Everything starts with a **Project** (a cubic region in the world). You can freely create overlapping or nested projects to organize your builds (e.g., placing a redstone machine "sub-project" inside a larger city "project").
- `/svcntrl project create <name>` - Create a new project.
- `/svcntrl select <name>` - Set your active project.
- `/svcntrl select raycast` - Look at a project and right-click to select it visually.
- `/svcntrl outline` - Toggle particle borders for your active project.

*(Other project commands: `/svcntrl project list`, `remove`)*

### Snapshots & Previews
Save your progress and roll back when necessary. The mod auto-saves a backup before any rollback to prevent accidental data loss.
- `/svcntrl save <snapshot_description>` - Save the current state of your project.
- `/svcntrl log ["filter"] [page]` - View the history of your saves with smart text filtering.
- `/svcntrl preview start <id>` - Visually preview a past snapshot (renders phantom blocks over the world).
- `/svcntrl preview stop` - Exit preview mode.
- `/svcntrl restore <id> [--nosave] [--exclude-intersections]` - Revert your project to a past snapshot.
- `/svcntrl restore patch <target_id> <base_id> [--nosave] [--exclude-intersections]` - Apply only the difference between two snapshots (applies changes made in target relative to base).
- `/svcntrl restore patch cross <target_branch> <target_id> <base_branch> <base_id> [--nosave] [--exclude-intersections]` - Apply a patch between two snapshots from different branches.

> **Tip:** Use the `--exclude-intersections` flag when restoring a large project to skip modifying any blocks or entities that belong to overlapping sub-projects, keeping your isolated work safe.

### Branches
Test different design variations (e.g., trying a stone roof instead of a wooden one) without destroying the original structure.
- `/svcntrl branch create <name>` - Branch off your current progress.
- `/svcntrl branch checkout <name>` - Switch between designs. The mod automatically saves your current branch before switching.
- `/svcntrl branch list` - See all active branches.

### Litematica Export & Cloud Sharing
Export your builds directly to `.litematic` files. In single-player, they save directly to your `schematics/svcntrl/` folder.
- `/svcntrl export <id>` - Export a specific snapshot.
- `/svcntrl export diff <id1> <id2>` - Export only the exact differences (a patch) between two snapshots.
- `/svcntrl export all` - Backup your entire project history to a `.zip`.

**Cloud Uploads:** If `allowPublicExport` is enabled in the server config, exporting will automatically upload your `.litematic` file to a temporary public cloud storage (`tmpfiles.org`) and generate a clickable download link in chat.

### Teamwork & Access Control
- `/svcntrl project trust <player>` - Give another player permission to manage your project.
- `/svcntrl project untrust <player>` - Revoke access.
*(Server Administrators (OP Level 3+) or players with the `svcntrl.admin` permission bypass these restrictions and can manage any project on the server).*

## Build
```bash
./gradlew build
```

## About Development
This mod was built in collaboration with an AI agent. The codebase has been thoroughly reviewed and tested to ensure strict conceptual integrity, complete functionality, and production-ready performance.
