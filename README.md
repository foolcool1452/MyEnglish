# MyEnglish

MyEnglish is a simple offline English-Chinese dictionary for Android. It focuses on fast local lookup, clear word details, and system text-to-speech pronunciation.

## Features

- Offline English word search
- Chinese definition reverse lookup
- Local SQLite dictionary database
- Word detail page with phonetic transcription, definitions, English explanations, and word forms
- Android back-button support between home, results, and detail pages
- System TTS pronunciation with fallback to the device default engine
- Jetpack Compose UI

## Tech Stack

- Kotlin
- Jetpack Compose
- Android Gradle Plugin 9.2.0
- SQLite / SQLiteOpenHelper
- Gradle Wrapper 9.4.1

## Dictionary Data

The full dictionary database is generated from [ECDICT](https://github.com/skywind3000/ECDICT), a free English-Chinese dictionary database released under the MIT License.

The generated database is stored locally at:

```text
app/src/main/assets/myenglish_dictionary.db
```

This file is a generated asset of about 325 MB, so it is intentionally not committed. The installed app can carry the full database entirely on the phone after this file is generated.

If the database file is missing, the app falls back to the small seed dictionary in:

```text
app/src/main/assets/dictionary_seed.json
```

To regenerate the full database:

```powershell
py tools/build_dictionary_db.py
```

The script downloads `ecdict.csv` into the ignored `data/` directory and builds the SQLite asset.

## Build

Set `ANDROID_HOME` if your SDK is not discoverable, then run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install To A Connected Phone

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.myenglish.dictionary -c android.intent.category.LAUNCHER 1
```

## Project Layout

```text
app/src/main/java/com/myenglish/dictionary/MainActivity.kt        Compose UI and TTS
app/src/main/java/com/myenglish/dictionary/data/                  SQLite data layer
app/src/main/java/com/myenglish/dictionary/ui/theme/              Material theme
app/src/main/assets/                                               Dictionary assets
tools/build_dictionary_db.py                                       ECDICT to SQLite builder
```

## Notes

- Pronunciation depends on the Android device's installed TTS engine and language data.
- The full SQLite database is generated locally instead of stored in Git.
- `local.properties`, build outputs, screenshots, and downloaded raw CSV data are intentionally ignored.
