<div align="center">

<img src="./.github/readme-images/app-icon.webp" alt="Yokai Komganion logo" height="200" width="200" />

# Yokai Komganion

A focused Android reader for Komga libraries and filesystem image galleries.

</div>

> [!IMPORTANT]
> Yokai Komganion is a personal, experimental fork. It is not affiliated with the Komga project, Mihon, or the original Yōkai project, and it does not host or provide content.

## What this fork is

Yokai Komganion keeps Yōkai's mature browsing and reader experience while narrowing the application around two self-hosted sources:

- **Komga** for comics, manga, and books managed by an existing Komga server.
- **Galleries** for ordinary image folders exposed by the companion [Gallery Komganion server](https://github.com/roddur99/gallery-komganion).

The main navigation is focused on **Komga**, **Recents**, and **Galleries**. Users connect directly to their servers instead of installing general-purpose source extensions.

## Changes from Yōkai

This fork currently adds or changes the following:

### Komga

- Built-in Komga connection and browsing.
- Individual Komga book covers in series chapter lists and the reader chapter sheet.
  - Covers use loading and error placeholders and remain correctly associated during scrolling and deletion.
- Reading through the existing Yōkai reader.
- Whole-book deletion through Komga.
  - Deletion requires a Komga account with administrator permission.
  - Yokai currently requires a manual refresh after deletion to clear the cached book listing.
- Slideshow playback inside a book.
- Sequential or shuffled playback.
- Selectable 2, 3, 5, 10, or 15 second playback interval.
- Per-book scores from 1–10 and free-text notes.
  - Scores and notes are stored locally in the app's SQLite database.
  - Komga book rows display the saved score and whether notes are present.
  - Annotations can be exported to and restored from a versioned JSON file.
  - Import merges by Komga book ID; newer records win without erasing unrelated local annotations.
  - Books can be sorted by score and filtered as rated, unrated, or containing notes.
  - A global Recently Rated view lists rated Komga books by their latest annotation update.

### Gallery Komganion

- Built-in authenticated connection to Gallery Komganion.
- Gallery listing, search, covers, thumbnails, and full-resolution page streaming.
- Reader metadata showing filename, page position, dimensions, file size, and modified date when available.
- Individual image deletion by moving the file into the gallery root's configured trash directory.
- Immediate page-list updates after image deletion.
- Slideshow playback with sequential and shuffle modes.
- The same persistent playback-speed options as Komga.

### Focused interface

- Main tabs reduced to Komga, Recents, and Galleries.
- Branding changed to **Yokai Komganion**.
- Extension-update badges, background extension checks, and extension actions were removed from the focused navigation.
- Dedicated adaptive launcher and themed icon.
- Permanent release application ID: `com.rodro.yokaikomganion`.

## Gallery companion server

[Gallery Komganion](https://github.com/roddur99/gallery-komganion) is the self-hosted companion service used by the Galleries tab.

It:

- Scans one or more configured filesystem roots for folders containing supported images.
- Stores indexed gallery and page metadata in SQLite.
- Exposes authenticated FastAPI endpoints for gallery browsing, covers, thumbnails, metadata, and image streaming.
- Moves deleted images to a configurable trash location instead of permanently erasing them.
- Can be reached privately over a LAN or VPN such as NordVPN Meshnet.

The server and Android client are separate repositories:

- Android client: [roddur99/yokai-komganion](https://github.com/roddur99/yokai-komganion)
- Companion server: [roddur99/gallery-komganion](https://github.com/roddur99/gallery-komganion)

Do not expose a development server directly to the public internet. Use authentication and a trusted private network.

## Planned feature timeline

The roadmap is intentionally incremental. Version assignments may change as features are tested.

| Version | Planned focus |
|---|---|
| **v0.1.2 — shipped** | Komga book covers, loading/error placeholders, and covers in the reader chapter sheet. |
| **v0.1.3 — shipped** | Score sorting, rated/unrated and has-notes filters, and a recently rated view. |
| **v0.1.4** | Local activity dashboard foundation: reading sessions, weekly totals, recent completions, source usage, monthly history, completion calendar, series averages, and an optional post-finish rating prompt. |
| **v0.1.5** | Reading queue, improved shuffle, cross-item playback, and delete-then-advance workflows. |
| **v0.2.0** | Optional multi-device synchronization through Gallery Komganion for annotations, activity, completions, bookmarks, and queue state. |
| **Later** | Page-level notes and bookmarks, richer statistics, and other features guided by real usage. |

Activity tracking will begin when the dashboard foundation ships. Time-spent and completion statistics will not claim historical accuracy for activity that occurred before tracking was introduced.

## Current status

Version **0.1.3** adds Komga score sorting, rated/unrated and has-notes filtering, and a global Recently Rated view. These annotation controls persist locally and remain hidden for non-Komga sources. Komga and Gallery browsing, reading, deletion, metadata, slideshow, shuffle, playback-speed controls, local annotations, annotation JSON recovery, cover row recycling, and annotation discovery have been tested on Android devices and emulators.

Known follow-up work includes:

- Better connection and offline error states.
- Stable Gallery page IDs before finalizing the companion API.
- Automatic Komga cache refresh after book deletion is intentionally deferred; use manual refresh.

## Development build

Requirements:

- Android SDK
- JDK 17
- A configured Komga server and/or Gallery Komganion server

On Windows PowerShell:

```powershell
$jdk17 = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat "-Dorg.gradle.java.home=$jdk17" :app:assembleDevDebug
```

The universal and ABI-specific APKs are written under:

```text
app/build/outputs/apk/dev/debug/
```

For an Android emulator, the x86_64 build is normally appropriate. A modern physical Android phone will normally use the arm64-v8a build.

## Signed release build

Android requires release APKs to be signed. The signing key establishes the app's permanent update identity, so every future release must use the same keystore.

The repository includes `keystore.properties.example`. Create the signing key locally:

```powershell
keytool -genkeypair -v \`
    -keystore "$env:USERPROFILE\.android\yokai-komganion-release.jks" \`
    -alias yokai-komganion \`
    -keyalg RSA \`
    -keysize 4096 \`
    -validity 10000
```

Copy the example configuration:

```powershell
Copy-Item .\keystore.properties.example .\keystore.properties
```

Configure it with the local keystore path and passwords:

```properties
storeFile=C:/Users/your-name/.android/yokai-komganion-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=yokai-komganion
keyPassword=YOUR_KEY_PASSWORD
```

Build the signed English release:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=$jdk17" :app:assembleDevRelease
```

Signed APKs are written under:

```text
app/build/outputs/apk/dev/release/
```

Use the `x86_64` APK for the usual Android emulator and the `arm64-v8a` APK for modern physical phones such as the Galaxy S24.

> [!CAUTION]
> Never commit `keystore.properties`, passwords, or the `.jks` file. Back up the keystore securely. Losing it prevents future APKs from updating installations signed with that key.

## Upstream projects

Yokai Komganion is derived from [Yōkai](https://github.com/null2264/yokai), which is itself based on the Tachiyomi/Mihon ecosystem.

The fork benefits from upstream work including:

- Multiple reader modes and reading directions.
- Double-page and continuous-reading support.
- Recents and reading-history interfaces.
- Themes, backups, downloads, and reader preferences.
- The broader Mihon and Yōkai Android architecture.

Please report issues caused by this fork here rather than to the upstream Yōkai, Mihon, or Komga projects.

## License

Copyright © 2015 Javier Tomás  
Copyright © 2024 null2264

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an **AS IS** BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
