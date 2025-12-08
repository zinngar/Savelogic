# Cloud Saves Minecraft Mod

A Fabric mod for Minecraft that allows you to save and load your single-player worlds to and from Google Drive. This ensures your worlds are backed up and accessible from any computer.

## Features

*   **Cloud Saves:** Save your single-player worlds directly to your personal Google Drive account.
*   **Cloud Loads:** Load your worlds from Google Drive on any machine with the mod installed.
*   **Simple UI:** Adds "Save to Cloud" and "Load from Cloud" buttons to the main menu for easy access.

### Planned Features

*   Automatic background backups.
*   Support for multiple cloud save slots.
*   In-game management of cloud saves.

## Setup & Installation

### Requirements

*   Minecraft 1.21.1
*   [Fabric Loader](https://fabricmc.net/use/installer/) (at least version 0.15.11)
*   [Fabric API](https://modrinth.com/mod/fabric-api)

### Installation

1.  Download the latest release of the mod from the [releases page](https://github.com/YourName/CloudSaves/releases).
2.  Place the downloaded `.jar` file into your `mods` folder, which is located in your Minecraft directory.
3.  Run the game once to generate the necessary configuration files.

### Building from Source

If you want to build the mod yourself, follow these steps:

1.  Clone this repository: `git clone https://github.com/YourName/CloudSaves.git`
2.  Navigate to the project directory: `cd CloudSaves`
3.  Run the Gradle build command: `./gradlew build`
4.  The compiled mod `.jar` will be located in the `build/libs/` directory.

## Configuration

This mod requires you to provide your own Google Drive API credentials to function. This ensures that your world saves are only accessible to you.

### Creating `credentials.json`

1.  **Go to the Google Cloud Console:** Navigate to [https://console.cloud.google.com/](https://console.cloud.google.com/) and sign in with your Google account.
2.  **Create a New Project:** If you don't have one already, create a new project.
3.  **Enable the Google Drive API:**
    *   In the navigation menu, go to **APIs & Services > Library**.
    *   Search for "Google Drive API" and enable it for your project.
4.  **Configure the OAuth Consent Screen:**
    *   Go to **APIs & Services > OAuth consent screen**.
    *   Choose **External** and click **Create**.
    *   Fill in the required fields (App name, User support email, Developer contact information). You can leave the rest blank for now.
    *   On the "Scopes" and "Test users" pages, you can click "Save and Continue" without adding anything.
5.  **Create Credentials:**
    *   Go to **APIs & Services > Credentials**.
    *   Click **+ CREATE CREDENTIALS** and select **OAuth client ID**.
    *   For **Application type**, select **Desktop app**.
    *   Give it a name (e.g., "Minecraft Cloud Saves") and click **Create**.
6.  **Download and Place the File:**
    *   A window will pop up with your client ID and secret. Click **DOWNLOAD JSON**.
    *   Rename the downloaded file to `credentials.json`.
    *   Place this `credentials.json` file inside the `config/cloudsaves` directory in your Minecraft game folder. The mod will create this folder the first time you run it.

## License

This project is licensed under the [CC0-1.0 License](LICENSE). Feel free to learn from it and incorporate it in your own projects.
