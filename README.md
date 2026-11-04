# 🧟‍♂️ ZombieRool Map Launcher

Welcome to the official **ZombieRool Map Launcher**! This tool allows you to easily browse, download, and install custom maps for the ZombieRool Minecraft mod with a single click.

Current source version: **1.7.1**. This is the launcher version; **26.7.1** is a ZombieRool mod version. Source changes alone do not publish a launcher update.

## ✨ Features
* **Play:** Builds a separate Forge instance for that map, so one map's mods do not replace your usual Minecraft folder.
* **Map versions:** Play installs the ZombieRool version declared by each map. Maps without a version use the latest stable release. Maps targeting a beta use that beta; unavailable versions show an error.
* **Install:** Copies the world into the folder shown at the bottom of the launcher.
* **Official & Community Maps:** Easily switch between maps made by the dev team and the community.
* **Auto-Updater:** Checks the published launcher version and offers an update when it differs from the installed version.
* **Multilingual:** Available in English and French.

## 📥 How to Download & Use
1. Go to the [Releases page](../../releases) and download the latest `ZRLauncher-Setup.exe`.
2. Run the installer.
3. Open the Launcher and sign in with Microsoft.
4. Pick a map, then click **Play**. Use **Install** only when you want the world in your normal saves folder.

## 🗺️ How to submit your map?
Are you a map maker? We'd love to feature your map on the Community tab!
Use the [community website](https://zombierool-community.onrender.com), sign in with Discord and submit your map. In-game publication links open the website; publication and in-game reviews use different sign-in flows.

## ZombieRool 26.7.1 compatibility

- Declare `26.7.1` in the map's **ZombieRool version** field only after testing that map with the matching mod JAR. Plain versions, `v26.7.1` and full JAR names are recognized by the launcher's resolver.
- The mod release must be publicly available on GitHub with the production `zombierool-26.7.1.jar` asset before Play can download it. A local JAR is not automatically uploaded or distributed.
- Existing maps keep their declared version. The launcher does not rewrite old maps to require 26.7.1. Maps without a version use the latest published stable release.
- **Player Animator** is required on clients. **TaCZ and gun packs** remains optional unless the selected map needs them. The runtime stays on Minecraft **1.20.1**, Forge **47.1.3** and Java **17**.

## Validation and releases

Run `mvn test` or `mvn package` with Java 17. Version 1.7.1 fixes normalization of modern mod JAR names, including `zombierool-26.7.1.jar`, while preserving historical labels and exact map requirements.

The draft updater manifest is in `release/launcher_version.v1.7.1.json`; the root manifest keeps the existing published version. For a release, the existing Windows workflow can build the runtime and installer and publish tag **v1.7.1**, then update the root `launcher_version.json` after the assets exist. Trigger it with a manual version input or a release commit containing `[release v1.7.1]`. Runtime descriptors do not need a mod URL pinned to 26.7.1. See [release preparation](release/README.md).

## 💬 Community & Support
Need help or want to find players? Join the ZombieRool community!
* [Join our Discord Server](https://discord.gg/HGv2r44hXM)

---
*Developed with ❤️ by Cryo60*
