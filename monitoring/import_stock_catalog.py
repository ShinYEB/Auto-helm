#!/usr/bin/env python3
"""Refresh a local stock search catalog from daily market caps and OpenDART codes."""
import argparse
import csv
import datetime as dt
import io
import os
import sqlite3
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

QUOTES_URL = "https://aikstockdata.com/data/public/quotes.csv"
DART_URL = "https://opendart.fss.or.kr/api/corpCode.xml"


def download(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("data/dev/control/catalog.sqlite"))
    args = parser.parse_args()
    key = os.environ.get("DART_API_KEY")
    if not key:
        raise SystemExit("DART_API_KEY environment variable is required")

    quotes = list(csv.DictReader(io.StringIO(download(QUOTES_URL).decode("utf-8-sig"))))
    if not quotes:
        raise SystemExit("Market quote source returned no records")
    dates = {row["basDt"] for row in quotes}
    if len(dates) != 1:
        raise SystemExit("Market quote source contains mixed dates")
    market_date = next(iter(dates))
    dt.datetime.strptime(market_date, "%Y%m%d")

    archive = zipfile.ZipFile(io.BytesIO(download(DART_URL + "?" + urllib.parse.urlencode({"crtfc_key": key}))))
    with archive.open("CORPCODE.xml") as source:
        root = ET.parse(source).getroot()
    codes = {item.findtext("stock_code", "").strip(): item.findtext("corp_code", "").strip()
             for item in root.findall("list") if item.findtext("stock_code", "").strip()}
    ranked = sorted((row for row in quotes if row["종목코드"] in codes and row["mrktTotAmt"].isdigit()),
                    key=lambda row: int(row["mrktTotAmt"]), reverse=True)
    if len(ranked) < 1000:
        raise SystemExit("Fewer than 1000 market-cap records matched OpenDART")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".tmp.sqlite")
    temporary.unlink(missing_ok=True)
    try:
        with sqlite3.connect(temporary) as db:
            db.execute("CREATE TABLE company (stock_code TEXT PRIMARY KEY, name TEXT NOT NULL, "
                       "corp_code TEXT NOT NULL, market TEXT NOT NULL, market_cap INTEGER NOT NULL, "
                       "rank INTEGER NOT NULL UNIQUE)")
            db.execute("CREATE INDEX company_name_idx ON company(name)")
            db.execute("CREATE TABLE catalog_meta (id INTEGER PRIMARY KEY CHECK(id=1), market_date TEXT NOT NULL, "
                       "imported_at TEXT NOT NULL, ranking_source TEXT NOT NULL, "
                       "mapping_source TEXT NOT NULL, matched_count INTEGER NOT NULL)")
            db.executemany("INSERT INTO company VALUES (?,?,?,?,?,?)", [
                (row["종목코드"], row["종목명"], codes[row["종목코드"]], row["mrktCtg"],
                 int(row["mrktTotAmt"]), rank)
                for rank, row in enumerate(ranked[:1000], 1)])
            db.execute("INSERT INTO catalog_meta VALUES (1,?,?,?,?,?)", (
                market_date, dt.datetime.now(dt.timezone.utc).isoformat(), QUOTES_URL,
                "OpenDART corpCode.xml", len(ranked)))
        os.replace(temporary, args.output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Imported 1000 stocks, market date {market_date}, matched {len(ranked)} listings")


if __name__ == "__main__":
    main()
