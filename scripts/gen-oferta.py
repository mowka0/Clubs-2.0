#!/usr/bin/env python3
"""Генерирует обе копии оферты из docs/legal/oferta.md.

Оферта нужна в двух местах, которые собираются раздельно (Docker-контексты ./frontend и ./backend
не видят друг друга): шит оплаты и /about во фронте, «шторка» бота в бэке. Чтобы тексты не
разъезжались, источник один — markdown, а код генерируется. После правки md:

    python3 scripts/gen-oferta.py

Подстановки: ФИО, ИНН, цена и число дней бесплатного периода в md записаны реальными значениями,
в коде заменяются на параметры (значения приходят из настроек сервера).
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
MD = ROOT / "docs/legal/oferta.md"
TS = ROOT / "frontend/src/components/billing/offerSections.generated.ts"
KT = ROOT / "backend/src/main/kotlin/com/clubs/bot/OfferSections.kt"

# Что в md написано буквально → чем подставляется в коде (TS-выражение, Kotlin-шаблон).
TOKENS = [
    ("Варламов Иван Михайлович", "p.recipientName", "${recipientName}"),
    ("370211562724", "p.inn", "${inn}"),
    ("199 ₽", "p.priceLabel", "${priceLabel}"),
    ("15 дней", "p.trialDaysLabel", "${trialDaysLabel}"),
    ("@clubs_tech_support", "p.supportTelegram", "${supportTelegram}"),
    ("clubs.techsupport@gmail.com", "p.supportEmail", "${supportEmail}"),
]
PARAMS = ["recipientName", "inn", "priceLabel", "trialDaysLabel", "supportTelegram", "supportEmail"]
HEADER = "Сгенерировано scripts/gen-oferta.py из docs/legal/oferta.md — не править руками."


def parse(md: str):
    """Заголовок, дата редакции, разделы. Преамбула (до первого `##`) — раздел без номера;
    «Редакция от …» из неё вынимается: в коде дата идёт в шапку, а не в текст."""
    title, updated, sections, current = None, None, [], None
    for raw in md.splitlines():
        line = raw.strip().replace("**", "")
        if not line or line.startswith(">"):
            continue
        if line.startswith("# "):
            title = line[2:].strip()
        elif line.startswith("## "):
            current = (line[3:].strip(), [])
            sections.append(current)
        elif line.startswith("#"):
            raise SystemExit(f"неожиданный заголовок: {line[:60]}")
        elif current is None:
            m = re.match(r"Редакция от (.+?)\.\s*", line)
            if m:
                updated, line = m.group(1), line[m.end():]
            if not sections:
                sections.append((title, []))
            sections[0][1].append(line)
        else:
            current[1].append(line)
    if not (title and updated and len(sections) >= 3):
        raise SystemExit("md разобран не полностью: нет заголовка, даты редакции или разделов")
    numbers = [int(m.group(1)) for t, _ in sections[1:] for m in [re.match(r"(\d+)\. ", t)] if m]
    if numbers != list(range(1, len(sections))):
        raise SystemExit(f"разделы должны идти подряд 1..N, получено {numbers}")
    for t, paragraphs in sections:
        if not paragraphs:
            raise SystemExit(f"пустой раздел: {t}")
    for literal, _, _ in TOKENS:
        if literal not in md:
            raise SystemExit(f"в md не найден токен «{literal}» — подстановка в коде молча пропала бы")
    return updated, sections


def split_tokens(text: str):
    """Текст → список кусков: строка либо индекс токена."""
    pattern = "(" + "|".join(re.escape(t[0]) for t in TOKENS) + ")"
    for chunk in re.split(pattern, text):
        if not chunk:
            continue
        idx = next((i for i, t in enumerate(TOKENS) if t[0] == chunk), None)
        yield chunk if idx is None else idx


def ts_expr(text: str) -> str:
    parts = [json.dumps(c, ensure_ascii=False) if isinstance(c, str) else TOKENS[c][1] for c in split_tokens(text)]
    return " + ".join(parts) if parts else '""'


def kt_literal(text: str) -> str:
    out = []
    for c in split_tokens(text):
        out.append(TOKENS[c][2] if isinstance(c, int) else c.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$"))
    return '"' + "".join(out) + '"'


def main() -> None:
    updated, sections = parse(MD.read_text(encoding="utf-8"))

    ts = [f"// {HEADER}", "", "export interface OfferSection { title: string; paragraphs: string[] }",
          "export interface OfferParams { " + "; ".join(f"{p}: string" for p in PARAMS) + " }",
          f"export const OFFER_UPDATED = {json.dumps(updated, ensure_ascii=False)};", "",
          "export function offerSections(p: OfferParams): OfferSection[] {", "  return ["]
    for title, paragraphs in sections:
        ts.append(f"    {{ title: {json.dumps(title, ensure_ascii=False)}, paragraphs: [")
        ts += [f"      {ts_expr(par)}," for par in paragraphs]
        ts.append("    ] },")
    ts += ["  ];", "}", ""]
    TS.write_text("\n".join(ts), encoding="utf-8")

    kt = ["package com.clubs.bot", "", f"// {HEADER}", "",
          f'internal const val OFFER_UPDATED = "{updated}"', "",
          "internal fun offerSections(" + ", ".join(f"{p}: String" for p in PARAMS) + "): List<Pair<String, List<String>>> = listOf("]
    for title, paragraphs in sections:
        kt.append(f"    {kt_literal(title)} to listOf(")
        kt += [f"        {kt_literal(par)}," for par in paragraphs]
        kt.append("    ),")
    kt += [")", ""]
    KT.write_text("\n".join(kt), encoding="utf-8")
    print(f"ok: {len(sections)} разделов → {TS.relative_to(ROOT)}, {KT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
