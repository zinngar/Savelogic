## SaveLogic 2.0.0

* **Updated to Minecraft 1.21.11** (Fabric Loader 0.19.5+, Java 21). The same version is also available for Minecraft 26.3.
* **Cloud saves actually upload now.** Fixed a crash when uploading (OkHttp's Kotlin dependency was missing from the jar) and tokens that were never decrypted after loading the config.
* **Big worlds:** each world is stored as its own GitHub release, named after the world, and split into 1 GB parts instead of committed to the repo, which was capped at 100 MB and grew forever.
* **Downloads over 1 MB work**, and they unzip into their own world folder instead of spilling into `saves/`.
* **Progress bar for uploads and downloads** at the top of the menus (percent, MB, speed, time left), with the result shown when they finish.
* **Cloud Saves is a ☁ button next to Singleplayer**, so it is always on screen at any GUI scale.
* **Test Connection** now checks what you typed, and warns if the token can't write or the repository is public.
* **Choose Synced Worlds:** see which worlds sync with the cloud, start syncing a world with one click, or stop syncing one (it stays on your computer, and its cloud copy is kept). New worlds stay on your computer until you sync them.
* **Schematics sync:** your Litematica `schematics` folder stays the same on every computer. It syncs automatically at startup and when you leave a world (or with *Sync Schematics*); deletions follow with a local backup, and a schematic changed on two computers keeps both copies.
* **Check for Mod Updates:** finds newer versions of your mods on Modrinth for your Minecraft version (plus any libraries they need) and installs them after Minecraft closes, with a backup.
* **Fast saves:** after a world's first upload, only the files that changed are uploaded, and computers that are in step download only the changes.
* **One question for old copies:** opening an unsynced world that has the same name as a cloud world asks which copy to use, showing when each was last played; prompts everywhere now show last-played times.
* **Version history and safe syncing between computers:** each world keeps its last versions (5 by default) with the computer and time each was saved, and any version can be restored. Uploads ask before overwriting newer progress from another computer, opening a world warns if it's open elsewhere or there's a newer version, and synced worlds upload automatically when you leave them.
* **Sync your mods and configs:** upload your mods and config folders and install them on another computer; the swap happens after Minecraft closes, with a backup of what was there before.
* Fixed local auto-backups zipping the wrong folder.
* Removed Google Drive support. Existing users need to paste their GitHub token into Settings again.
