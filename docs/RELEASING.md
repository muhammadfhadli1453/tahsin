# Releasing Tahsin on Google Play

The build already meets Play's technical requirements as of October 2026:
target API 36, 16 KB-aligned native code, arm64-only so Play never offers it
to phones it cannot run on, and an Android App Bundle (AAB) as the upload
format. This guide covers the rest: signing, the build itself, and Play
Console.

## 1. One-time: create an upload key

Play signs the app that users install with a key Google holds (Play App
Signing). You sign each upload with your own **upload key**. If you lose the
upload key, Google can reset it, but only after identity checks that take days.
Keep the keystore and its passwords in a password manager, never in git.

```bash
keytool -genkeypair -v -keystore ../tahsin-upload.jks -alias upload \
    -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # then fill in the passwords
```

`keystore.properties` and `*.jks` are git-ignored. Without them, release builds
come out unsigned.

## 2. One-time per machine: produce the model

Release builds bundle the Quran model, so the app works offline from first
launch. The model is 57 MB and is not stored in git:

```bash
pip install torch transformers numpy huggingface_hub
./scripts/convert_whisper_model.sh tarteel-ai/whisper-base-ar-quran
# -> build/models/ggml-tarteel-ai-whisper-base-ar-quran-q5_1.bin
```

The release build fails with a clear message if it is missing. To bundle a
different file, pass `-PtahsinModel=/path/to/model.bin`.

## 3. Every release: build

1. Raise `versionCode` (by at least 1; Play rejects a code it has seen) and set
   `versionName` in `app/build.gradle.kts`.
2. Run the checks and build the bundle:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:bundleRelease
   # -> app/build/outputs/bundle/release/app-release.aab  (~72 MB)
   ```

3. Optionally, try the exact release build on a phone first. A release build
   is signed differently from a debug build, so uninstall the debug app first
   (this deletes its data):

   ```bash
   ./gradlew :app:assembleRelease
   adb uninstall com.tahsin.app
   adb install app/build/outputs/apk/release/app-release.apk
   ```

   On first launch the app copies the bundled model into its storage, which
   takes a second or two.

## 4. One-time: Play Console setup

1. **Developer account.** Register at play.google.com/console ($25, one-off) and
   complete identity verification. Choose:
   - **Personal**: under your own name. New personal accounts must run a
     **closed test with at least 12 testers, opted in for 14 days in a row**,
     before they can apply for production access.
   - **Organization** (a registered company): needs a D-U-N-S number, but skips the
     12-tester rule.
2. **Create the app**: name "Tahsin", default language, App, Free. The package
   name comes from the first upload (`com.tahsin.app`) and can never be changed.
   If Play says it is taken, change `applicationId` in `app/build.gradle.kts`
   before uploading anything.
3. **Store listing**: copy from `docs/play-store-listing.md`. You also need:
   - App icon, 512 × 512 PNG
   - Feature graphic, 1024 × 500 PNG
   - At least 2 phone screenshots (a session in progress, the results screen,
     the mushaf with Tajweed colours)
4. **Privacy policy URL**: host `PRIVACY.md` publicly, for example as the
   GitHub file link once the repo is pushed, or GitHub Pages. Play requires one
   because the app uses the microphone.
5. **App content** (Policy → App content):
   - *Data safety*: the app collects and shares **no** data. Audio is
     processed on the device only, and recordings stay on the device, so
     neither counts as collected. Data is not encrypted in transit because none
     is sent. Users can delete their data: Settings → recordings, or uninstall.
   - *Ads*: no ads.
   - *Content rating*: fill in the questionnaire (reference/education, no
     objectionable content). Expect "Everyone" / PEGI 3.
   - *Target audience*: 13 and over is simplest. Including under-13s brings in
     the Families policy and its extra requirements.
   - *Government, financial, health and news app* declarations: no.
   - Microphone is not a restricted permission, so no permission declaration
     form is needed.

## 5. Every release: upload and roll out

1. Testing → Closed testing → create a release → upload the `.aab`. Add
   testers by email list or Google Group, and share the opt-in link.
2. For a new personal account, keep 12 or more testers opted in for 14 days,
   then apply for production access from the Dashboard. Google reviews the
   application, typically within a week.
3. Production → create a release → reuse the same AAB from the library →
   release notes → **staged rollout** (e.g. 20%) → review. A new app's first
   review can take several days.
4. Watch Android vitals (crashes, ANRs) in Play Console before going to 100%.

## Size

Play builds per-device APKs from the bundle, so an arm64 phone downloads about
70 MB, most of it the model. That is under Play's 200 MB limit for the base
module, so no asset packs are needed.
