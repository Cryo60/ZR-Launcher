# Launcher 1.7.1 release

`launcher_version.v1.7.1.json` is a draft, not the live updater manifest. The root `launcher_version.json` keeps its existing value until release assets are published.

The existing GitHub Actions workflow builds the executable, creates the Windows runtime with jlink and builds the installer with Inno Setup. A manual dispatch with version **v1.7.1**, or a release commit containing **[release v1.7.1]**, publishes both executables and only then updates the root updater manifest.

The local Maven build validates 10 resolver tests and produces `target/ZRLauncher.exe` and the launcher JARs. Inno Setup is not available in the current local environment; the installer is to be built by the Windows workflow or a machine with Inno Setup before publication.

The release workflow publishes version v1.7.1 and updates the live manifest after its downloads exist.
