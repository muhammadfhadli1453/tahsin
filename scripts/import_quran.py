#!/usr/bin/env python3
"""Build the prepackaged Quran database that ships in the APK.

Room refuses to open a prepackaged database unless the file agrees with the
generated code on three things: the table/index DDL, ``PRAGMA user_version``, and
the identity hash in ``room_master_table``. Rather than duplicating the DDL here
and hoping it stays in sync, this script reads Room's own exported schema JSON
and writes exactly what that schema describes. Re-run it after any change to the
entities in ``com.tahsin.app.data.quran``.

Usage:
    python3 scripts/import_quran.py \
        --schema app/schemas/com.tahsin.app.data.quran.QuranDatabase/1.json \
        --uthmani scripts/data/quran-uthmani.json \
        --imlaai scripts/data/quran-simple-imlaai.json \
        --out app/src/main/assets/databases/quran.db
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from xml.etree import ElementTree
from pathlib import Path

SURAH_NAMES_EN = [
    "Al-Fatihah", "Al-Baqarah", "Ali 'Imran", "An-Nisa", "Al-Ma'idah", "Al-An'am",
    "Al-A'raf", "Al-Anfal", "At-Tawbah", "Yunus", "Hud", "Yusuf", "Ar-Ra'd",
    "Ibrahim", "Al-Hijr", "An-Nahl", "Al-Isra", "Al-Kahf", "Maryam", "Taha",
    "Al-Anbya", "Al-Hajj", "Al-Mu'minun", "An-Nur", "Al-Furqan", "Ash-Shu'ara",
    "An-Naml", "Al-Qasas", "Al-'Ankabut", "Ar-Rum", "Luqman", "As-Sajdah",
    "Al-Ahzab", "Saba", "Fatir", "Ya-Sin", "As-Saffat", "Sad", "Az-Zumar",
    "Ghafir", "Fussilat", "Ash-Shura", "Az-Zukhruf", "Ad-Dukhan", "Al-Jathiyah",
    "Al-Ahqaf", "Muhammad", "Al-Fath", "Al-Hujurat", "Qaf", "Adh-Dhariyat",
    "At-Tur", "An-Najm", "Al-Qamar", "Ar-Rahman", "Al-Waqi'ah", "Al-Hadid",
    "Al-Mujadila", "Al-Hashr", "Al-Mumtahanah", "As-Saff", "Al-Jumu'ah",
    "Al-Munafiqun", "At-Taghabun", "At-Talaq", "At-Tahrim", "Al-Mulk",
    "Al-Qalam", "Al-Haqqah", "Al-Ma'arij", "Nuh", "Al-Jinn", "Al-Muzzammil",
    "Al-Muddaththir", "Al-Qiyamah", "Al-Insan", "Al-Mursalat", "An-Naba",
    "An-Nazi'at", "Abasa", "At-Takwir", "Al-Infitar", "Al-Mutaffifin",
    "Al-Inshiqaq", "Al-Buruj", "At-Tariq", "Al-A'la", "Al-Ghashiyah",
    "Al-Fajr", "Al-Balad", "Ash-Shams", "Al-Layl", "Ad-Duhaa", "Ash-Sharh",
    "At-Tin", "Al-'Alaq", "Al-Qadr", "Al-Bayyinah", "Az-Zalzalah", "Al-'Adiyat",
    "Al-Qari'ah", "At-Takathur", "Al-'Asr", "Al-Humazah", "Al-Fil", "Quraysh",
    "Al-Ma'un", "Al-Kawthar", "Al-Kafirun", "An-Nasr", "Al-Masad", "Al-Ikhlas",
    "Al-Falaq", "An-Nas",
]


# Mirrors com.tahsin.app.domain.text.ArabicNormalizer. Keep the two in step:
# `text_normalized` is what the in-app search matches against.
REMOVABLE = re.compile(
    "["
    "ؐ-ؚ"
    "ً-ٟ"
    "ٰ"
    "ۖ-ۭ"
    "ـ"
    "࣓-ࣿ"
    "​-‏‪-‮⁠-⁤؜"
    "︀-️"
    "]"
)
STRIPPABLE = re.compile("[^ء-يٱ-ە\\s]")
SUPERSCRIPT_ALEF_PAIRS = (
    ("وٰ", "ا"),
    ("ىٰ", "ا"),
    ("يٰ", "ا"),
)
LETTER_FOLDING = str.maketrans({
    "آ": "ا",  # alef with madda
    "أ": "ا",  # alef with hamza above
    "إ": "ا",  # alef with hamza below
    "ٱ": "ا",  # alef wasla
    "ى": "ي",  # alef maqsura
    "ة": "ه",  # ta marbuta
    "ؤ": "و",  # waw with hamza
    "ئ": "ي",  # ya with hamza
    "ک": "ك",  # Persian kaf
    "ی": "ي",  # Persian ya
    "ء": "ا",  # standalone hamza
})


def normalize(text: str) -> str:
    for src, dst in SUPERSCRIPT_ALEF_PAIRS:
        if src in text:
            text = text.replace(src, dst)
    text = STRIPPABLE.sub(" ", REMOVABLE.sub("", text))
    return " ".join(text.translate(LETTER_FOLDING).split())


def load_boundaries(metadata_path: Path, tag: str) -> list[tuple[int, int, int]]:
    """Reads (index, surah, ayah) start markers for ``tag`` ("juz" or "page")."""
    root = ElementTree.parse(metadata_path).getroot()
    starts = [
        (int(node.get("index")), int(node.get("sura")), int(node.get("aya")))
        for node in root.iter(tag)
    ]
    if not starts:
        raise SystemExit(f"error: no <{tag}> entries in {metadata_path}")
    return sorted(starts, key=lambda entry: entry[0])


def boundary_lookup(starts: list[tuple[int, int, int]]):
    """Returns f(surah, ayah) -> index for a sorted start table."""

    def lookup(surah: int, ayah: int) -> int:
        result = starts[0][0]
        for index, start_surah, start_ayah in starts:
            if (surah, ayah) >= (start_surah, start_ayah):
                result = index
            else:
                break
        return result

    return lookup


def load_schema(path: Path) -> dict:
    schema = json.loads(path.read_text(encoding="utf-8"))["database"]
    return {
        "version": schema["version"],
        "identity_hash": schema["identityHash"],
        "entities": schema["entities"],
    }


def create_schema(cur: sqlite3.Cursor, schema: dict) -> None:
    for entity in schema["entities"]:
        table = entity["tableName"]
        cur.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            cur.execute(index["createSql"].replace("${TABLE_NAME}", table))

    # Room's own bookkeeping table. Without a matching identity hash here, Room
    # treats the file as an unknown schema and refuses to open it.
    cur.execute(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
    )
    cur.execute(
        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
        (schema["identity_hash"],),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    schema_dir = Path("app/schemas/com.tahsin.app.data.quran.QuranDatabase")
    default_schema = max(
        schema_dir.glob("*.json"),
        key=lambda f: int(f.stem),
        default=schema_dir / "1.json",
    )
    parser.add_argument(
        "--schema",
        type=Path,
        default=default_schema,
        help="Room's exported schema JSON for QuranDatabase (newest by default).",
    )
    parser.add_argument("--uthmani", type=Path, default=Path("scripts/data/quran-uthmani.json"))
    parser.add_argument(
        "--metadata",
        type=Path,
        default=Path("scripts/data/quran-data.xml"),
        help="Tanzil metadata XML; source of the juz and Madani page tables.",
    )
    parser.add_argument("--imlaai", type=Path, default=Path("scripts/data/quran-simple-imlaai.json"))
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("app/src/main/assets/databases/quran.db"),
    )
    args = parser.parse_args()

    for path in (args.schema, args.uthmani, args.imlaai, args.metadata):
        if not path.exists():
            parser.error(
                f"{path} not found. Build the app once so Room exports its schema, "
                "and make sure the Tanzil JSON files are in scripts/data/."
            )

    schema = load_schema(args.schema)
    juz_for = boundary_lookup(load_boundaries(args.metadata, "juz"))
    page_for = boundary_lookup(load_boundaries(args.metadata, "page"))
    uthmani = json.loads(args.uthmani.read_text(encoding="utf-8"))
    imlaai = json.loads(args.imlaai.read_text(encoding="utf-8"))

    imlaai_map = {
        (surah["number"], ayah["number"]): ayah["text"]
        for surah in imlaai["surahs"]
        for ayah in surah["ayahs"]
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    if args.out.exists():
        args.out.unlink()

    conn = sqlite3.connect(str(args.out))
    cur = conn.cursor()
    create_schema(cur, schema)

    total_ayahs = 0
    warnings = 0
    for surah in uthmani["surahs"]:
        sid = surah["number"]
        ayahs = [a for a in surah["ayahs"] if a["number"] > 0]
        revelation = "Meccan" if surah["loc"] == "مكية" else "Medinan"
        cur.execute(
            "INSERT INTO surahs (id, name_ar, name_en, ayah_count, revelation_type) "
            "VALUES (?, ?, ?, ?, ?)",
            (sid, surah["name"], SURAH_NAMES_EN[sid - 1], len(ayahs), revelation),
        )

        for ayah in ayahs:
            number = ayah["number"]
            # Search matches modern spelling, so index the imlaai text; the app
            # derives per-word comparison forms from text_uthmani at runtime.
            source = imlaai_map.get((sid, number))
            if source is None:
                source = ayah["text"]
                warnings += 1
                print(f"Warning: no imlaai text for {sid}:{number}", file=sys.stderr)
            cur.execute(
                "INSERT INTO ayahs (surah_id, ayah_number, juz, page, text_uthmani, text_normalized) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (
                    sid,
                    number,
                    juz_for(sid, number),
                    page_for(sid, number),
                    ayah["text"],
                    normalize(source),
                ),
            )
            total_ayahs += 1

    cur.execute(f"PRAGMA user_version = {schema['version']}")
    conn.commit()
    cur.execute("VACUUM")
    conn.close()

    size_mb = args.out.stat().st_size / (1024 * 1024)
    print(
        f"Wrote {args.out} — {total_ayahs} ayahs, schema v{schema['version']}, "
        f"identity {schema['identity_hash']}, {size_mb:.1f} MB"
    )
    if warnings:
        print(f"{warnings} ayah(s) fell back to the uthmani text", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
