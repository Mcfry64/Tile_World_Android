# Herbenoem `win31_` referenties naar `tw_`

Hernoem alle drawable-bestanden en bijbehorende XML/Kotlin-referenties die de prefix `win31_` dragen naar `tw_` om merkrechtelijke/copyright-referenties naar "Windows 3.1" te verwijderen.

## Proposed Changes

### Drawables

Hernoem de volgende 12 drawable-bestanden in `app/src/main/res/drawable/`:

- [DELETE] [win31_arrow_down.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_arrow_down.xml) $\rightarrow$ [NEW] [tw_arrow_down.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_arrow_down.xml)
- [DELETE] [win31_button.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_button.xml) $\rightarrow$ [NEW] [tw_button.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_button.xml)
- [DELETE] [win31_checkbox.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_checkbox.xml) $\rightarrow$ [NEW] [tw_checkbox.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_checkbox.xml)
- [DELETE] [win31_combo_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_combo_bg.xml) $\rightarrow$ [NEW] [tw_combo_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_combo_bg.xml)
- [DELETE] [win31_dpad_cross_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_dpad_cross_bg.xml) $\rightarrow$ [NEW] [tw_dpad_cross_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_dpad_cross_bg.xml)
- [DELETE] [win31_field.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_field.xml) $\rightarrow$ [NEW] [tw_field.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_field.xml)
- [DELETE] [win31_popup.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_popup.xml) $\rightarrow$ [NEW] [tw_popup.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_popup.xml)
- [DELETE] [win31_radio.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_radio.xml) $\rightarrow$ [NEW] [tw_radio.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_radio.xml)
- [DELETE] [win31_seekbar_thumb.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_seekbar_thumb.xml) $\rightarrow$ [NEW] [tw_seekbar_thumb.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_seekbar_thumb.xml)
- [DELETE] [win31_seekbar_track.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_seekbar_track.xml) $\rightarrow$ [NEW] [tw_seekbar_track.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_seekbar_track.xml)
- [DELETE] [win31_title_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_title_bg.xml) $\rightarrow$ [NEW] [tw_title_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_title_bg.xml)
- [DELETE] [win31_window.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/win31_window.xml) $\rightarrow$ [NEW] [tw_window.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_window.xml)

---

### Layout & Kotlin Files

#### [MODIFY] [activity_main.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/layout/activity_main.xml)
- Vervang alle `@drawable/win31_...` referenties door `@drawable/tw_...`.

#### [MODIFY] [dpad_overlay.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/layout/dpad_overlay.xml)
- Vervang alle `@drawable/win31_...` referenties door `@drawable/tw_...`.

#### [MODIFY] [tw_combo_bg.xml](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/res/drawable/tw_combo_bg.xml)
- Vervang `@drawable/win31_arrow_down` door `@drawable/tw_arrow_down`.

#### [MODIFY] [GameActivity.kt](file:///home/quaqua64/Desktop/TWORLD2000/tworld-android-main/app/src/main/java/dev/mcfry64/tworld/GameActivity.kt)
- Vervang alle `R.drawable.win31_...` referenties door `R.drawable.tw_...`.

---

## Verification Plan

### Automated Tests & Build
- Voer `:app:assembleDebug` uit om te controleren of alle resource-referenties goed compileren.
- Voer een `grep` uit over de broncode om te verifiëren dat er geen `win31_` referenties achtergebleven zijn.
