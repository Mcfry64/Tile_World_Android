# Native Android Launcher with Progress and Scores

This plan outlines the implementation of a native Android UI to replace the initial retro levelset selection menu. It includes progress tracking and score display by parsing game save files directly.

## User Review Required

> [!IMPORTANT]
> The game engine's internal selection menu will be bypassed. You will now choose everything from the standard Android UI before the game starts.
>
> [!NOTE]
> Progress and Scores are read from binary `.tws` files. If you have many custom level sets, the initial scan might take a brief moment.

## Proposed Changes

### Android UI

#### [MODIFY] [activity_main.xml](file:///home/quaqua64/Desktop/tworld v5/tworld-android-mainv5/app/src/main/res/layout/activity_main.xml)
- Add two `Spinner` components for Level Set and Level selection.
- Add `TextView`s to display progress (e.g., "12/149") and total score.
- Add a "START GAME" `Button`.

#### [NEW] [SaveGameParser.kt](file:///home/quaqua64/Desktop/tworld v5/tworld-android-mainv5/app/src/main/java/dev/koolaidxk1d/tworld/SaveGameParser.kt)
- Implement logic to read binary `.tws` files from the `/save` directory.
- Extract completion flags and best times (scores) for each level.
- Parse level set files (`.ccx`, `.dat`) to get level names and counts.

#### [MODIFY] [GameActivity.kt](file:///home/quaqua64/Desktop/tworld v5/tworld-android-mainv5/app/src/main/java/dev/koolaidxk1d/tworld/GameActivity.kt)
- Initialize the UI components and populate the Level Set spinner from the `sets/` directory.
- Implement dynamic updates for the Level selection spinner when a set is chosen.
- Pass the selected set and level number to the game engine when "START" is clicked.

---

### JNI & Game Engine (C++)

#### [MODIFY] [GameEngine.kt](file:///home/quaqua64/Desktop/tworld v5/tworld-android-mainv5/app/src/main/java/dev/koolaidxk1d/tworld/GameEngine.kt)
- Update `nativeStart` to accept `levelSetPath` and `levelNumber` strings.

#### [MODIFY] [android_jni.c](file:///home/quaqua64/Desktop/tworld v5/tworld-android-mainv5/app/src/main/cpp/android_jni.c)
- Receive the new parameters and store them globally for the game thread.
- Pass these parameters to the `tworld()` function.

#### [MODIFY] [tworld.c](file:///home/quaqua64/Desktop/tworld v5/tworld-android-mainv5/app/src/main/cpp/tworld_src/tworld.c)
- Modify the startup logic to check for provided level set and number.
- If provided, bypass `choosegameatstartup` and load the specified level immediately.

## Verification Plan

### Automated Tests
- Build verification using `./gradlew assembleDebug`.

### Manual Verification
1.  **Launcher UI:** Verify that all level sets in the `sets/` folder appear in the dropdown.
2.  **Progress Tracking:** Solve a level, restart the app, and verify that the level is marked as solved and your score is displayed in the dropdown.
3.  **Level Selection:** Pick a specific level from the second dropdown and verify the game starts exactly on that level.
4.  **Bypass:** Verify that the "retro" level selection screen no longer appears at startup.
