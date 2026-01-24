# Cloud Saves Fabric Mod

This is a Fabric mod for Minecraft 1.21.10 that allows you to save and load your single-player worlds to and from the cloud. It currently supports Google Drive and GitHub as cloud storage providers.

## Features

*   **Cloud Backup:** Upload your Minecraft worlds to Google Drive or a private GitHub repository.
*   **Cloud Sync:** Download your worlds from the cloud to play on different computers.
*   **Simple UI:** Buttons on the main menu allow for easy saving and loading of worlds.

## Build

1.  Ensure you have the Java 21 JDK installed and that your `JAVA_HOME` environment variable is set correctly.
2.  From the project root directory, run the following command:
    ```bash
    ./gradlew build
    ```
3.  The compiled mod `.jar` file will be located in the `build/libs/` directory.

## Configuration

Before using the mod, you need to configure it by editing the `savelogic.json` file located in your Minecraft `config` directory.

### General Configuration

*   `provider`: Set this to either `"google"` or `"github"` to choose your desired cloud storage provider.

### Google Drive Configuration

1.  **Set `provider` to `"google"`.**
2.  **Create a Google Cloud Platform Project:**
    *   Go to the [Google API Console](https://console.developers.google.com/).
    *   Create a new project.
    *   Enable the **Google Drive API** for your project.
    *   From the "Credentials" page, create an **OAuth client ID** for a **Desktop app**.
3.  **Fill in `clientId` and `clientSecret`:**
    *   Copy the "Client ID" and "Client Secret" from the Google API Console into the respective fields in the `savelogic.json` file.
4.  **First-time Login:**
    *   When you first start Minecraft with the mod configured for Google Drive, a "Login with Google Drive" button will appear on the main menu.
    *   Clicking this will open a Google authorization page in your web browser.
    *   Log in and grant the mod permission to access your Google Drive.
    *   After authorization, you will be redirected to a page confirming success. You can then close the browser window.
    *   The mod will securely store a refresh token in the config file for future use.

### GitHub Configuration

1.  **Set `provider` to `"github"`.**
2.  **Create a Fine-Grained Personal Access Token (PAT):**
    *   Go to your GitHub **Developer settings** > **Personal access tokens** > **Fine-grained tokens**.
    *   Click **Generate new token**.
    *   Give it a descriptive name (e.g., "Minecraft Savelogic Mod").
    *   Under **Repository access**, select **Only select repositories** and choose the private repository you want to use for your world saves.
    *   Under **Permissions**, find **Contents** and change its access to **Read and write**. This is the only permission the mod needs.
    *   Click **Generate token** and copy the token.
3.  **Fill in `personalAccessToken` and `repositoryUrl`:**
    *   Copy the generated PAT into the `personalAccessToken` field in the `savelogic.json` file.
    *   Set the `repositoryUrl` to the URL of the private GitHub repository where you want to store your worlds (e.g., `"https://github.com/YourUsername/MyMinecraftSaves"`).

## Support

If you find this mod useful, please consider supporting the developer:

[<img src="https://img.shields.io/badge/Donate-PayPal-blue.svg?logo=paypal" alt="Donate via PayPal">](https://www.paypal.com/donate/?business=RQFEAWX7E39CG&no_recurring=0&item_name=Support+Savelogic+Development+&currency_code=AUD)

## ⚠️ Security Warning ⚠️

The `savelogic.json` configuration file will store sensitive credentials, including your GitHub Personal Access Token and your Google Drive refresh token.

**Treat this file like a password.** Anyone with access to this file could potentially access your files on Google Drive or your repositories on GitHub.

*   **Do not share your `savelogic.json` file with anyone.**
*   **Ensure the file is stored in a secure location.**
*   **If you believe your credentials have been compromised, revoke them immediately from the respective service (Google or GitHub).**
