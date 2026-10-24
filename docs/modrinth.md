<!--
Text for the SaveLogic page on modrinth.com. Copy the summary into "Summary" and everything below
"Description" into the project's description editor.
-->

# Summary

Cloud saves for singleplayer: sync your worlds, mods and configs between computers through your own private GitHub repository, with version history and fast change-only uploads.

# Description

**SaveLogic** keeps your singleplayer worlds in the cloud, so you can play the same world on any computer. Your saves are stored in **your own private GitHub repository**: no SaveLogic server, no extra account, and nothing stored anywhere you don't control.

## Features

- **Cloud saves.** Upload a world from the ☁ button next to Singleplayer, and load it on another computer.
- **Fast saves.** After a world's first upload, only the files that changed are sent, usually a few MB instead of the whole world. A computer whose copy is up to date downloads only the newer changes.
- **You choose what syncs.** Only the worlds you pick go to the cloud, so test worlds stay local. Synced worlds upload when you leave them. Opening one checks for a newer version from your other computer first.
- **No accidental overwrites.** If another computer saved a newer version, SaveLogic asks before overwriting it and shows when each copy was last played. It also warns you if the world is still open on another computer.
- **Version history.** The last few versions of each world are kept (5 by default), each labelled with when and on which computer it was saved. Restore any of them in two clicks.
- **Big worlds welcome.** Worlds are split into 1 GB parts, so there's no practical size limit.
- **Same mods everywhere.** Upload your mods and configs once and install them on another computer. The swap happens after Minecraft closes, and your old mods are backed up first.
- **Schematics everywhere.** Your Litematica schematics sync between computers automatically. Deleted ones are backed up, and if one was changed on two computers, both copies are kept.
- **Check for mod updates.** Finds newer versions of your mods on Modrinth for your Minecraft version, adds any libraries they need, and installs them after Minecraft closes, again with a backup.
- **Progress bar** for every upload and download, with speed and time left.

## Setup

1. Create a **private** repository on GitHub (for example `minecraft-saves`).
2. Create a **fine-grained personal access token** with access to only that repository and **Contents: Read and write**.
3. In Minecraft, click **☁** next to Singleplayer, then **Settings**. Paste the repository URL and the token, then press **Test**.

Your worlds then appear on the repository's **Releases** page, one release per world. Full instructions are in the [README](https://github.com/zinngar/Savelogic#readme).

*Check for Mod Updates* works without any setup.

## Requirements

- Fabric Loader and [Fabric API](https://modrinth.com/mod/fabric-api)
- Client only. It does nothing on servers.
- Available for **Minecraft 26.3** (Java 25) and **Minecraft 1.21.11** (Java 21). Download the file for your Minecraft version.

## Privacy and network use

SaveLogic only connects to the internet when you use it, and only to:

- **GitHub** (`api.github.com`, `uploads.github.com`): the repository *you* configure, to upload and download your worlds, your mods & configs and your schematics, and to record which of your computers has a world open.
- **Modrinth** (`api.modrinth.com`, `cdn.modrinth.com`): when you press *Check for Mod Updates*, to look up your installed mods by their file fingerprints and download the updates you choose.

There is no telemetry and no SaveLogic server. Your GitHub token is stored encrypted on your computer and is never uploaded with your worlds or settings. Keep the saves repository **private**: *Settings > Test* warns you if it's public.

## Good to know

- This is a **beta**. Keep backups of worlds you care about.
- Wait for the progress bar to finish before quitting Minecraft, or the upload in progress is lost (the previous cloud version stays safe).
- Source code, issues and changelog are on [GitHub](https://github.com/zinngar/Savelogic). Licensed under MIT.
