# TileWorld for Android

An Android port of [Tile World](https://github.com/BR903/TileWorld), the open-source engine for *Chip's Challenge* — a classic puzzle game originally released in 1989. Maintained and enhanced by **Mcfry64**.

The game engine runs natively via JNI with no SDL dependency. Audio uses AAudio, graphics are rendered to a `SurfaceView` via Two-Pass Sharp Bilinear Sub-pixel scaling, and input comes from Android key events, physical keyboards, gamepads, and touchscreen controls.

---

## Features

- **Full MS & Lynx Ruleset Simulation**: 100% faithful logic for both Microsoft and Lynx rulesets.
- **Open-Source Level Sets**: Bundled with 100% free community level packs (CCLP1, CCLP2, CCLP3, CCLP4, CCLP5, and Intro). Automatic detection of user-provided level files on storage.
- **Pixel-Perfect Tile Rendering**: Two-Pass Sharp Bilinear Sub-pixel scaling for zero pixel shimmering and razor-sharp 2D tile graphics.
- **Custom Content & AUTO Linkage**:
  - **Custom Tilesets**: Dynamic scanning of `.bmp` tile sheets in `res/mstiles/` and `res/lynxtiles/`.
  - **SFX Themes**: Dynamic scanning of `res/sfx/` with dedicated `PickupKeySound` and optional `StopBgmOnLevelComplete`.
  - **BGM Themes**: Dynamic scanning of `res/bgm/` supporting classic level rotation (CHIPS01/CHIPS02/CANYON) and random `.ogg` track selection.
  - **AUTO Audio Mode**: Automatically matches BGM and SFX themes to the active Tileset name (with automatic fallback to `aki` BGM and `Tile World` SFX).
- **Side-by-Side Control Dropdowns**:
  - **Touchscreen Controls**: `Touch Nav`, `Swipe Classic`, `Swipe Fluid`, `Swipe Precise`, `None`
  - **Onscreen Controls**: `None`, `Arrow Keys`, `D-Pad`
  - Universal tap anywhere on screen or controls after level completion/death to proceed or restart.
- **Keyboard & Gamepad Support**:
  - Full USB & Bluetooth keyboard support (WASD, Arrow keys, Numpad) with state-based continuous movement and OS auto-repeat filtering.
  - Full USB & Bluetooth gamepad/joystick support (analog sticks, D-pad hat, action buttons).
- **Game Speed Controller**: 25% Crawl up to 300% Ludicrous speed with secret 10-second hold unlock.
- **Level Password Unlock System**: Password parser and level unlock notifications.
- **Dynamic Level Set Scanner**: Automatically scans user-added `.dac` files in `sets/` and counts levels dynamically.

---

## Custom Content Guide (Tilesets, BGM, & SFX)

On first launch the app extracts its bundled assets to the device storage:

```
/sdcard/Android/data/dev.mcfry64.tworld/files/
    data/          ← level set .dat / .ccx level files
    sets/          ← .dac descriptor files
    res/           ← default tile sheets, sound effects (.wav), and BGM (.ogg)
        mstiles/   ← custom MS tilesets (.bmp)
        lynxtiles/ ← custom Lynx tilesets (.bmp)
        sfx/       ← custom SFX theme folders
        bgm/       ← custom BGM theme folders
    save/          ← level progress and high scores (.tws / .sav)
```

---

### 🎨 Custom Tilesets (`mstiles` & `lynxtiles`)
Place custom `.bmp` tile sheets in `res/mstiles/` or `res/lynxtiles/`:
- **MS Tilesets**: `/sdcard/Android/data/dev.mcfry64.tworld/files/res/mstiles/MySet.bmp`
- **Lynx Tilesets**: `/sdcard/Android/data/dev.mcfry64.tworld/files/res/lynxtiles/MySet.bmp`

The app automatically scans these directories at startup and populates the Tileset dropdowns in the launcher!

---

### 🔊 Custom SFX Themes (`res/sfx/`)
Create a custom folder under `/sdcard/Android/data/dev.mcfry64.tworld/files/res/sfx/<ThemeName>/` and place an `rc` configuration file inside it:

```ini
# Example res/sfx/MyTheme/rc
ChipDeathSound = death.wav
LevelCompleteSound = win.wav
PickupChipsSound = chip.wav
PickupToolSound = tool.wav
PickupKeySound = key.wav
StopBgmOnLevelComplete = 1
```

- **`PickupKeySound`**: Dedicated sound effect when picking up keys (red, blue, yellow, green). Falls back to `PickupToolSound` if omitted.
- **`StopBgmOnLevelComplete = 1`**: Immediately stops background music when completing a level so `LevelCompleteSound` plays cleanly.
- **WAV Fallback**: If any `.wav` file is omitted, the engine automatically falls back to the default WAV file in `res/`.

---

### 🎵 Custom BGM Themes (`res/bgm/`)
Place `.ogg` music files in a custom theme folder under `/sdcard/Android/data/dev.mcfry64.tworld/files/res/bgm/<ThemeName>/` (for example, `res/bgm/MS/` for Microsoft music).

1. **Chip's Challenge Classic Level Rotation (`C1.ogg`, `C2.ogg`, `CA.ogg`)**:
   - Replicates the original 1992 *Chip's Challenge* 3-track level rotation. If you own the original game, convert `CHIPS01.MID`, `CHIPS02.MID`, and `CANYON.MID` to `.ogg` and place them as `C1.ogg`, `C2.ogg`, and `CA.ogg` inside `res/bgm/MS/` (or any custom theme folder):
     - **Level 1**: `C2.ogg` *(Originally CHIPS02.MID)*
     - **Level 2**: `CA.ogg` *(Originally CANYON.MID)*
     - **Level 3**: `C1.ogg` *(Originally CHIPS01.MID)*
     - **Level 4**: `C2.ogg` *(Rotates continuously per level)*
2. **Random Track Engine**:
   - If a folder contains other `.ogg` files (e.g. `track1.ogg`, `track2.ogg`), the game selects a random track from that folder for each level.

---

### 🔗 AUTO Audio Theme Linkage
When BGM or SFX Theme is set to **`AUTO`** in the launcher:
- The app automatically looks for a BGM or SFX folder matching the name of the currently selected Tileset (e.g. Tileset `MySet` $\rightarrow$ checks `res/bgm/MySet/` & `res/sfx/MySet/`).
- Switching Tilesets in the launcher automatically updates BGM and SFX themes to match!
- **Fallbacks**: If a Tileset has no matching folder, BGM falls back to `aki` and SFX falls back to `Tile World`.

---

## Changelog

### v3.4.1 (Current)
- Maintained by **Mcfry64** under package `dev.mcfry64.tworld`
- Added AUTO Audio Theme selection for BGM and SFX themes based on active Tileset name.
- Added custom `PickupKeySound` and `StopBgmOnLevelComplete` support in `rc` files.
- Added recessed black LED display box with live scrolling green matrix ticker.
- Reorganized Controls section into side-by-side Touchscreen Controls and Onscreen Controls dropdowns.
- Added state-based WASD / Arrow key movement filtering for smooth continuous keyboard movement.
- Added universal screen/controls tap-to-continue or restart on level completion/death.
- Added dynamic user `.dac` level set file scanner in `sets/`.
- Automated build APK naming to `TileWorld-v3.4.1-debug.apk`.

---

## Requirements

### Build machine
| Tool           | Minimum version                                                |
|----------------|----------------------------------------------------------------|
| Android Studio | Hedgehog (2023.1) or newer — **or** Android command-line tools |
| Android SDK    | API 26 (Android 8.0) or higher                                 |
| Android NDK    | r25 or newer (tested with r28)                                 |
| CMake          | 3.22.1 (installed via SDK Manager)                             |
| JDK            | 11 or newer                                                    |
| Gradle         | 8.x (provided via the wrapper)                                 |

### Target device
- Android **8.0 (API 26)** or higher
- Physical gamepad, USB/Bluetooth keyboard, or touchscreen

---

## Building

### 1. Clone the repository

```bash
git clone https://github.com/Mcfry64/tileworld-android.git
cd tileworld-android
```

### 2. Install SDK components

Open **Android Studio → SDK Manager** (or use `sdkmanager` on the command line) and install:

- **SDK Platform** for API 36 (or the `compileSdk` version in `app/build.gradle.kts`)
- **NDK (Side by side)** — any r25 or newer
- **CMake 3.22.1**

### 3. Build

```bash
./gradlew assembleDebug
```

The APK will be located at:
```
app/build/outputs/apk/debug/TileWorld-v3.4.1-debug.apk
```

---

## Replacing level sets and resources via ADB

You can push custom files directly over ADB to replace or add resources:

```bash
# Push custom level set descriptors and level data files
adb push MyLevels.dac /sdcard/Android/data/dev.mcfry64.tworld/files/sets/
adb push MyLevels.dat /sdcard/Android/data/dev.mcfry64.tworld/files/data/

# Push custom MS or Lynx tilesets (.bmp graphics)
adb push MyTiles.bmp /sdcard/Android/data/dev.mcfry64.tworld/files/res/mstiles/
adb push MyTiles.bmp /sdcard/Android/data/dev.mcfry64.tworld/files/res/lynxtiles/

# Push custom BGM theme folder (.ogg music)
adb push MyBgmFolder/ /sdcard/Android/data/dev.mcfry64.tworld/files/res/bgm/

# Push custom SFX theme folder (.wav sounds & rc file)
adb push MySfxFolder/ /sdcard/Android/data/dev.mcfry64.tworld/files/res/sfx/

# Backup or restore save game progress (.tws / .sav)
adb pull /sdcard/Android/data/dev.mcfry64.tworld/files/save/ ./my_saves/
adb push MyProgress.tws /sdcard/Android/data/dev.mcfry64.tworld/files/save/
```

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/                  ← bundled level sets and resources
│   ├── data/                ← .dat / .ccx level files
│   ├── res/                 ← tile graphics, music, and sound effects
│   └── sets/                ← .dac descriptor files
├── cpp/
│   ├── CMakeLists.txt       ← native build definition
│   ├── android_jni.c        ← JNI bridge (Kotlin ↔ C)
│   └── tworld_src/          ← Tile World engine (C)
└── java/dev/mcfry64/tworld/
    ├── GameActivity.kt      ← entry point, launcher, input dispatch
    ├── GameEngine.kt        ← JNI declarations, asset extraction
    ├── GameSurfaceView.kt   ← render thread, sharp bilinear scaling
    ├── MusicManager.kt      ← background music manager
    └── SaveGameParser.kt    ← progress and password parser
```

---

## License & Copyrights

- **Tile World Engine**: Licensed under the **GNU General Public License v2**. See [`app/src/main/cpp/tworld_src/COPYING`](app/src/main/cpp/tworld_src/COPYING) for the full text.
- **Android Port Code**: All Android-specific source files (`oshw-android/`, `android_jni.c`, and Kotlin sources) are released under the **GNU General Public License v2**.
- **Bundled Level Sets**: CCLP1–CCLP5, CCLXP2, and Intro were created by the *Chip's Challenge* community and are freely redistributable. See individual `.ccx` files for per-level authorship.
- **Sound Effects**: Created by Brian Raiter and placed in the **Public Domain**.

---

## Acknowledgements

- **Mcfry64** — Current maintainer, HD rendering, touch controls, audio themes, & Android v3.4.1 enhancements
- **koolaidxk1d** — Original Android port & JNI bridge
- **Brian Raiter, Madhav Shanbhag, Eric Schmidt** — Authors of the Tile World engine
- **Chuck Sommerville** — Designer of the original *Chip's Challenge*
- The Chip's Challenge community for the free level sets
