# Agent Notes

## Goal

Maintain MyEnglish as a simple offline Android English-Chinese dictionary. Keep the app focused: search, results, word detail, and pronunciation.

## Development Rules

- Preserve the simple dictionary-first UX. Do not reintroduce learning, review, account, cloud sync, or social features unless explicitly requested.
- Keep all lookup data local to the device.
- Do not commit generated build outputs, screenshots, raw CSV downloads, or `local.properties`.
- The generated full SQLite dictionary is large and should stay uncommitted. Regenerate it with `py tools/build_dictionary_db.py` when needed.
- Prefer small, readable Kotlin changes over adding frameworks or abstraction layers.
- If changing database schema, update both `DictionaryDbHelper.kt` and `tools/build_dictionary_db.py`.
- If changing UI text, keep it short and suitable for a dictionary app.

## Useful Commands

```powershell
py tools/build_dictionary_db.py
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Important Files

```text
app/src/main/java/com/myenglish/dictionary/MainActivity.kt
app/src/main/java/com/myenglish/dictionary/data/DictionaryDbHelper.kt
app/src/main/java/com/myenglish/dictionary/data/DictionaryRepository.kt
tools/build_dictionary_db.py
```
