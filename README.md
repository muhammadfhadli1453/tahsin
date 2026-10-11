# Tahsin

Live Quran recitation checking for Android, **fully on-device**. Pick a passage,
recite into the microphone, and get word-level feedback as you read — the same
kind of feedback [Tarteel](https://tarteel.ai/) gives, but the speech model runs
locally via [whisper.cpp](https://github.com/ggml-org/whisper.cpp), so nothing
you recite leaves the phone and no account or subscription is involved.

## What it does

**Live recitation checking**
- Word-by-word highlighting as you recite: correct, wrong, missed
- Continuous sessions across many ayahs, or a whole surah, with the view
  following you across ayah boundaries
- Tolerant of repetition and hesitation — repeating a phrase does not re-flag
  words you already passed
- Adjustable sensitivity (Lenient / Balanced / Strict) and a selectable speed
  profile (Responsive / Balanced / Accurate beam search)
- Optional live transcript view showing what the model actually heard and how
  confident it was
- **Gharib check by ear**: in 11:41, مَجْر۪ىٰهَا must be read with imala
  (majrēhā). The speech model writes the word the same however it is voiced, so
  the app measures the vowel itself when you reach that ayah and flags "majrāhā"
  or "majrohā" as a pronunciation mistake. See "Gharib words" below

**Three session modes**
- **Recite** — mushaf visible, read along
- **Memorize** — upcoming words are masked; they reveal as you recite them, with
  a *Peek* hint when you stall and *Skip* to move on
- **Qiyam** — long continuous sessions

**Review and progress**
- Every session is recorded (optional) as WAV; play it back and tap any mistake
  to jump to that moment in the audio
- Session history with accuracy, mistake counts and duration
- Insights: most-missed words, weakest ayahs, minutes per day for the last two
  weeks
- Daily goal, current and longest streak
- Per-ayah memorisation strength, updated after each review, surfaced as a
  "Needs review" list

**Tajweed colouring**
- Ghunnah, ikhfa, idgham (with and without ghunnah), iqlab, ikhfa/idgham
  shafawi, qalqalah, the three madd lengths, and silent letters — coloured the
  way a printed mushaf colours them
- Derived from the text, so it shows *how the passage should be recited*. It does
  not listen to your pronunciation; see "Known limitations"
- Toggleable, with a colour legend in Settings

**Getting around**
- All 114 surahs, ayah-range picker, juz and Madani mushaf page numbers,
  bookmarks, resume-where-you-left-off
- Offline Quran search that ignores diacritics (`الرحمن` matches `ٱلرَّحْمَٰن`)
- Light/dark/system theme and an Arabic text size control

**Offline model management**
- Import any ggml Whisper `.bin` from device storage — the app never needs the
  network
- Or download a model once from the in-app catalogue (tiny/base/small, quantised
  or full precision)
- Or ship a model inside the APK by dropping it in `app/src/main/assets/models/`
- Multiple models can be installed and switched between

## Requirements

- Android 8.0+ (API 26) on **arm64-v8a**, which covers practically every phone
  in use. `x86_64` builds too, for emulators (`-PtahsinAbis=arm64-v8a,x86_64`);
  32-bit phones are not supported
- ~60–200 MB of storage for the speech model, plus space for recordings
- 3 GB+ RAM for the base model; use the tiny model on smaller devices
- Microphone permission

## Build

### Prerequisites

- **JDK 17 or newer** (the build is verified on JDK 21)
- Android SDK 34 and NDK **26.1.10909125**
- Gradle 8.11.1 / AGP 8.10.1 / Kotlin 1.9.24, compile and target SDK 36 (all
  pinned in the wrapper and build files — nothing to install manually)

```bash
export ANDROID_HOME=~/Android/Sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;26.1.10909125" "platforms;android-36"
```

### Clone and build

```bash
git clone --recurse-submodules <repo-url> tahsin
cd tahsin
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Each ABI is a full whisper.cpp + ggml native build, so only `arm64-v8a` is built
by default. Add others explicitly:

```bash
./gradlew :app:assembleDebug -PtahsinAbis=arm64-v8a,x86_64
```

If you cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

## Getting a speech model

**Release builds** (the Play Store app) bundle the Quran fine-tuned model, so
they work offline from the first launch; see [docs/RELEASING.md](docs/RELEASING.md).
**Debug builds** ship without one, to keep installs fast. Any of these works:

1. **In-app download** (Settings → Available to download). "Base (quantised)" is
   the recommended default.
2. **Import from storage** (Settings → Import a `.bin` model from storage) — for
   devices that stay offline, or for a model you converted yourself.
3. **Bundle it in the APK**: put the `.bin` in `app/src/main/assets/models/` and
   it is installed on first launch.

### Quran fine-tuned model

A general Whisper model understands Arabic; a Quran fine-tune is distinctly
better at recitation, and it transcribes *vocalised* text (with tashkeel), which
the normaliser handles the same way. Those models are published in Transformers
format, so convert them locally:

```bash
pip install torch transformers numpy huggingface_hub
./scripts/convert_whisper_model.sh tarteel-ai/whisper-base-ar-quran
# -> build/models/ggml-tarteel-ai-whisper-base-ar-quran-q5_1.bin  (57 MB)
adb push build/models/ggml-*-q5_1.bin /sdcard/Download/
```

Then import it from Settings.

The script handles three things that otherwise make this fail:

- It fetches the repository files as published, because whisper.cpp's converter
  needs the original `vocab.json` / `merges.txt`, and recent `transformers`
  versions save a fast tokenizer (`tokenizer.json`) instead.
- It downloads the mel filter bank from OpenAI's whisper repo, which whisper.cpp
  does not vendor.
- It pins `max_length` to `max_target_positions` in the checkpoint config.
  Fine-tunes often carry a *generation* limit in `max_length` — this one ships
  1024 against a 448-row positional embedding — and the converter would
  otherwise write a header whisper.cpp rejects with
  `decoder.positional_embedding has wrong size`.

## Regenerating the Quran database

`app/src/main/assets/databases/quran.db` is prebuilt and tracked, so a normal
build needs nothing. To rebuild it (for example after changing the entities in
`com.tahsin.app.data.quran`):

```bash
./gradlew :app:kspDebugKotlin          # exports Room's schema JSON
python3 scripts/import_quran.py
```

Juz and Madani page boundaries both come from Tanzil's `quran-data.xml`
(`scripts/data/`), so neither is hand-maintained.

The script reads Room's **exported schema** and writes the matching DDL,
`PRAGMA user_version` and `room_master_table` identity hash. Room refuses to open
a prepackaged database that disagrees with the generated code on any of those, so
the schema JSON is the contract rather than DDL duplicated in the script.
`QuranDatabaseAssetTest` fails if the committed `.db` and the entities drift
apart.

## Architecture

```
app/
  asr/             Whisper model catalogue, download/import/selection
  audio/           Continuous mic capture, PCM ring buffer, WAV write/store
  data/quran/      Read-only prepackaged Quran text (Room)
  data/user/       Sessions, mistakes, streaks, bookmarks, memorisation (Room)
  domain/text/     Arabic normalisation and weighted word similarity
  domain/recitation/  Streaming alignment tracker and the live session engine
  domain/gharib/   Acoustic checks for gharib words (formant tracking, imala)
  settings/        DataStore-backed preferences
  ui/              Compose screens (home, picker, session, summary, history,
                   search, settings)
whisper/           whisper.cpp JNI wrapper (confidence + timestamps exposed)
scripts/           Quran DB generator, model converter
```

### How the recitation pipeline works

```
mic ──► recorder thread ──► PCM ring buffer ──► rolling window ──► whisper.cpp
                                                                       │
                                        RecitationTracker ◄────────────┘
                                                 │
                                        word statuses + mistakes ──► UI
```

Three decisions do most of the work here:

**The recorder never waits on inference.** A dedicated thread appends audio to a
ring buffer; the inference coroutine takes snapshots whenever it is free. A
model slower than real time therefore produces staler feedback rather than
silently dropping words.

**Windows are anchored at the last recognised word.** Each pass (every ~1.2 s)
reaches back to where the last word was recognised, but no further than the
awaited word could plausibly last — longer for a maddah or an ayah ending, which
can be held for several seconds. So a long word is always heard from its first
letter, and a held ending is not dragged into the next word's window, where it
makes whisper garble what follows. `audio_ctx` is shrunk to the window, which is
what makes a pass take ~0.8 s on a phone instead of a full 30 s encode. When the
cursor jumps over a word, that stretch gets one longer "second listen" before
anything is judged.

**Whisper is never primed with the expected text.** Passing the ayah as
`initial_prompt` makes the decoder reproduce it almost regardless of what was
recited, which quietly destroys mistake detection. The decoder is given the
language and nothing else.

**Verdicts are deferred.** Windows overlap, so each expected word keeps the best
similarity ever seen for it, the cursor only moves forward, and a word is not
judged until the cursor is a few words past it. That is what stops a clipped
window edge from being reported as a mistake, and what makes repetition safe.

**Native code is always optimised.** ggml compiled at `-O0` (the default for a
debug build) is so slow that no pass finishes in time and the cursor never
moves; `whisper/src/main/jni/whisper/CMakeLists.txt` forces `-O3` for every
build type.

### Gharib words

A handful of words are read against their letters in Hafs, and the mushaf marks
them: imala in 11:41 مَجْر۪ىٰهَا (U+06EA), ishmam in 12:11, tas-hil in 41:44,
and a few more. A speech model writes the word, not how it was voiced, so
"majrēhā" and "majrāhā" come out as the same text and no transcript can tell
them apart.

Imala is checked by measuring the vowel instead. Fronting ā to ē raises its
second formant (F2), so the app compares the F2 of the word's two long vowels —
the one after ra against the plain ā of the هَا that ends the word:

| Recording | ΔF2 (rē − hā) |
| --- | --- |
| 11:41 مَجْر۪ىٰهَا — Husary, Minshawy, Abdul Basit | +260 to +640 Hz (majority of readings) |
| 11:41 مَجْر۪ىٰهَا — Alafasy | +5 / −160 / −185 → not judged |
| 79:43 ذِكْرَاهَا, same ending without imala — all four | −260 to −430 Hz (majority of readings) |

A mistake is reported only when the measurement is clearly negative under all
three steadiness tolerances it is taken at; anything in between is left
unjudged, because a false accusation costs more than a missed one.

The check needs to know exactly where the word is in the audio, which takes
per-token timings (DTW over whisper's cross-attention). Those require flash
attention off, which would slow every live pass, so the live model never has
them. Instead a second copy of the model with timings is opened in the
background **when the cursor enters a gharib ayah**, used once the reciter is
past the word, and closed again — a one-off pause of about 2 s on a mid-range
phone, after which the cursor catches up. Only imala is checked so far; the
other gharib marks are recognised but not yet measured.

### Tajweed colouring

The Tanzil Uthmani text makes rule derivation tractable, because it already
encodes the hard parts: iqlab carries a small meem (above the letter after a
fatha or damma tanween, below it after a kasratan), madd carries a maddah, silent
letters carry a small high zero, and — crucially — an assimilated or hidden noon
is written *bare*, with no sukun. So "noon with no vowel" identifies noon
sakinah, and the rest follows from the letter that comes next.

Two corpus-wide invariants are asserted in the test suite: the number of iqlab
spans equals the number of iqlab marks in all 6236 ayahs (609), and likewise for
silent letters. Those catch both over- and under-firing, which is how the
alef-wasla and shadda-plus-tanween edge cases were found.

Word matching uses a weighted edit distance that charges a reduced cost for the
Arabic letters an ASR model routinely confuses (ه/ح, ق/ك, س/ص/ث …), and each
expected word carries every legitimate reading of itself — notably both readings
of the ambiguous dagger alef, where `ٱلْعَـٰلَمِينَ` is spelled `العالمين` in modern
Arabic but `ٱلرَّحْمَـٰنِ` is spelled `الرحمن`.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

119 unit tests cover Arabic normalisation, word similarity, Tajweed rule
derivation, the streaming tracker's behaviour (resync, repetition and whisper
repetition loops, deferred verdicts, second listens, extra words), the engine's
window sizing and lifecycle, the imala check on synthetic vowels with known
formants, streak arithmetic, ring-buffer wrap-around, the right-to-left mushaf
layout, and — via Robolectric — that the committed `quran.db` really opens
through Room and that Tajweed analysis holds over the whole corpus.

The imala thresholds were chosen on master recitations that are not in the
repo. With those word clips in a folder (plus an `expected.txt` naming them),
`GHARIB_CLIPS=/path/to/clips ./gradlew :app:testDebugUnitTest` also checks that
no master's imala is accused and every plain "-rāhā" is caught.

Instrumented tests run the real model on the device — the JNI bridge, thread
benchmarks, and end-to-end replays of real recitation through the live engine
(`RecitationFollowingTest`, including the gharib check on 11:41). Install both
APKs and run them with `am instrument`:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.tahsin.app.RecitationFollowingTest \
    com.tahsin.app.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s TahsinFollow TahsinEngine
```

Avoid `connectedDebugAndroidTest`: it uninstalls the app afterwards, which
deletes the imported model and every fixture. The replays read the model from
the app's `files/models/` and WAV fixtures (16 kHz mono) from
`files/test-fixtures/` — copy them in with `adb shell run-as com.tahsin.app` —
and skip when either is missing.

Manual QA worth doing on a device:

- Surah 112 (Al-Ikhlas), ayahs 1–4 — short, quick to iterate on
- Surah 1 (Al-Fatihah), whole surah — exercises ayah auto-advance
- Deliberately misread one word, skip one word, and repeat a phrase — the first
  two should be flagged, the third should not
- Surah 9, ayah 1 or 66 — rule-dense passages, good for checking Tajweed colours
- Memorize mode: stall deliberately to trigger the Peek prompt

## Known limitations

- **Tajweed is shown, not checked.** The colouring tells you a ghunnah is due;
  it cannot tell you whether you held it for two counts. Verifying that needs
  phoneme-level acoustic modelling, which a word-level ASR pipeline does not do.
  Mistake detection is word-level, with one exception: the imala in 11:41 (see
  "Gharib words"). The other gharib readings are not checked yet.
- **The imala check is calibrated on four master reciters** and synthetic
  vowels. Voices whose formants track poorly (Alafasy's is one) land in the
  "not judged" band, so a wrong reading by such a voice can go unflagged.
- **Latency.** Feedback lags live speech by roughly a window step plus inference
  time — about 1–2 s on a mid-range phone with the quantised Quran model.
- **Model quality dominates accuracy.** A general Whisper model produces more
  false positives on recitation than a Quran fine-tune does; sensitivity is
  adjustable partly to compensate.
- **No translations or tafsir**, and no reciter audio to follow along with.
- The memorisation strength score approximates ayah length rather than using the
  real word count, so it is meaningful as a trend rather than as an exact figure.

## Releasing

[docs/RELEASING.md](docs/RELEASING.md) covers signing, the release build and
Play Console setup. A draft store listing is in
[docs/play-store-listing.md](docs/play-store-listing.md), and the privacy policy
the listing links to is [PRIVACY.md](PRIVACY.md).

## License

The application code is [MIT](LICENSE). Bundled third-party material keeps its
own terms, listed in [NOTICE](NOTICE) and credited in the app under Settings →
About:

- Quran text: [Tanzil Project](https://tanzil.net), Creative Commons Attribution 3.0, used verbatim
- Speech model: [tarteel-ai/whisper-base-ar-quran](https://huggingface.co/tarteel-ai/whisper-base-ar-quran), Apache 2.0
- whisper.cpp and ggml: MIT
