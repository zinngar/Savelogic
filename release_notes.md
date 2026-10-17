## SaveLogic 2.0.0

* **Updated to Minecraft 26.3** (Fabric Loader 0.19.5+, Java 25).
* **Cloud saves actually upload now.** Fixed a crash when uploading (OkHttp's Kotlin dependency was missing from the jar) and tokens that were never decrypted after loading the config.
* **Big worlds:** each world is stored as its own GitHub release, named after the world, and split into 1 GB parts instead of committed to the repo, which was capped at 100 MB and grew forever.
* **Downloads over 1 MB work**, and they unzip into their own world folder instead of spilling into `saves/`.
* **Upload and download results are shown in-game** instead of only in the log.
* **Test Connection** now checks what you typed, and warns if the token can't write or the repository is public.
* Fixed local auto-backups zipping the wrong folder.
* Removed Google Drive support. Existing users need to paste their GitHub token into Settings again.
