# SaveLogic: Cloud Saves for Minecraft

A Fabric mod for **Minecraft 26.3** that saves your singleplayer worlds to a **private GitHub repository** and loads them back on any computer.

## Features

*   **Save to cloud:** pick a world and upload it. Uploading the same world again replaces the previous cloud copy.
*   **Load from cloud:** download a world into your saves folder. Existing local worlds are never overwritten; a download that would clash gets a name like `My World (2)`.
*   **Big worlds:** each world is stored as its own GitHub *release* and split into 1 GB parts, so there is no practical size limit, and old copies are deleted rather than piling up in git history.
*   **Safe re-uploads:** the new copy is fully uploaded before the old one is removed, so a failed upload never loses your cloud save.
*   **Progress bar:** uploads and downloads show a bar at the top of the menus with percent, speed and time left, plus a clear success or error message when they finish.
*   **Version history:** the last few versions of each world are kept (5 by default), each labelled with when and on which computer it was saved, and any of them can be restored. See [Playing on several computers](#playing-on-several-computers).
*   **No accidental overwrites:** if another computer saved a world after your copy was synced, SaveLogic asks before uploading over it, and warns when you open a world that is open on another computer or has a newer version in the cloud.
*   **Auto-sync:** synced worlds upload automatically when you leave them.
*   **Same mods everywhere:** upload your mods and mod configs once, then install that setup on any other computer with the same Minecraft version. See [Mods & configs](#mods--configs).
*   **Optional local backups:** zip a world into `.minecraft/savelogic-backups` every time you leave it.

## Setup

1.  **Create a private repository** on GitHub (for example `minecraft-saves`). It can be empty.
2.  **Create a fine-grained personal access token:**
    *   GitHub **Settings** > **Developer settings** > **Personal access tokens** > **Fine-grained tokens** > **Generate new token**.
    *   Under **Repository access**, choose **Only select repositories** and pick your saves repository.
    *   Under **Permissions**, set **Contents** to **Read and write**. That is the only permission needed.
    *   Generate the token and copy it.
3.  **In Minecraft:** on the title screen click the **☁** button next to Singleplayer, then **Settings**. Paste the repository URL and the token, press **Test Connection**, then **Done**.

Each world appears on your repository's **Releases** page as its own release, named after the world, with when it was last saved and its size. Don't delete those releases or their files; that is where your worlds live.

## Playing on several computers

A world becomes **synced** on a computer once you upload it from there or download it there. For synced worlds:

*   **Opening the world** first checks the cloud (it takes a moment):
    *   If it's open on another computer, you're told which one and since when, and can play anyway or cancel. If that computer crashed or was switched off mid-game, playing anyway is safe.
    *   If another computer saved a newer version, you can download it and play (your copy is backed up first), play your copy, or cancel.
    *   Otherwise it opens straight away and is marked as in use on this computer.
*   **Leaving the world** uploads it (if *Auto-upload* is on in Settings) and marks it as no longer in use. Wait for the progress bar to finish before quitting Minecraft. If another computer saved a newer version in the meantime, the automatic upload doesn't overwrite it and tells you instead.
*   **Uploading by hand** (*Save World to Cloud*) asks first if another computer saved a newer version: upload yours (theirs stays in history), upload yours as a separate world, or cancel.
*   **Downloading a world you already have** asks whether to replace your copy (it's moved to `savelogic-backups/worlds` first) or keep both.
*   **Version history:** *Load World from Cloud > Versions* lists the kept versions with when and where each was saved. Click one to restore it. *Settings > Keep versions* sets how many are kept (1, 3, 5 or 10). Each version takes space in your repository's releases.
*   Give each computer a recognisable name in *Settings* so the history and warnings make sense.

Worlds uploaded before version history existed show up as saved on "another computer". The first time you upload such a world from a computer, SaveLogic asks first, because that copy has never been synced. Choose *Upload mine*.

## Mods & configs

Keep the same mods and settings on every computer:

1.  On the computer with your setup: **☁ > Upload My Mods & Configs**. This uploads every `.jar` in your `mods` folder and everything in your `config` folder.
2.  On another computer (with the same Minecraft version, Fabric Loader and SaveLogic installed): **☁ > Install Mods & Configs from Cloud**, then close Minecraft when asked.
3.  Start Minecraft again. Your mods and configs now match.

Good to know:

*   A running game can't replace its own mods, so the install finishes **after Minecraft closes**. A small helper waits for the game to exit, then swaps the files and writes `logs/savelogic-setup-install.log`.
*   Your current mods are moved, and any configs that get replaced are copied, to `savelogic-backups/setup-before-<date>` first. To undo, copy them back.
*   Mods that aren't in the setup are removed from `mods`; config files that aren't in the setup are left alone.
*   SaveLogic's own jar and its token files (`config/savelogic.json`, `config/savelogic.key`) are never uploaded or replaced.
*   Setups are stored per Minecraft version (one release per version), so a setup made on 26.3 only installs on 26.3.
*   Some mods keep personal data in their config files. The saves repository is private, but keep that in mind before sharing it.

## Minecraft versions

Each Minecraft version has its own branch, and releases are numbered `<mod version>+<Minecraft version>`:

| Minecraft | Branch | Mod version | Java |
|---|---|---|---|
| 26.3 | [`26.3`](https://github.com/zinngar/Savelogic/tree/26.3) | 2.0.0+26.3 | 25 |
| 1.21.11 | [`1.21.11`](https://github.com/zinngar/Savelogic/tree/1.21.11) | 2.0.0+1.21.11 | 21 |
| 1.21.10 | [`1.21.10`](https://github.com/zinngar/Savelogic/tree/1.21.10) | 0.1.x (old version, not maintained) | 21 |

Worlds and mods & configs can be shared between computers on the same Minecraft version. A world saved on a newer Minecraft version won't open on an older one.

## Build

Requires a JDK 25 or newer.

```bash
./gradlew build
```

The mod jar is written to `build/libs/`, named with the mod version and the Minecraft version it's for, e.g. `savelogic-2.0.0+26.3.jar`. Install it together with Fabric Loader 0.19.5+ and Fabric API for 26.3.

## Support

If you find this mod useful, please consider supporting the developer:

[<img src="https://img.shields.io/badge/Donate-PayPal-blue.svg?logo=paypal" alt="Donate via PayPal">](https://www.paypal.com/donate/?business=RQFEAWX7E39CG&no_recurring=0&item_name=Support+Savelogic+Development+&currency_code=AUD)
## Security

Your token is stored encrypted in `config/savelogic.json`, with the key in `config/savelogic.key`. That keeps it from leaking if you share or screenshot your config, but anyone who can copy both files from your computer can use it.

*   **Use a fine-grained token limited to your saves repository**, so a leaked token can't touch anything else.
*   **Keep the saves repository private.** Test Connection warns you if it is public.
*   **If you think the token leaked**, revoke it on GitHub and paste a new one into Settings.
