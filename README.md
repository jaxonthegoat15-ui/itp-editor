# Quest File Manager 0.1.0

A standalone Android file manager and APK/archive browser with a minimal charcoal UI. Designed for Meta Quest Android devices, but it can also run on compatible Android phones/tablets.

## Included in this source build

- SAF folder access with persistent folder permission where the Android picker supports it.
- Browse folders and files in the selected tree.
- Create folders, rename, delete, copy and move files/folders.
- Text editor for common text/config/code formats (up to 1 MB loaded into the editor).
- Hex preview of the first 256 bytes of a file.
- Open APK, ZIP, JAR and AAR archives and browse their entries.
- Export individual files from an archive, including Unity-related files such as `lib/*.so`, `assets/*`, `*.dll`, `globalgamemanagers` and `resources.assets` when those entries exist in the chosen package.
- Add or replace one file inside an APK at a chosen archive path, then re-sign and export a modified APK.
- No root, Shizuku, wireless debugging or API key required.
- GitHub Actions workflow builds a debug APK automatically.

## Important Android / Quest storage limitation

This app uses Android's Storage Access Framework (SAF). The user selects a folder through the system picker. The app can operate only on folders and files that the operating system exposes through that picker. Newer Android/Quest firmware may block `Android/data`, `Android/obb`, or individual app-private directories. The app does not bypass those protections and cannot promise direct access to every installed game's private Unity data. It can inspect APK/ZIP files that the user can select and read.

APK patching rebuilds the ZIP contents and signs the result with a local key stored in the app's private data. The modified APK will have a different signing identity from the original. Split APKs and APKs with special vendor protections may not work.

## Build the APK on GitHub

1. Create a new GitHub repository.
2. Extract this ZIP and upload **the contents** of the `QuestFileManager` folder to the repository root (so `settings.gradle.kts`, `build.gradle.kts`, `app/`, and `.github/` are at the root).
3. Push to the `main` branch, or open **Actions → Build Quest File Manager APK → Run workflow**.
4. Wait for the workflow to finish successfully.
5. Open the completed workflow run and download the `QuestFileManager-debug-apk` artifact.
6. Extract the downloaded artifact ZIP; it contains `app-debug.apk`.

You can also open the folder in Android Studio (JDK 17, Gradle 8.9, Android SDK Platform 35) and run the `assembleDebug` Gradle task.

## Current limitations / roadmap

This is the first functional source release, not a full clone of MT Manager. It currently provides SAF file operations, a text editor, hex preview and archive/APK browsing with entry export. APK rebuild/signing, split APK set support, resource-table editing, DEX editing/decompilation, batch archive creation, and mod-loader-specific installation workflows are not included yet. The APK explorer is read/export only in this version.

Only modify software and game files you own or are authorised to modify. Always keep backups before changing game packages.
