# svcntrl

A server-side version control mod for Minecraft Fabric (1.21.x).

svcntrl brings a simplified Git-like workflow (based on snapshots) directly into Minecraft for builders and server administrators. It allows you to select areas, save snapshots, create branches, and instantly restore states without causing server lag.

## Features & Commands

svcntrl uses a straight-forward snapshot system designed specifically to handle large amounts of blocks and entities safely. All heavy operations (NBT parsing, I/O) are offloaded to background threads.

### 🏗️ Projects & Selection
Everything starts with a **Project** (a cubic region in the world).
- **Selection:** Use a **Wooden Sword** (Left-click for pos1, Right-click for pos2) or use `/svcntrl pos1` and `/svcntrl pos2`.
- `/svcntrl project create <name>` - Create a new project from your selection.
- `/svcntrl select <name>` - Set your active project.
- `/svcntrl raycast` - Look at a project and right-click to select it visually.
- `/svcntrl outline` - Toggle particle borders for your active project.

*(Other project commands: `/svcntrl project list`, `info`, `rename`, `delete`)*

### 💾 Snapshots & Previews
Save your progress instantly and roll back whenever you make a mistake. The mod auto-saves a backup before any rollback so you never accidentally lose work.
- `/svcntrl save <snapshot_name>` - Save the current state of your project.
- `/svcntrl snapshots` - View the history of your saves.
- `/svcntrl preview start <id>` - Visually preview a past snapshot (renders phantom blocks over the world).
- `/svcntrl preview stop` - Exit preview mode.
- `/svcntrl restore <id>` - Revert your project to a past snapshot.

### 🌿 Branches
Want to try building a stone roof instead of a wooden one without destroying the original? Use branches.
- `/svcntrl branch create <name>` - Branch off your current progress.
- `/svcntrl branch checkout <name>` - Switch between designs instantly. The mod automatically saves your current branch before switching!
- `/svcntrl branch list` - See all active branches.

### 📦 Litematica Export & Cloud Sharing
You can export your builds directly to `.litematic` files. If playing in single-player, they save directly to your `schematics/svcntrl/` folder.
- `/svcntrl export <id>` - Export a specific snapshot.
- `/svcntrl export diff <id1> <id2>` - Export *only the exact differences* (a patch) between two snapshots.
- `/svcntrl export all` - Backup your entire project history to a `.zip`.

**☁️ Cloud Uploads:** If `allowPublicExport` is enabled in the server config, exporting will automatically upload your `.litematic` file to a temporary public cloud storage (`tmpfiles.org`) and generate a clickable download link in chat for easy sharing!

### 🤝 Teamwork & Access Control
- `/svcntrl trust <player>` - Give a friend permission to manage your project.
- `/svcntrl untrust <player>` - Revoke access.
*(Server Administrators (OP Level 3+) or players with the `svcntrl.admin` permission completely bypass these restrictions and can manage any project on the server).*

## Build
```bash
./gradlew build
```

## About Development
This mod was built in collaboration with an AI agent. The codebase has been thoroughly reviewed and tested to ensure strict conceptual integrity, complete functionality, and production-ready performance.
