#!/usr/bin/env python3
import csv
import re
import sqlite3
import sys
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CSV_PATH = ROOT / "data" / "ecdict.csv"
DB_PATH = ROOT / "app" / "src" / "main" / "assets" / "myenglish_dictionary.db"
DB_VERSION = 3
ECDICT_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"

EXCHANGE_LABELS = {
    "p": "过去式",
    "d": "过去分词",
    "i": "现在分词",
    "3": "第三人称单数",
    "r": "比较级",
    "t": "最高级",
    "s": "复数",
    "0": "原形",
    "1": "变体",
}

POS_RE = re.compile(r"^([a-z]+\.)(.*)$", re.IGNORECASE)


def create_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        PRAGMA temp_store = MEMORY;
        PRAGMA foreign_keys = OFF;

        CREATE TABLE words (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word TEXT NOT NULL UNIQUE,
            normalized_word TEXT NOT NULL UNIQUE,
            phonetic_us TEXT,
            phonetic_uk TEXT,
            frequency INTEGER NOT NULL DEFAULT 0,
            difficulty TEXT
        );

        CREATE TABLE definitions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
            part_of_speech TEXT NOT NULL,
            chinese_definition TEXT NOT NULL,
            english_definition TEXT,
            order_index INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE examples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
            sentence_en TEXT NOT NULL,
            sentence_zh TEXT NOT NULL,
            source TEXT,
            order_index INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE word_forms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
            form_type TEXT NOT NULL,
            form_value TEXT NOT NULL
        );

        CREATE TABLE relations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
            relation_type TEXT NOT NULL,
            related_word TEXT NOT NULL
        );

        CREATE TABLE favorites (
            word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE search_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            query TEXT NOT NULL,
            word_id INTEGER,
            searched_at INTEGER NOT NULL
        );

        CREATE TABLE notes (
            word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
            content TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );

        CREATE TABLE study_records (
            word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
            status TEXT NOT NULL DEFAULT 'new',
            review_count INTEGER NOT NULL DEFAULT 0,
            ease_factor REAL NOT NULL DEFAULT 2.5,
            interval_days INTEGER NOT NULL DEFAULT 0,
            next_review_at INTEGER NOT NULL,
            last_review_at INTEGER
        );

        CREATE VIRTUAL TABLE word_fts USING fts4(
            word_id,
            word,
            normalized_word,
            chinese_definition,
            english_definition,
            examples
        );

        CREATE INDEX index_words_normalized ON words(normalized_word);
        CREATE INDEX index_words_frequency ON words(frequency DESC, normalized_word);
        CREATE INDEX index_definitions_word ON definitions(word_id);
        CREATE INDEX index_examples_word ON examples(word_id);
        CREATE INDEX index_history_time ON search_history(searched_at DESC);
        """
    )


def frequency_score(row: dict[str, str]) -> int:
    for key in ("frq", "bnc"):
        try:
            value = int(row.get(key) or 0)
        except ValueError:
            value = 0
        if value > 0:
            return max(1, 1_000_000 - value)
    return 0


def difficulty(row: dict[str, str]) -> str | None:
    tags = [part for part in (row.get("tag") or "").split() if part]
    return " / ".join(tags) if tags else None


def definition_lines(row: dict[str, str]) -> list[tuple[str, str, str | None]]:
    translation = (row.get("translation") or "").replace("\\n", "\n").strip()
    english = (row.get("definition") or "").replace("\\n", "\n").strip() or None
    if not translation:
        return []
    result: list[tuple[str, str, str | None]] = []
    for index, line in enumerate(part.strip() for part in translation.splitlines() if part.strip()):
        match = POS_RE.match(line)
        if match:
            pos = match.group(1)
            chinese = match.group(2).strip() or line
        else:
            pos = (row.get("pos") or "").strip() or "释义"
            chinese = line
        result.append((pos, chinese, english if index == 0 else None))
    return result


def exchange_items(exchange: str) -> list[tuple[str, str]]:
    items: list[tuple[str, str]] = []
    for chunk in exchange.split("/"):
        if ":" not in chunk:
            continue
        key, value = chunk.split(":", 1)
        label = EXCHANGE_LABELS.get(key, key)
        value = value.strip()
        if value:
            items.append((label, value))
    return items


def main() -> int:
    if not CSV_PATH.exists():
        CSV_PATH.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {ECDICT_URL}")
        urllib.request.urlretrieve(ECDICT_URL, CSV_PATH)

    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    if DB_PATH.exists():
        DB_PATH.unlink()

    conn = sqlite3.connect(DB_PATH)
    create_schema(conn)
    inserted = 0

    with CSV_PATH.open("r", encoding="utf-8", newline="") as fp:
        reader = csv.DictReader(fp)
        conn.execute("BEGIN")
        for row in reader:
            word = (row.get("word") or "").strip()
            normalized = word.lower()
            definitions = definition_lines(row)
            if not word or not normalized or not definitions:
                continue
            try:
                cursor = conn.execute(
                    """
                    INSERT INTO words(word, normalized_word, phonetic_us, phonetic_uk, frequency, difficulty)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        word,
                        normalized,
                        row.get("phonetic") or None,
                        row.get("phonetic") or None,
                        frequency_score(row),
                        difficulty(row),
                    ),
                )
            except sqlite3.IntegrityError:
                continue

            word_id = cursor.lastrowid
            chinese_parts: list[str] = []
            english_parts: list[str] = []
            for order, (pos, chinese, english) in enumerate(definitions):
                chinese_parts.append(chinese)
                if english:
                    english_parts.append(english)
                conn.execute(
                    """
                    INSERT INTO definitions(word_id, part_of_speech, chinese_definition, english_definition, order_index)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (word_id, pos, chinese, english, order),
                )

            for form_type, form_value in exchange_items(row.get("exchange") or ""):
                conn.execute(
                    "INSERT INTO word_forms(word_id, form_type, form_value) VALUES (?, ?, ?)",
                    (word_id, form_type, form_value),
                )

            conn.execute(
                """
                INSERT INTO word_fts(word_id, word, normalized_word, chinese_definition, english_definition, examples)
                VALUES (?, ?, ?, ?, ?, '')
                """,
                (word_id, word, normalized, " ".join(chinese_parts), " ".join(english_parts)),
            )

            inserted += 1
            if inserted % 20_000 == 0:
                conn.commit()
                print(f"inserted {inserted}", flush=True)
                conn.execute("BEGIN")

    conn.commit()
    conn.execute("PRAGMA user_version = %d" % DB_VERSION)
    conn.execute("VACUUM")
    conn.close()
    print(f"built {DB_PATH} with {inserted} words")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
