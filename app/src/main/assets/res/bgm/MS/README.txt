===================================================================
                  TILE WORLD - MS MUSIC GUIDE
===================================================================

To enable the classic Microsoft (MS) background music theme,
place the following 3 .ogg files in this directory:

1. C1.ogg  -> Corresponds to: CHIP1.MID  or chips01.mp3
2. C2.ogg  -> Corresponds to: CHIP2.MID  or chips02.mp3
3. CA.ogg  -> Corresponds to: CANYON.MID or canyon.mp3

-------------------------------------------------------------------
AUDIO FORMAT CONVERSION REQUIREMENT:
-------------------------------------------------------------------
* All audio files MUST be in OGG Vorbis (.ogg) format.
* If your original files are in MIDI (.mid) or MP3 (.mp3) format,
  you MUST convert them to .ogg first (using free tools such as
  Audacity, VLC, or an online audio converter) before renaming
  and placing them in this folder.

-------------------------------------------------------------------
LEVEL MUSIC ROTATION:
-------------------------------------------------------------------
Music tracks rotate automatically per level based on the formula (Level % 3):

* Level 1, 4, 7, 10, 13 ... (Level % 3 = 1)  -> plays C2.ogg
* Level 2, 5, 8, 11, 14 ... (Level % 3 = 2)  -> plays CA.ogg
* Level 3, 6, 9, 12, 15 ... (Level % 3 = 0)  -> plays C1.ogg

-------------------------------------------------------------------
NOTE:
-------------------------------------------------------------------
The "MS" option will ONLY appear in the BGM selection dropdown
when all 3 required files (C1.ogg, C2.ogg, CA.ogg) are present
in this folder.
===================================================================
