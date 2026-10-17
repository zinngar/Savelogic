# SaveLogic: Cloud Saves for Minecraft

A Fabric mod for **Minecraft 26.3** that saves your singleplayer worlds to a **private GitHub repository** and loads them back on any computer.

## Features

*   **Save to cloud:** pick a world and upload it. Uploading the same world again replaces the previous cloud copy.
*   **Load from cloud:** download a world into your saves folder. Existing local worlds are never overwritten; a download that would clash gets a name like `My World (2)`.
*   **Big worlds:** each world is stored as its own GitHub *release* and split into 1 GB parts, so there is no practical size limit, and old copies are deleted rather than piling up in git history.
*   **Safe re-uploads:** the new copy is fully uploaded before the old one is removed, so a failed upload never loses your cloud save.
*   **Progress bar:** uploads and downloads show a bar at the top of the menus with percent, speed and time left, plus a clear success or error message when they finish.
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

## Build

Requires a JDK 25 or newer.

```bash
./gradlew build
```

The mod jar is written to `build/libs/`. Install it together with Fabric Loader 0.19.5+ and Fabric API for 26.3.

## Support

If you find this mod useful, please consider supporting the developer:

[<img src="https://img.shields.io/badge/Donate-PayPal-blue.svg?logo=paypal" alt="Donate via PayPal">](https://www.paypal.com/donate/?business=RQFEAWX7E39CG&no_recurring=0&item_name=Support+Savelogic+Development+&currency_code=AUD)
## Security

Your token is stored encrypted in `config/savelogic.json`, with the key in `config/savelogic.key`. That keeps it from leaking if you share or screenshot your config, but anyone who can copy both files from your computer can use it.

*   **Use a fine-grained token limited to your saves repository**, so a leaked token can't touch anything else.
*   **Keep the saves repository private.** Test Connection warns you if it is public.
*   **If you think the token leaked**, revoke it on GitHub and paste a new one into Settings.
