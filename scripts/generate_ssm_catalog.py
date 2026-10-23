#!/usr/bin/env python3
"""RomRaider のロガー定義 (logger.xml) から、アプリが読む SSM パラメータ表 (TSV) を生成する。

  python3 scripts/generate_ssm_catalog.py --logger-xml <RomRaider logger.xml> --ecu-id 5104584007

対象は SSM プロトコルの標準パラメータ (P) と、指定 ECU ID の拡張パラメータ (E)。
次のものは含めない:
- 他のパラメータから計算するもの (<depends> を持つ P200 など)。計算値はアプリ側で必要なものだけ作る
- ビット単位のスイッチ (<switches>、address に bit 属性があるもの)

1 行が「パラメータ 1 つの換算式 1 つ」。同じパラメータの先頭行 (conv_index 0) が RomRaider の既定の換算。
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

COLUMNS = ["kind", "id", "name", "ecu_id", "addresses", "length", "capability_index", "capability_bit",
           "conv_index", "units", "storagetype", "expr", "format"]
DEFAULT_OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/ssm_catalog.tsv"


def clean(text: str | None) -> str:
    return re.sub(r"[\t\r\n]+", " ", text or "").strip()


def conversion_rows(element: ET.Element) -> list[tuple[str, str, str, str]]:
    rows = []
    for c in element.iter("conversion"):
        rows.append((clean(c.get("units")), clean(c.get("storagetype")), clean(c.get("expr")), clean(c.get("format"))))
    # 同じ値の換算違いなのに、一部の換算だけ storagetype が抜けている定義がある（例: E36 は既定の換算だけ float の指定が無い）。
    # 抜けた方を整数と読むと値が化けるので、指定のある換算に揃える。指定どうしが食い違う場合は生成を止める
    explicit = {r[1] for r in rows if r[1]}
    if len(explicit) > 1:
        raise SystemExit(f"{element.get('id')}: conflicting storage types {sorted(explicit)}")
    if explicit:
        storage = explicit.pop()
        rows = [(u, s or storage, e, f) for u, s, e, f in rows]
    return rows


def source_version(xml_path: Path) -> str:
    head = xml_path.read_text(encoding="utf-8", errors="replace")[:2000]
    m = re.search(r"<!--(.*?)\n", head)
    return clean(m.group(1)) if m else "unknown"


def generate(xml_path: Path, ecu_ids: set[str]) -> tuple[list[list[str]], dict]:
    root = ET.parse(xml_path).getroot()
    ssm = next(p for p in root.iter("protocol") if p.get("id") == "SSM")
    rows: list[list[str]] = []
    stats = {"standard": 0, "extended": 0, "skipped_calculated": 0, "skipped_bit": 0}

    for p in ssm.find("parameters").findall("parameter"):
        if p.find("depends") is not None:
            stats["skipped_calculated"] += 1
            continue
        addresses = p.findall("address")
        if any(a.get("bit") for a in addresses):
            stats["skipped_bit"] += 1
            continue
        lengths = {a.get("length", "1") for a in addresses}
        if len(lengths) != 1:
            raise SystemExit(f"{p.get('id')}: mixed address lengths {lengths}")
        addr = ";".join(f"0x{int(a.text, 16):06X}" for a in addresses)
        length = lengths.pop()  # アドレスごとの長さ（複数アドレスのパラメータも長さは揃っている）
        for i, (units, storage, expr, fmt) in enumerate(conversion_rows(p)):
            rows.append(["std", p.get("id"), clean(p.get("name")), "", addr, length,
                         p.get("ecubyteindex", ""), p.get("ecubit", ""), str(i), units, storage, expr, fmt])
        stats["standard"] += 1

    for e in ssm.find("ecuparams").findall("ecuparam"):
        convs = conversion_rows(e)
        for ecu in e.findall("ecu"):
            ids = [x.strip() for x in ecu.get("id", "").split(",")]
            for ecu_id in sorted(ecu_ids.intersection(ids)):
                a = ecu.find("address")
                if a is None:
                    continue
                if a.get("bit"):
                    stats["skipped_bit"] += 1
                    continue
                for i, (units, storage, expr, fmt) in enumerate(convs):
                    rows.append(["ext", e.get("id"), clean(e.get("name")), ecu_id, f"0x{int(a.text, 16):06X}", a.get("length", "1"),
                                 "", "", str(i), units, storage, expr, fmt])
                stats["extended"] += 1
    return rows, stats


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--logger-xml", required=True, type=Path)
    ap.add_argument("--ecu-id", action="append", required=True, help="拡張パラメータを取り出す ECU ID（複数可）")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    rows, stats = generate(args.logger_xml, set(args.ecu_id))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# source: RomRaider logger definition: {source_version(args.logger_xml)}\n")
        f.write(f"# generator: scripts/generate_ssm_catalog.py --ecu-id {' --ecu-id '.join(sorted(args.ecu_id))}\n")
        f.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    print(f"wrote {args.out}: {len(rows)} rows, {stats}", file=sys.stderr)


if __name__ == "__main__":
    main()
