# Play Store listing (draft)

## App name (max 30)

Tahsin — Quran Recitation

## Short description (max 80)

Recite the Quran and get word-by-word feedback. Works fully offline.

## Full description (max 4000)

Plain text, pasted as is: Play shows no Markdown, and every line break is kept,
so each paragraph and bullet is a single line.

```text
Tahsin listens as you recite the Quran and follows along word by word, showing which words you read correctly, which you missed, and where you said something different. Everything runs on your phone: no account, no internet connection, and your voice never leaves your device.

RECITE WITH FEEDBACK
• The mushaf highlights each word as you say it, across ayahs and whole surahs
• Mistakes are flagged as you go: wrong words, skipped words and extra words
• Repeating a phrase or pausing to think is fine; you won't be marked down for it
• Checks the imala in Surah Hud 41 (مَجْر۪ىٰهَا) by listening to how you voice it

MEMORISE
• Memorize mode hides the words until you recite them
• Ask for a hint when you're stuck, or skip ahead
• Each ayah gets a memorisation strength, and a "Needs review" list shows the ayahs to revisit

LEARN FROM YOUR SESSIONS
• Optionally record each session, play it back and jump straight to any mistake
• History, accuracy, your most-missed words and weakest ayahs
• Daily goal and streaks

READ THE MUSHAF
• All 114 surahs in Uthmani script, with juz and Madani page numbers
• Tajweed colouring: ghunnah, ikhfa, idgham, iqlab, qalqalah, madd and more
• Search that ignores diacritics, bookmarks, light and dark themes, adjustable Arabic text size

PRIVATE BY DESIGN
Recitation checking runs entirely on your phone with a speech model trained on Quran recitation. Nothing is uploaded, and there are no ads or tracking.

Quran text: Tanzil Project (tanzil.net). Speech model: whisper-base-ar-quran by Tarteel (Apache 2.0), running on whisper.cpp.
```

## Category

Education. (Books & Reference also fits.)

## Tags / keywords to consider

Quran, recitation, tajweed, hifz, memorization, tahsin, murajaah

## What's new (1.0.0)

First release.

## Graphics (in `docs/store/`)

| Play Console field | File |
| --- | --- |
| App icon (512 × 512) | `icon-512.png` |
| Feature graphic (1024 × 500) | `feature-graphic-1024x500.png` |
| Phone screenshots, in this order | `screenshots/1-recite-fatihah.png` … `5-insights.png` |

The icon and feature graphic come from `scripts/make_store_graphics.py`. The
screenshots are 1080 × 2160 phone captures with the status and navigation bars
trimmed, because Play rejects images more than twice as tall as they are wide.
