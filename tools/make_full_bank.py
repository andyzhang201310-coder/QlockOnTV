# -*- coding: utf-8 -*-
"""生成 1-6 年级 × 语文/数学/英语，每科每年级 1000 题（共 18000 题）。

原则：所有答案都来自可核对的数据或程序计算，绝不手写随机答案。
- 语文：识字表拼音、笔画/部首、同音字、多音字（人工整理）、成语释义、反义/近义词
- 数学：程序计算、单位换算、概念公式（人工整理）、应用题模板（程序求解）
- 英语：人教版 PEP 词表（词义、例句翻译、拼写、首字母）

运行：python tools/make_full_bank.py
"""
import io
import json
import os
import random
import re
import sys
from decimal import Decimal
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import make_questions
from bank_data import MATH_DEFS, POLYPHONES, SYNONYMS

SEED = 20261109
rng = random.Random(SEED)

DATA = os.path.join(HERE, "data")
OUT_PATH = os.path.normpath(
    os.path.join(HERE, "..", "app", "src", "main", "assets", "questions.json")
)

SUB_CN, SUB_MA, SUB_EN = "语文", "数学", "英语"
TARGET = 1000
VERSION = 4


def load(name):
    with io.open(os.path.join(DATA, name), encoding="utf-8") as f:
        return json.load(f)


HANZI = load("hanzi.json")
IDIOMS = load("idioms.json")
ANTONYMS = load("antonyms.json")
ANTONYMS_RAW = load("antonyms_raw.json")
SYN_GROUPS = load("syn_groups.json")
PEP = load("pep_vocab.json")

# ---------------------------------------------------------------- 汉字索引
CHAR_ROWS = []
for g in sorted(HANZI, key=int):
    for r in HANZI[g]:
        CHAR_ROWS.append(
            {"h": r["h"], "p": r["p"], "s": int(r["s"]), "r": r["r"], "grade": int(g)}
        )

CHAR_BY_H = {r["h"]: r for r in CHAR_ROWS}
ALL_CHARS = [r["h"] for r in CHAR_ROWS]
PY_BY_CHAR = {r["h"]: r["p"] for r in CHAR_ROWS}

CHARS_BY_PY = {}
for r in CHAR_ROWS:
    CHARS_BY_PY.setdefault(r["p"], []).append(r["h"])

POOL = {}
for g in range(1, 7):
    lim = min(g, 5)
    POOL[g] = [r for r in CHAR_ROWS if r["grade"] <= lim]

POLY_SET = {c for c, _ in POLYPHONES}

# 识字表里出现过多个读音的字（含未收进 POLYPHONES 的多音字），
# 一律不用于“正确拼音 / 声调 / 读音选字 / 同音字”题，避免多答案。
READINGS_PATH = os.path.join(DATA, "readings.json")
if os.path.exists(READINGS_PATH):
    with io.open(READINGS_PATH, encoding="utf-8") as _f:
        _READINGS = json.load(_f)
    POLY_SET |= {c for c, pys in _READINGS.items() if len(pys) > 1}

PINYIN_ALPHABET = set("abcdefghijklmnopqrstuvwxyzüāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜê")
ALL_PINYIN = sorted(
    {r["p"] for r in CHAR_ROWS if r["p"] and all(ch in PINYIN_ALPHABET for ch in r["p"])}
)
PINYIN_FULL = sorted(set(ALL_PINYIN) | {p for _, rs in POLYPHONES for p, _ in rs})


def is_plain_pinyin(p):
    return bool(p) and all(ch in PINYIN_ALPHABET for ch in p)


TONE_MARKS = {}
for marks, tone in [
    ("āēīōūǖ", 1),
    ("áéíóúǘ", 2),
    ("ǎěǐǒǔǚ", 3),
    ("àèìòùǜ", 4),
]:
    for ch in marks:
        TONE_MARKS[ch] = tone

CN_ORD = {1: "第一声", 2: "第二声", 3: "第三声", 4: "第四声"}


def tone_of(pinyin):
    for ch in pinyin:
        if ch in TONE_MARKS:
            return TONE_MARKS[ch]
    return 0


RADICAL_POOL = sorted({r["r"] for r in CHAR_ROWS if r["r"]})

# ---------------------------------------------------------------- 成语
IDIOM_MEANINGS = [x["m"] for x in IDIOMS]


def grams(s, k=4):
    if len(s) < k:
        return {s}
    return {s[i : i + k] for i in range(len(s) - k + 1)}


def similar_meaning(a, b):
    if a == b or a in b or b in a:
        return True
    return bool(grams(a) & grams(b))


# ---------------------------------------------------------------- 反义词 / 近义词
def greedy_match(pairs):
    used = set()
    out = []
    for a, b in pairs:
        if a in used or b in used or a == b:
            continue
        used.add(a)
        used.add(b)
        out.append((a, b))
    return out


ANT_MATCH = greedy_match(ANTONYMS)          # 165 对
SYN_MATCH = greedy_match(SYNONYMS)          # 131 对
ANT_WORD_POOL = sorted({w for p in ANT_MATCH for w in p})
SYN_WORD_POOL = sorted({w for p in SYN_MATCH for w in p})

ANT_ADJ = {}
for a, b in list(ANTONYMS_RAW) + list(ANT_MATCH):
    ANT_ADJ.setdefault(a, set()).add(b)
    ANT_ADJ.setdefault(b, set()).add(a)

SYN_ADJ = {}
for grp in SYN_GROUPS:
    ws = set(grp)
    for w in ws:
        SYN_ADJ.setdefault(w, set()).update(ws - {w})

# ---------------------------------------------------------------- 题池
USED = {SUB_CN: set(), SUB_MA: set(), SUB_EN: set()}


def add(bucket, subject, grade, q, correct, wrongs):
    q = q.strip()
    options = [str(correct).strip()] + [str(w).strip() for w in wrongs]
    if len(options) != 4:
        return False
    if any(not o for o in options):
        return False
    if len(set(options)) != 4:
        return False
    if q in USED[subject]:
        return False
    USED[subject].add(q)
    bucket.append(
        {"subject": subject, "grade": grade, "q": q, "options": options, "answer": 0}
    )
    return True


# ---------------------------------------------------------------- 语文
POLY_FACTS = []
for c, readings in POLYPHONES:
    for py, words in readings:
        for w in words:
            POLY_FACTS.append((c, py, w))
rng.shuffle(POLY_FACTS)
POLY_IDX = [0]

IDIOM_ORDER = list(range(len(IDIOMS)))
rng.shuffle(IDIOM_ORDER)
IDIOM_IDX = [0]


def chinese_questions(grade, need):
    out = []
    pool = list(POOL[grade])
    rng.shuffle(pool)
    non_poly = [
        r for r in pool if r["h"] not in POLY_SET and is_plain_pinyin(r["p"])
    ]
    rng.shuffle(non_poly)

    # 1. 汉字 -> 拼音（240）
    n = 0
    for r in non_poly:
        if n >= 240:
            break
        ans = r["p"]
        cands = [p for p in ALL_PINYIN if p != ans]
        if len(cands) < 3:
            continue
        if add(out, SUB_CN, grade, "「%s」的正确拼音是？" % r["h"], ans, rng.sample(cands, 3)):
            n += 1

    # 2. 拼音 -> 汉字（150）
    n = 0
    for r in non_poly:
        if n >= 150:
            break
        py = r["p"]
        cands = [
            c
            for c in ALL_CHARS
            if PY_BY_CHAR.get(c) != py and c not in POLY_SET
        ]
        if len(cands) < 3:
            continue
        if add(out, SUB_CN, grade, "读音是「%s」的汉字是？" % py, r["h"], rng.sample(cands, 3)):
            n += 1

    # 3. 声调（80）
    n = 0
    for r in non_poly:
        if n >= 80:
            break
        t = tone_of(r["p"])
        if not t:
            continue
        wrongs = [CN_ORD[k] for k in range(1, 5) if k != t]
        if add(out, SUB_CN, grade, "「%s」的读音是第几声？" % r["h"], CN_ORD[t], wrongs):
            n += 1

    # 4. 笔画数（100）
    n = 0
    for r in pool:
        if n >= 100:
            break
        s = r["s"]
        if s <= 0:
            continue
        wrongs = []
        for d in (1, -1, 2, -2, 3, -3, 4, -4):
            v = s + d
            if v > 0 and v != s and ("%d 画" % v) not in wrongs:
                wrongs.append("%d 画" % v)
            if len(wrongs) == 3:
                break
        if len(wrongs) < 3:
            continue
        if add(out, SUB_CN, grade, "「%s」字一共有几画？" % r["h"], "%d 画" % s, wrongs):
            n += 1

    # 5. 部首（100）
    n = 0
    for r in pool:
        if n >= 100:
            break
        rad = r["r"]
        if not rad:
            continue
        cands = [x for x in RADICAL_POOL if x != rad]
        if len(cands) < 3:
            continue
        if add(out, SUB_CN, grade, "「%s」的部首是？" % r["h"], rad, rng.sample(cands, 3)):
            n += 1

    # 6. 笔画比较（40）
    n = 0
    guard = 0
    while n < 40 and guard < 8000:
        guard += 1
        cand = [r for r in POOL[grade] if r["s"] > 0]
        four = rng.sample(cand, 4)
        if len({r["s"] for r in four}) != 4:
            continue
        four.sort(key=lambda r: -r["s"])
        hs = [r["h"] for r in four]
        q = "「%s」「%s」「%s」「%s」中笔画最多的是？" % tuple(hs)
        if add(out, SUB_CN, grade, q, hs[0], hs[1:]):
            n += 1

    # 7. 同音字（40）
    n = 0
    for r in non_poly:
        if n >= 40:
            break
        homos = [c for c in CHARS_BY_PY.get(r["p"], []) if c != r["h"] and c not in POLY_SET]
        if not homos:
            continue
        ans = rng.choice(homos)
        cands = [
            c
            for c in ALL_CHARS
            if PY_BY_CHAR.get(c) != r["p"] and c not in POLY_SET and c != ans
        ]
        if len(cands) < 3:
            continue
        if add(
            out,
            SUB_CN,
            grade,
            "下列汉字中，与「%s」读音相同的是？" % r["h"],
            ans,
            rng.sample(cands, 3),
        ):
            n += 1

    # 8. 多音字（60）
    n = 0
    guard = 0
    while n < 60 and POLY_IDX[0] < len(POLY_FACTS) and guard < 3000:
        guard += 1
        c, py, word = POLY_FACTS[POLY_IDX[0]]
        POLY_IDX[0] += 1
        other = [p for c2, rs in POLYPHONES if c2 == c for p, _ in rs if p != py]
        wrongs = list(dict.fromkeys(other))
        cands = [p for p in PINYIN_FULL if p != py and p not in wrongs]
        rng.shuffle(cands)
        for p in cands:
            if len(wrongs) >= 3:
                break
            wrongs.append(p)
        if len(wrongs) < 3:
            continue
        if add(
            out,
            SUB_CN,
            grade,
            "「%s」在「%s」中读作？" % (c, word),
            py,
            wrongs[:3],
        ):
            n += 1

    # 9. 成语释义（70）
    n = 0
    guard = 0
    while n < 70 and IDIOM_IDX[0] < len(IDIOMS) and guard < 3000:
        guard += 1
        item = IDIOMS[IDIOM_ORDER[IDIOM_IDX[0]]]
        IDIOM_IDX[0] += 1
        meaning = item["m"]
        wrongs = []
        tried = 0
        while len(wrongs) < 3 and tried < 600:
            tried += 1
            other = rng.choice(IDIOM_MEANINGS)
            if other == meaning or other in wrongs:
                continue
            if similar_meaning(meaning, other):
                continue
            wrongs.append(other)
        if len(wrongs) < 3:
            continue
        if add(out, SUB_CN, grade, "成语「%s」的意思是？" % item["w"], meaning, wrongs):
            n += 1

    # 10. 反义词（50）
    my_pairs = ANT_MATCH[grade * 25 - 25 : grade * 25]
    for a, b in my_pairs:
        for target, correct in ((a, b), (b, a)):
            forbidden = set(ANT_ADJ.get(target, set()))
            cands = [
                w
                for w in ANT_WORD_POOL
                if w != target and w != correct and w not in forbidden
            ]
            if len(cands) < 3:
                continue
            add(
                out,
                SUB_CN,
                grade,
                "「%s」的反义词是？" % target,
                correct,
                rng.sample(cands, 3),
            )

    # 11. 近义词（40）
    my_syn = SYN_MATCH[grade * 20 - 20 : grade * 20]
    for a, b in my_syn:
        for target, correct in ((a, b), (b, a)):
            forbidden = set(SYN_ADJ.get(target, set()))
            forbidden.add(target)
            forbidden.add(correct)
            cands = [w for w in SYN_WORD_POOL if w not in forbidden]
            if len(cands) < 3:
                continue
            add(
                out,
                SUB_CN,
                grade,
                "「%s」的近义词是？" % target,
                correct,
                rng.sample(cands, 3),
            )

    # 12. 补齐：原有 30 道基础题会占用少量题干，用拼音题补足
    if len(out) < need:
        for r in non_poly:
            if len(out) >= need:
                break
            ans = r["p"]
            cands = [p for p in ALL_PINYIN if p != ans]
            if len(cands) < 3:
                continue
            add(out, SUB_CN, grade, "「%s」的正确拼音是？" % r["h"], ans, rng.sample(cands, 3))
    if len(out) < need:
        for r in non_poly:
            if len(out) >= need:
                break
            py = r["p"]
            cands = [
                c for c in ALL_CHARS if PY_BY_CHAR.get(c) != py and c not in POLY_SET
            ]
            if len(cands) < 3:
                continue
            add(out, SUB_CN, grade, "读音是「%s」的汉字是？" % py, r["h"], rng.sample(cands, 3))

    return out
def dec_str(v):
    d = Decimal(v)
    d = d.normalize()
    s = format(d, "f")
    if s in ("-0", ""):
        s = "0"
    return s


def int_wrongs(ans, steps=(1, -1, 2, -2, 10, -10)):
    out = []
    for d in steps:
        v = ans + d
        if v != ans and v > 0 and str(v) not in out:
            out.append(str(v))
        if len(out) == 3:
            break
    return out


def dec_wrongs(ans, step):
    out = []
    for d in (step, -step, step * 10, -step * 10, step * 2, -step * 2):
        v = ans + d
        if v <= 0:
            continue
        s = dec_str(v)
        if s != dec_str(ans) and s not in out:
            out.append(s)
        if len(out) == 3:
            break
    return out


def frac_str(fr):
    fr = Fraction(fr)
    if fr.denominator == 1:
        return str(fr.numerator)
    return "%d/%d" % (fr.numerator, fr.denominator)


def frac_wrongs(ans):
    ans = Fraction(ans)
    cands = [
        ans + Fraction(1, 2),
        ans - Fraction(1, 2),
        ans + 1,
        ans - 1,
        Fraction(ans.numerator + 1, ans.denominator),
        Fraction(ans.numerator, ans.denominator + 1),
        Fraction(ans.denominator, ans.numerator) if ans.numerator else ans + 1,
    ]
    out = []
    for c in cands:
        if c <= 0 or c == ans:
            continue
        s = frac_str(c)
        if s not in out:
            out.append(s)
        if len(out) == 3:
            break
    return out


def add_math(out, grade, q, ans, wrongs):
    return add(out, SUB_MA, grade, q, ans, wrongs)


def math_compute(grade, need):
    out = []
    guard = 0
    while len(out) < need and guard < need * 60:
        guard += 1
        produced = None

        if grade == 1:
            k = rng.choice(["add", "sub", "ten", "three"])
            if k == "add":
                a, b = rng.randint(1, 19), rng.randint(1, 19)
                if a + b > 20:
                    continue
                ans = a + b
                produced = ("%d + %d = ?" % (a, b), str(ans), int_wrongs(ans))
            elif k == "sub":
                a = rng.randint(2, 20)
                b = rng.randint(1, a - 1)
                ans = a - b
                produced = ("%d - %d = ?" % (a, b), str(ans), int_wrongs(ans))
            elif k == "ten":
                a, b = rng.randint(1, 9) * 10, rng.randint(1, 9) * 10
                if rng.random() < 0.5 and a + b <= 100:
                    ans = a + b
                    produced = ("%d + %d = ?" % (a, b), str(ans), int_wrongs(ans))
                elif a > b:
                    ans = a - b
                    produced = ("%d - %d = ?" % (a, b), str(ans), int_wrongs(ans))
            else:
                a, b, c = rng.randint(1, 9), rng.randint(1, 9), rng.randint(1, 9)
                if a + b > 20 or a + b - c < 0:
                    continue
                ans = a + b - c
                produced = ("%d + %d - %d = ?" % (a, b, c), str(ans), int_wrongs(ans))

        elif grade == 2:
            k = rng.choice(["add", "sub", "mul", "div", "mix"])
            if k == "add":
                a, b = rng.randint(11, 80), rng.randint(2, 19)
                if a + b > 100:
                    continue
                ans = a + b
                produced = ("%d + %d = ?" % (a, b), str(ans), int_wrongs(ans))
            elif k == "sub":
                a, b = rng.randint(30, 100), rng.randint(2, 29)
                ans = a - b
                produced = ("%d - %d = ?" % (a, b), str(ans), int_wrongs(ans))
            elif k == "mul":
                a, b = rng.randint(2, 9), rng.randint(2, 9)
                ans = a * b
                produced = ("%d × %d = ?" % (a, b), str(ans), int_wrongs(ans, (1, -1, a, -a)))
            elif k == "div":
                b, c = rng.randint(2, 9), rng.randint(2, 9)
                ans = c
                produced = ("%d ÷ %d = ?" % (b * c, b), str(ans), int_wrongs(ans))
            else:
                a, b, c = rng.randint(1, 30), rng.randint(2, 9), rng.randint(2, 9)
                if a + b * c > 100:
                    continue
                ans = a + b * c
                produced = ("%d + %d × %d = ?" % (a, b, c), str(ans), int_wrongs(ans))

        elif grade == 3:
            k = rng.choice(["mul2", "mul3", "div", "add", "sub", "mix"])
            if k == "mul2":
                a, b = rng.randint(12, 99), rng.randint(2, 9)
                ans = a * b
                produced = ("%d × %d = ?" % (a, b), str(ans), int_wrongs(ans, (1, -1, a, -a)))
            elif k == "mul3":
                a, b = rng.randint(101, 999), rng.randint(2, 9)
                ans = a * b
                produced = ("%d × %d = ?" % (a, b), str(ans), int_wrongs(ans, (1, -1, 10, -10)))
            elif k == "div":
                b, c = rng.randint(2, 9), rng.randint(11, 99)
                ans = c
                produced = ("%d ÷ %d = ?" % (b * c, b), str(ans), int_wrongs(ans))
            elif k == "add":
                a, b = rng.randint(120, 800), rng.randint(30, 199)
                ans = a + b
                produced = ("%d + %d = ?" % (a, b), str(ans), int_wrongs(ans, (10, -10, 100, -100)))
            elif k == "sub":
                a, b = rng.randint(300, 900), rng.randint(100, 299)
                ans = a - b
                produced = ("%d - %d = ?" % (a, b), str(ans), int_wrongs(ans, (10, -10, 100, -100)))
            else:
                a, b, c = rng.randint(11, 40), rng.randint(2, 9), rng.randint(1, 20)
                ans = a * b + c
                produced = ("%d × %d + %d = ?" % (a, b, c), str(ans), int_wrongs(ans))

        elif grade == 4:
            k = rng.choice(["mul", "div", "decadd", "decsub", "mix"])
            if k == "mul":
                a, b = rng.randint(101, 999), rng.randint(11, 25)
                ans = a * b
                produced = ("%d × %d = ?" % (a, b), str(ans), int_wrongs(ans, (1, -1, 100, -100)))
            elif k == "div":
                b, c = rng.randint(12, 30), rng.randint(11, 40)
                ans = c
                produced = ("%d ÷ %d = ?" % (b * c, b), str(ans), int_wrongs(ans))
            elif k == "decadd":
                a = Decimal(rng.randint(11, 999)) / 10
                b = Decimal(rng.randint(11, 99)) / 10
                ans = a + b
                produced = ("%s + %s = ?" % (dec_str(a), dec_str(b)), dec_str(ans), dec_wrongs(ans, Decimal("0.1")))
            elif k == "decsub":
                a = Decimal(rng.randint(50, 999)) / 10
                b = Decimal(rng.randint(11, 49)) / 10
                ans = a - b
                produced = ("%s - %s = ?" % (dec_str(a), dec_str(b)), dec_str(ans), dec_wrongs(ans, Decimal("0.1")))
            else:
                a, b, c = rng.randint(2, 30), rng.randint(2, 9), rng.randint(2, 9)
                ans = (a + b) * c
                produced = ("(%d + %d) × %d = ?" % (a, b, c), str(ans), int_wrongs(ans))

        elif grade == 5:
            k = rng.choice(["decmul", "decdiv", "decadd", "fracadd", "fracmul", "eq"])
            if k == "decmul":
                a = Decimal(rng.randint(11, 99)) / 10
                b = rng.randint(2, 9)
                ans = a * b
                produced = ("%s × %d = ?" % (dec_str(a), b), dec_str(ans), dec_wrongs(ans, Decimal("0.1")))
            elif k == "decdiv":
                c = Decimal(rng.randint(11, 99)) / 10
                b = rng.randint(2, 9)
                ans = c
                produced = ("%s ÷ %d = ?" % (dec_str(c * b), b), dec_str(ans), dec_wrongs(ans, Decimal("0.1")))
            elif k == "decadd":
                a = Decimal(rng.randint(11, 999)) / 100
                b = Decimal(rng.randint(11, 99)) / 100
                ans = a + b
                produced = ("%s + %s = ?" % (dec_str(a), dec_str(b)), dec_str(ans), dec_wrongs(ans, Decimal("0.01")))
            elif k == "fracadd":
                d = rng.choice([5, 6, 7, 8, 9, 10, 12])
                a = rng.randint(1, d - 2)
                b = rng.randint(1, d - 1 - a)
                ans = Fraction(a + b, d)
                produced = (
                    "%d/%d + %d/%d = ?" % (a, d, b, d),
                    frac_str(ans),
                    frac_wrongs(ans),
                )
            elif k == "fracmul":
                a, b = rng.randint(1, 8), rng.randint(2, 9)
                while a >= b:
                    a, b = rng.randint(1, 8), rng.randint(2, 9)
                c, d = rng.randint(1, 8), rng.randint(2, 9)
                while c >= d:
                    c, d = rng.randint(1, 8), rng.randint(2, 9)
                ans = Fraction(a, b) * Fraction(c, d)
                produced = (
                    "%d/%d × %d/%d = ?" % (a, b, c, d),
                    frac_str(ans),
                    frac_wrongs(ans),
                )
            else:
                b = Decimal(rng.randint(11, 99)) / 10
                a = Decimal(rng.randint(11, 99)) / 10
                if a >= b:
                    continue
                ans = b - a
                produced = (
                    "x + %s = %s，x = ?" % (dec_str(a), dec_str(b)),
                    dec_str(ans),
                    dec_wrongs(ans, Decimal("0.1")),
                )

        else:
            k = rng.choice(["fracmul", "fracdiv", "percent", "ratio", "prop", "circle", "circlearea", "decpct"])
            if k == "fracmul":
                a, b = rng.randint(1, 8), rng.randint(2, 9)
                while a >= b:
                    a, b = rng.randint(1, 8), rng.randint(2, 9)
                c, d = rng.randint(1, 8), rng.randint(2, 9)
                while c >= d:
                    c, d = rng.randint(1, 8), rng.randint(2, 9)
                ans = Fraction(a, b) * Fraction(c, d)
                produced = ("%d/%d × %d/%d = ?" % (a, b, c, d), frac_str(ans), frac_wrongs(ans))
            elif k == "fracdiv":
                a, b = rng.randint(1, 8), rng.randint(2, 9)
                while a >= b:
                    a, b = rng.randint(1, 8), rng.randint(2, 9)
                c, d = rng.randint(1, 8), rng.randint(2, 9)
                while c >= d:
                    c, d = rng.randint(1, 8), rng.randint(2, 9)
                ans = Fraction(a, b) / Fraction(c, d)
                produced = ("%d/%d ÷ %d/%d = ?" % (a, b, c, d), frac_str(ans), frac_wrongs(ans))
            elif k == "percent":
                n = rng.choice([20, 40, 50, 60, 80, 100, 120, 200, 300, 400])
                p = rng.choice([5, 10, 15, 20, 25, 40, 50, 60, 75])
                if (n * p) % 100 != 0:
                    continue
                ans = n * p // 100
                produced = ("%d 的 %d%% 是多少？" % (n, p), str(ans), int_wrongs(ans, (1, -1, 10, -10, n // 10)))
            elif k == "ratio":
                a, b = rng.randint(1, 12), rng.randint(2, 12)
                if a == b:
                    continue
                g = Fraction(a, b)
                ans = "%d:%d" % (g.numerator, g.denominator)
                produced = (
                    "把 %d : %d 化成最简单的整数比是？" % (a, b),
                    ans,
                    ["%d:%d" % (a, b), "%d:%d" % (b, a), "%d:%d" % (g.denominator, g.numerator)],
                )
            elif k == "prop":
                a, b, c = rng.randint(2, 9), rng.randint(2, 9), rng.randint(2, 9)
                if (b * c) % a != 0:
                    continue
                ans = b * c // a
                produced = (
                    "%d : %d = %d : x，x = ?" % (a, b, c),
                    str(ans),
                    int_wrongs(ans, (a, -a, c, -c)),
                )
            elif k == "circle":
                r = rng.randint(2, 10)
                ans = Decimal("3.14") * 2 * r
                produced = (
                    "一个圆的半径是 %d 厘米，它的周长是多少厘米？（π 取 3.14）" % r,
                    dec_str(ans),
                    dec_wrongs(ans, Decimal("6.28")),
                )
            elif k == "circlearea":
                r = rng.randint(2, 10)
                ans = Decimal("3.14") * r * r
                produced = (
                    "一个圆的半径是 %d 厘米，它的面积是多少平方厘米？（π 取 3.14）" % r,
                    dec_str(ans),
                    dec_wrongs(ans, Decimal("3.14")),
                )
            else:
                v = rng.randint(1, 99)
                d = Decimal(v) / 100
                produced = (
                    "把 %s 化成百分数是多少？" % dec_str(d),
                    "%d%%" % v,
                    ["%d%%" % (v * 10), "%s%%" % dec_str(d), "%d%%" % max(1, v // 10), "%d%%" % (v + 100)],
                )

        if not produced:
            continue
        q, ans, wrongs = produced
        if len(wrongs) < 3:
            continue
        add_math(out, grade, q, ans, wrongs[:3])

    return out


# ---------------------------------------------------------------- 数学单位
UNITS = {
    1: [("元", "角", 10), ("角", "分", 10)],
    2: [("米", "厘米", 100), ("米", "分米", 10), ("分米", "厘米", 10), ("时", "分", 60), ("分", "秒", 60)],
    3: [("千米", "米", 1000), ("吨", "千克", 1000), ("千克", "克", 1000), ("平方米", "平方分米", 100), ("平方分米", "平方厘米", 100), ("日", "时", 24), ("年", "月", 12)],
    4: [("公顷", "平方米", 10000), ("平方千米", "公顷", 100), ("升", "毫升", 1000), ("立方米", "立方分米", 1000), ("立方分米", "立方厘米", 1000)],
    5: [("立方米", "立方分米", 1000), ("立方分米", "立方厘米", 1000), ("升", "毫升", 1000), ("吨", "千克", 1000)],
    6: [("公顷", "平方米", 10000), ("平方千米", "平方米", 1000000), ("时", "分", 60), ("分", "秒", 60), ("日", "时", 24)],
}


def math_units(grade, need):
    out = []
    pairs = UNITS[grade]
    guard = 0
    while len(out) < need and guard < need * 40:
        guard += 1
        u1, u2, rate = rng.choice(pairs)
        n = rng.randint(1, 30)
        if rng.random() < 0.5:
            ans = n * rate
            q = "%d %s = ? %s" % (n, u1, u2)
            correct = "%d %s" % (ans, u2)
            wrongs = [
                "%d %s" % (ans * 10, u2),
                "%d %s" % (ans // 10, u2) if ans // 10 > 0 else "%d %s" % (ans + 99, u2),
                "%d %s" % (ans + rate, u2),
                "%d %s" % (ans - rate, u2) if ans - rate > 0 else "%d %s" % (ans + 2 * rate, u2),
            ]
        else:
            total = n * rate
            q = "%d %s = ? %s" % (total, u2, u1)
            correct = "%d %s" % (n, u1)
            wrongs = [
                "%d %s" % (n * 10, u1),
                "%d %s" % (n * 100, u1),
                "%d %s" % (n + 1, u1),
                "%d %s" % (n - 1, u1) if n - 1 > 0 else "%d %s" % (n + 2, u1),
            ]
        uniq = []
        for w in wrongs:
            if w != correct and w not in uniq:
                uniq.append(w)
            if len(uniq) == 3:
                break
        if len(uniq) < 3:
            continue
        add_math(out, grade, q, correct, uniq)
    return out


def math_defs(grade, need):
    out = []
    for q, correct, d1, d2, d3 in MATH_DEFS[grade]:
        add_math(out, grade, q, correct, [d1, d2, d3])
        if len(out) >= need:
            break
    return out


# ---------------------------------------------------------------- 数学应用题
def wp_g1():
    k = rng.randint(1, 12)
    if k == 1:
        a, b = rng.randint(1, 12), rng.randint(1, 7)
        if a + b > 20:
            return None
        ans = a + b
        return ("小明有 %d 个苹果，妈妈又给了他 %d 个。现在一共有多少个苹果？" % (a, b), str(ans), int_wrongs(ans))
    if k == 2:
        a = rng.randint(5, 20)
        b = rng.randint(1, a - 1)
        ans = a - b
        return ("树上有 %d 只小鸟，飞走了 %d 只。还剩多少只？" % (a, b), str(ans), int_wrongs(ans))
    if k == 3:
        a, b = rng.randint(2, 9), rng.randint(2, 9)
        ans = a + b - 1
        return ("排队时，小明从前数排第 %d 个，从后数排第 %d 个。这一队一共有多少人？" % (a, b), str(ans), int_wrongs(ans))
    if k == 4:
        a, b = rng.randint(5, 20), rng.randint(1, 4)
        ans = a - b
        return ("一共有 %d 个小朋友，其中男生 %d 人。女生有多少人？" % (a, b), str(ans), int_wrongs(ans))
    if k == 5:
        a, b = rng.randint(5, 18), rng.randint(1, 4)
        ans = a - b
        return ("小红有 %d 朵花，比小明多 %d 朵。小明有多少朵？" % (a, b), str(ans), int_wrongs(ans))
    if k == 6:
        a, b = rng.randint(7, 15), rng.randint(1, 6)
        ans = a - b
        return ("哥哥今年 %d 岁，弟弟比哥哥小 %d 岁。弟弟今年多少岁？" % (a, b), str(ans), int_wrongs(ans))
    if k == 7:
        a = rng.randint(1, 10)
        b = rng.randint(a, 20)
        ans = b - a + 1
        return ("从 %d 数到 %d，一共数了多少个数？" % (a, b), str(ans), int_wrongs(ans))
    if k == 8:
        a, b = rng.randint(2, 15), rng.randint(1, 10)
        ans = a + b
        return ("一本书看了 %d 页，还剩 %d 页。这本书一共有多少页？" % (a, b), str(ans), int_wrongs(ans))
    if k == 9:
        a = rng.randint(10, 20)
        b = rng.randint(1, 4)
        c = rng.randint(1, 4)
        if a - b + c > 20:
            return None
        ans = a - b + c
        return ("公交车上原有 %d 人，到站下去 %d 人，又上来 %d 人。现在车上有多少人？" % (a, b, c), str(ans), int_wrongs(ans))
    if k == 10:
        a, b = rng.randint(1, 8), rng.randint(1, 8)
        ans = a + b
        return ("一支铅笔 %d 角，一块橡皮 %d 角。买一支铅笔和一块橡皮一共要多少角？" % (a, b), str(ans), int_wrongs(ans))
    if k == 11:
        a = rng.randint(5, 15)
        b = rng.randint(1, 4)
        c = rng.randint(1, 4)
        ans = a - b + c
        return ("有 %d 颗糖，吃掉 %d 颗，又买来 %d 颗。现在有多少颗？" % (a, b, c), str(ans), int_wrongs(ans))
    a, b, c = rng.randint(10, 20), rng.randint(1, 5), rng.randint(1, 5)
    if a - b - c < 0:
        return None
    ans = a - b - c
    return ("妈妈买了 %d 个鸡蛋，第一天吃了 %d 个，第二天吃了 %d 个。还剩多少个？" % (a, b, c), str(ans), int_wrongs(ans))


def wp_g2():
    k = rng.randint(1, 12)
    if k == 1:
        a, b = rng.randint(2, 9), rng.randint(2, 9)
        ans = a * b
        return ("每盒有 %d 个乒乓球，%d 盒一共有多少个？" % (a, b), str(ans), int_wrongs(ans, (1, -1, a, -a)))
    if k == 2:
        b, q = rng.randint(2, 9), rng.randint(2, 9)
        return ("把 %d 个苹果平均分给 %d 个小朋友，每人分到几个？" % (b * q, b), str(q), int_wrongs(q))
    if k == 3:
        b, q = rng.randint(2, 9), rng.randint(2, 9)
        return ("有 %d 个桃子，每 %d 个装一袋，可以装几袋？" % (b * q, b), str(q), int_wrongs(q))
    if k == 4:
        a, b = rng.randint(2, 9), rng.randint(2, 9)
        ans = b + 1
        return ("一条小路长 %d 米，每隔 %d 米栽一棵树（两端都栽），一共要栽多少棵？" % (a * b, a), str(ans), int_wrongs(ans))
    if k == 5:
        a, b = rng.randint(10, 90), rng.randint(2, 9)
        ans = a - b
        return ("小明有 %d 元，买文具用了 %d 元。还剩多少元？" % (a, b), str(ans), int_wrongs(ans))
    if k == 6:
        a = rng.randint(2, 9)
        ans = 7 * a
        return ("一个星期有 7 天，%d 个星期一共有多少天？" % a, str(ans), int_wrongs(ans, (7, -7, 1, -1)))
    if k == 7:
        a, b = rng.randint(3, 12), rng.randint(2, 9)
        ans = a * b
        return ("一本书每天看 %d 页，%d 天可以看完。这本书一共有多少页？" % (a, b), str(ans), int_wrongs(ans))
    if k == 8:
        a, b = rng.randint(15, 40), rng.randint(15, 40)
        ans = a + b
        return ("二（1）班有男生 %d 人，女生 %d 人。全班一共有多少人？" % (a, b), str(ans), int_wrongs(ans))
    if k == 9:
        b = rng.randint(2, 9)
        q = rng.randint(2, 9)
        r = rng.randint(1, b - 1)
        return ("把 %d 个苹果平均分给 %d 个小朋友，每人最多分几个？" % (b * q + r, b), str(q), int_wrongs(q))
    if k == 10:
        a, b = rng.randint(10, 50), rng.randint(2, 9)
        ans = a - b
        return ("一根绳子长 %d 米，剪去 %d 米。还剩多少米？" % (a, b), str(ans), int_wrongs(ans))
    if k == 11:
        a, b = rng.randint(3, 9), rng.randint(2, 9)
        ans = a * b
        return ("%d 个小朋友，每人折 %d 只纸鹤。一共折了多少只？" % (a, b), str(ans), int_wrongs(ans, (1, -1, a, -a)))
    a, b = rng.randint(2, 9), rng.randint(2, 9)
    ans = a * b
    return ("妈妈买了 %d 千克苹果，每千克 %d 元。一共花了多少元？" % (a, b), str(ans), int_wrongs(ans, (1, -1, a, -a)))


def wp_g3():
    k = rng.randint(1, 12)
    if k == 1:
        b, a = rng.randint(2, 9), rng.randint(2, 9)
        return ("3 支同样的笔要 %d 元，买 %d 支这样的笔要多少元？" % (3 * b, a), str(a * b), int_wrongs(a * b))
    if k == 2:
        a, b = rng.randint(5, 20), rng.randint(2, 9)
        ans = 2 * (a + b)
        return ("一个长方形长 %d 厘米，宽 %d 厘米。它的周长是多少厘米？" % (a, b), str(ans), int_wrongs(ans))
    if k == 3:
        a, b = rng.randint(5, 20), rng.randint(2, 9)
        ans = a * b
        return ("一个长方形长 %d 厘米，宽 %d 厘米。它的面积是多少平方厘米？" % (a, b), str(ans), int_wrongs(ans))
    if k == 4:
        a = rng.randint(3, 20)
        ans = 4 * a
        return ("一个正方形的边长是 %d 厘米。它的周长是多少厘米？" % a, str(ans), int_wrongs(ans))
    if k == 5:
        a, b = rng.randint(20, 90), rng.randint(2, 8)
        ans = a * b
        return ("一辆汽车每小时行 %d 千米，%d 小时行多少千米？" % (a, b), str(ans), int_wrongs(ans))
    if k == 6:
        a, b, c = rng.randint(10, 30), rng.randint(10, 30), rng.randint(10, 30)
        d = rng.randint(10, 30)
        total = a + b + c + d
        total -= total % 4
        d -= (a + b + c + d) - total
        ans = total // 4
        return ("%d、%d、%d、%d 四个数的平均数是多少？" % (a, b, c, d), str(ans), int_wrongs(ans))
    if k == 7:
        b, q = rng.randint(2, 9), rng.randint(10, 40)
        return ("三年级有 %d 人，每 %d 人一组，可以分成几组？" % (b * q, b), str(q), int_wrongs(q))
    if k == 8:
        a, b = rng.randint(5, 20), rng.randint(3, 12)
        ans = a * b
        return ("果园里有 %d 行苹果树，每行 %d 棵。一共有多少棵？" % (a, b), str(ans), int_wrongs(ans))
    if k == 9:
        b, q = rng.randint(3, 12), rng.randint(5, 20)
        return ("一本书共 %d 页，每天看 %d 页，几天可以看完？" % (b * q, b), str(q), int_wrongs(q))
    if k == 10:
        a = rng.randint(1, 11)
        return ("下午 %d 时用 24 时计时法表示是几时？" % a, "%d 时" % (a + 12), ["%d 时" % a, "%d 时" % (a + 10), "%d 时" % (a + 14)])
    if k == 11:
        a, b = rng.randint(5, 30), rng.randint(10, 90)
        ans = a * b
        return ("学校买了 %d 个足球，每个 %d 元。一共花了多少元？" % (a, b), str(ans), int_wrongs(ans))
    a, b = rng.randint(5, 20), rng.randint(3, 12)
    ans = a * b
    return ("一块长方形菜地长 %d 米，宽 %d 米。它的面积是多少平方米？" % (a, b), str(ans), int_wrongs(ans))


def wp_g4():
    k = rng.randint(1, 12)
    if k == 1:
        heads = rng.randint(5, 20)
        rabbits = rng.randint(1, heads - 1)
        legs = 2 * heads + 2 * rabbits
        return ("笼子里有鸡和兔共 %d 只，一共有 %d 只脚。兔有几只？" % (heads, legs), str(rabbits), int_wrongs(rabbits))
    if k == 2:
        v1, v2, t = rng.randint(40, 80), rng.randint(40, 80), rng.randint(2, 9)
        dist = (v1 + v2) * t
        return ("两地相距 %d 米，甲、乙两人同时从两地相向而行，甲每分钟走 %d 米，乙每分钟走 %d 米。几分钟后相遇？" % (dist, v1, v2), str(t), int_wrongs(t))
    if k == 3:
        a, b = rng.randint(11, 99), rng.randint(11, 30)
        ans = a * b
        return ("每箱牛奶有 %d 瓶，%d 箱一共有多少瓶？" % (a, b), str(ans), int_wrongs(ans, (1, -1, 10, -10)))
    if k == 4:
        v, t = rng.randint(40, 120), rng.randint(2, 9)
        ans = v * t
        return ("一辆汽车每小时行 %d 千米，%d 小时行多少千米？" % (v, t), str(ans), int_wrongs(ans))
    if k == 5:
        nums = [rng.randint(10, 40) for _ in range(5)]
        total = sum(nums) - (sum(nums) % 5)
        nums[-1] -= sum(nums) - total
        ans = total // 5
        return ("%d、%d、%d、%d、%d 五个数的平均数是多少？" % tuple(nums), str(ans), int_wrongs(ans))
    if k == 6:
        a = rng.randint(20, 80)
        b = rng.randint(20, 80)
        if a + b >= 178:
            return None
        ans = 180 - a - b
        return ("一个三角形中，两个角分别是 %d 度和 %d 度，第三个角是多少度？" % (a, b), "%d 度" % ans, ["%d 度" % (ans + 10), "%d 度" % (ans - 10), "%d 度" % (360 - a - b)])
    if k == 7:
        a, b = rng.randint(5, 30), rng.randint(3, 20)
        ans = a * b
        return ("一个平行四边形的底是 %d 厘米，高是 %d 厘米，面积是多少平方厘米？" % (a, b), str(ans), int_wrongs(ans))
    if k == 8:
        a, b = rng.randint(3, 12), rng.randint(5, 20)
        h = rng.randint(2, 10) * 2
        ans = (a + b) * h // 2
        return ("一个梯形上底 %d 厘米，下底 %d 厘米，高 %d 厘米，面积是多少平方厘米？" % (a, b, h), str(ans), int_wrongs(ans))
    if k == 9:
        a, b = rng.randint(2, 9), rng.randint(3, 12)
        ans = b - 1
        return ("一条小路长 %d 米，每隔 %d 米栽一棵树（两端都不栽），一共要栽多少棵？" % (a * b, a), str(ans), int_wrongs(ans))
    if k == 10:
        a, b = rng.randint(1, 9), rng.randint(1, 999)
        ans = 1000 * a + b
        return ("%d 吨 %d 千克 = 多少千克？" % (a, b), str(ans), int_wrongs(ans, (1000, -1000, 500, -500)))
    if k == 11:
        a, b, c = rng.randint(2, 8), rng.randint(2, 9), rng.randint(5, 30)
        return ("%d 台机器 %d 小时生产 %d 个零件，1 台机器 1 小时生产多少个？" % (a, b, a * b * c), str(c), int_wrongs(c))
    t, v = rng.randint(2, 9), rng.randint(30, 100)
    ans = v
    return ("小明 %d 小时走了 %d 千米，他每小时走多少千米？" % (t, v * t), str(ans), int_wrongs(ans))


def wp_g5():
    k = rng.randint(1, 12)
    if k == 1:
        a, b = rng.choice([(4, 12), (12, 4), (6, 3), (3, 6), (10, 15), (15, 10), (8, 8)])
        ans = int(Fraction(1, 1) / (Fraction(1, a) + Fraction(1, b)))
        return ("一项工程，甲单独做要 %d 天完成，乙单独做要 %d 天完成。两人合作，几天可以完成？" % (a, b), "%d 天" % ans, ["%d 天" % (ans + 1), "%d 天" % (a + b), "%d 天" % (ans + 2)])
    if k == 2:
        c = rng.choice([2, 3, 4, 5, 6])
        b = rng.randint(1, c - 1)
        n = c * rng.randint(2, 12)
        ans = n * b // c
        return ("%d 的 %d/%d 是多少？" % (n, b, c), str(ans), int_wrongs(ans))
    if k == 3:
        a, b, h = rng.randint(2, 12), rng.randint(2, 9), rng.randint(2, 9)
        ans = a * b * h
        return ("一个长方体长 %d 厘米，宽 %d 厘米，高 %d 厘米，体积是多少立方厘米？" % (a, b, h), str(ans), int_wrongs(ans))
    if k == 4:
        a = rng.randint(2, 12)
        ans = 6 * a * a
        return ("一个正方体的棱长是 %d 厘米，它的表面积是多少平方厘米？" % a, str(ans), int_wrongs(ans))
    if k == 5:
        a, h = rng.randint(3, 20), rng.randint(3, 15)
        ans = a * h
        return ("一个平行四边形的底是 %d 厘米，高是 %d 厘米，面积是多少平方厘米？" % (a, h), str(ans), int_wrongs(ans))
    if k == 6:
        a, h = rng.randint(3, 20), rng.randint(2, 10) * 2
        ans = a * h // 2
        return ("一个三角形的底是 %d 厘米，高是 %d 厘米，面积是多少平方厘米？" % (a, h), str(ans), int_wrongs(ans))
    if k == 7:
        a = Decimal(rng.randint(11, 99)) / 10
        b = rng.randint(2, 9)
        ans = a * b
        return ("每千克苹果 %s 元，买 %d 千克要多少元？" % (dec_str(a), b), dec_str(ans), dec_wrongs(ans, Decimal("0.1")))
    if k == 8:
        nums = [rng.randint(10, 40) for _ in range(4)]
        total = sum(nums) - (sum(nums) % 4)
        nums[-1] -= sum(nums) - total
        ans = total // 4
        return ("%d、%d、%d、%d 四个数的平均数是多少？" % tuple(nums), str(ans), int_wrongs(ans))
    if k == 9:
        a = Decimal(rng.randint(11, 99)) / 10
        b = Decimal(rng.randint(101, 999)) / 10
        if b <= a:
            return None
        ans = b - a
        return ("x + %s = %s，x 等于多少？" % (dec_str(a), dec_str(b)), dec_str(ans), dec_wrongs(ans, Decimal("0.1")))
    if k == 10:
        salt, water, pct = rng.choice([(10, 90, 10), (20, 80, 20), (25, 75, 25), (40, 60, 40), (5, 95, 5), (50, 50, 50)])
        return ("%d 克盐溶解在 %d 克水中，盐水的含盐率是多少？" % (salt, water), "%d%%" % pct, ["%d%%" % (100 - pct), "%d%%" % (pct + 10), "%d%%" % max(1, pct - 10)])
    if k == 11:
        v1, v2, t = rng.randint(40, 80), rng.randint(40, 80), rng.randint(2, 9)
        dist = (v1 + v2) * t
        return ("两地相距 %d 米，两人同时从两地相向而行，速度分别是每分钟 %d 米和 %d 米。几分钟后相遇？" % (dist, v1, v2), str(t), int_wrongs(t))
    a = rng.randint(2, 20)
    ans = 1000 * a
    return ("%d 立方分米 = 多少立方厘米？" % a, "%d 立方厘米" % ans, ["%d 立方厘米" % (ans * 10), "%d 立方厘米" % (ans // 10), "%d 立方厘米" % (ans + 100)])


def wp_g6():
    k = rng.randint(1, 12)
    if k == 1:
        n = rng.choice([80, 120, 200, 240, 300, 400, 500])
        p = rng.choice([5, 10, 15, 20, 25, 40, 50, 75])
        # 同 make_questions.py：避免 //100 截断产生错误答案
        from math import gcd
        step = 100 // gcd(p, 100)
        n -= n % step
        ans = n * p // 100
        return ("%d 的 %d%% 是多少？" % (n, p), str(ans), int_wrongs(ans, (1, -1, 10, -10)))
    if k == 2:
        price = rng.choice([40, 60, 80, 100, 120, 200, 300])
        disc = rng.choice([5, 6, 7, 8, 9])
        ans = price * disc // 10
        return ("一件商品原价 %d 元，打 %d 折出售，现价是多少元？" % (price, disc), str(ans), int_wrongs(ans, (10, -10, 5, -5)))
    if k == 3:
        cost = rng.choice([40, 50, 80, 100, 200])
        pct = rng.choice([10, 20, 25, 50])
        if (cost * pct) % 100 != 0:
            return None
        sell = cost + cost * pct // 100
        return ("一件商品进价 %d 元，售价 %d 元，利润率是多少？" % (cost, sell), "%d%%" % pct, ["%d%%" % (pct + 5), "%d%%" % max(1, pct - 5), "%d%%" % (100 - pct)])
    if k == 4:
        r = rng.randint(2, 10)
        ans = Decimal("3.14") * 2 * r
        return ("一个圆的半径是 %d 厘米，它的周长是多少厘米？（π 取 3.14）" % r, dec_str(ans), dec_wrongs(ans, Decimal("6.28")))
    if k == 5:
        r = rng.randint(2, 10)
        ans = Decimal("3.14") * r * r
        return ("一个圆的半径是 %d 厘米，它的面积是多少平方厘米？（π 取 3.14）" % r, dec_str(ans), dec_wrongs(ans, Decimal("3.14")))
    if k == 6:
        s, h = rng.randint(10, 60), rng.randint(2, 15)
        ans = s * h
        return ("一个圆柱的底面积是 %d 平方厘米，高是 %d 厘米，体积是多少立方厘米？" % (s, h), str(ans), int_wrongs(ans))
    if k == 7:
        s = rng.randint(10, 60)
        h = rng.randint(2, 15) * 3
        ans = s * h // 3
        return ("一个圆锥的底面积是 %d 平方厘米，高是 %d 厘米，体积是多少立方厘米？" % (s, h), str(ans), int_wrongs(ans))
    if k == 8:
        # a : b = c : x  =>  a/b = c/x  =>  x = b*c/a，必须整除
        while True:
            a, b, c = rng.randint(2, 9), rng.randint(2, 9), rng.randint(2, 9)
            if (b * c) % a == 0:
                break
        ans = b * c // a
        return ("%d : %d = %d : x，x 等于多少？" % (a, b, c), str(ans), int_wrongs(ans, (a, -a, c, -c)))
    if k == 9:
        # 图上 a 厘米表示实际 b 千米：比例尺 = a : b*100000，需整除，取 a 为 100000 的因数
        a = rng.choice([1, 2, 4, 5, 8, 10, 20, 25, 40, 50])
        b = rng.randint(1, 20)
        ans = b * 100000 // a
        wrongs = ["%d:1" % ans, "1:%d" % (ans * 10), "1:%d" % (ans // 10)]
        uniq = []
        for w in wrongs:
            if w != "1:%d" % ans and w not in uniq:
                uniq.append(w)
        if len(uniq) < 3:
            return None
        return ("图上距离 %d 厘米表示实际距离 %d 千米，这幅图的比例尺是多少？" % (a, b), "1:%d" % ans, uniq[:3])
    if k == 10:
        p = rng.choice([1000, 2000, 5000, 10000])
        r = rng.choice([2, 3, 4, 5])
        t = rng.randint(2, 5)
        ans = p * r // 100 * t
        return ("本金 %d 元，年利率 %d%%，存 %d 年，利息是多少元？" % (p, r, t), str(ans), int_wrongs(ans, (10, -10, 100, -100)))
    if k == 11:
        a, b = rng.randint(1, 8), rng.randint(2, 9)
        while a >= b:
            a, b = rng.randint(1, 8), rng.randint(2, 9)
        c, d = rng.randint(1, 8), rng.randint(2, 9)
        while c >= d:
            c, d = rng.randint(1, 8), rng.randint(2, 9)
        ans = Fraction(a, b) / Fraction(c, d)
        return ("%d/%d ÷ %d/%d = ?" % (a, b, c, d), frac_str(ans), frac_wrongs(ans))
    a, b = rng.choice([(4, 12), (12, 4), (6, 3), (3, 6), (10, 15), (15, 10), (8, 8)])
    ans = int(Fraction(1, 1) / (Fraction(1, a) + Fraction(1, b)))
    return ("一项工程，甲单独做要 %d 天完成，乙单独做要 %d 天完成。两人合作，几天可以完成？" % (a, b), "%d 天" % ans, ["%d 天" % (ans + 1), "%d 天" % (a + b), "%d 天" % (ans + 2)])


WP = {1: wp_g1, 2: wp_g2, 3: wp_g3, 4: wp_g4, 5: wp_g5, 6: wp_g6}


def math_word_problems(grade, need):
    out = []
    guard = 0
    fn = WP[grade]
    while len(out) < need and guard < need * 80:
        guard += 1
        res = fn()
        if not res:
            continue
        q, ans, wrongs = res
        if len(wrongs) < 3:
            continue
        add_math(out, grade, q, ans, wrongs[:3])
    return out


def math_questions(grade, need):
    parts = []
    parts += math_compute(grade, 570)
    parts += math_units(grade, 70)
    parts += math_defs(grade, 30)
    parts += math_word_problems(grade, 300)
    if len(parts) < need:
        parts += math_compute(grade, need - len(parts) + 10)
    return parts


# ---------------------------------------------------------------- 英语
PEP_BY_GRADE = {3: [], 4: [], 5: [], 6: []}
for w in PEP:
    g = int(w.get("grade") or 0)
    if g in PEP_BY_GRADE:
        PEP_BY_GRADE[g].append(w)

for g in PEP_BY_GRADE:
    seen = set()
    uniq = []
    for w in PEP_BY_GRADE[g]:
        key = w["en"].lower()
        if key in seen:
            continue
        seen.add(key)
        uniq.append(w)
    PEP_BY_GRADE[g] = uniq

WORD_LIST = {
    1: PEP_BY_GRADE[3],
    2: PEP_BY_GRADE[3],
    3: PEP_BY_GRADE[3],
    4: PEP_BY_GRADE[4],
    5: PEP_BY_GRADE[5],
    6: PEP_BY_GRADE[6],
}

TPL_EN2CN = [
    "单词「%s」的意思是？",
    "「%s」这个单词是什么意思？",
    "单词「%s」的中文意思是？",
    "「%s」的中文意思是什么？",
    "单词「%s」表示什么意思？",
    "「%s」这个单词的中文意思是？",
]
TPL_CN2EN = [
    "「%s」用英语怎么说？",
    "「%s」的英文是什么？",
    "下面哪个单词表示「%s」？",
    "「%s」对应的英语单词是？",
    "「%s」用英文怎么表达？",
    "哪个英文单词的意思是「%s」？",
]
TPL_EX2CN = [
    "句子「%s」的中文意思是？",
    "「%s」这句话是什么意思？",
    "下面哪句中文是「%s」的意思？",
    "「%s」翻译成中文是？",
    "句子「%s」应该翻译成哪句中文？",
    "「%s」这句话的中文意思是？",
]
TPL_CN2EX = [
    "「%s」用英语怎么说？",
    "「%s」对应的英文句子是？",
    "下面哪句英文的意思是「%s」？",
    "「%s」翻译成英语是？",
    "「%s」应该翻译成哪句英文？",
    "哪句英文表达的是「%s」？",
]
TPL_SPELL = [
    "单词「%s」的正确拼写是？",
    "「%s」的正确拼写是哪一项？",
    "下面哪个是「%s」的正确拼写？",
    "「%s」这个单词应该怎么拼写？",
    "选出「%s」的正确拼写。",
    "「%s」的正确写法是？",
]
TPL_FIRST = [
    "单词「%s」的第一个字母是？",
    "「%s」这个单词以哪个字母开头？",
    "单词「%s」的首字母是？",
    "「%s」的第一个字母是什么？",
    "单词「%s」的开头字母是？",
    "「%s」以哪个字母开头？",
]
TPL_COUNT = [
    "单词「%s」由几个字母组成？",
    "「%s」这个单词一共有几个字母？",
    "单词「%s」的字母个数是？",
    "「%s」有几个字母？",
    "单词「%s」一共有几个字母？",
    "「%s」这个单词由几个字母组成？",
]


def senses(cn):
    return {s.strip() for s in re.split(r"[；;]", cn or "") if s.strip()}


def senses_overlap(a, b):
    return bool(senses(a) & senses(b))


def mutate_word(word):
    """生成拼写干扰项：交换相邻字母 / 删除字母 / 重复字母。"""
    w = word.lower()
    out = []
    for i in range(len(w) - 1):
        if w[i] != w[i + 1]:
            out.append(w[:i] + w[i + 1] + w[i] + w[i + 2 :])
    for i in range(len(w)):
        if len(w) > 2:
            out.append(w[:i] + w[i + 1 :])
    for i in range(len(w)):
        out.append(w[:i] + w[i] + w[i:])
    seen = []
    for x in out:
        if x != w and x not in seen:
            seen.append(x)
    return seen


ALL_EN = {w["en"].lower() for wl in PEP_BY_GRADE.values() for w in wl}


def english_questions(grade, need):
    out = []
    words = list(WORD_LIST[grade])
    rng.shuffle(words)
    v = grade - 1

    used_cn = set()
    used_cn_spell = set()
    used_ex = set()
    used_excn = set()

    # A. 英 -> 中
    for w in words:
        if len(out) >= need:
            break
        ans = w["cn"]
        cands = [
            x["cn"]
            for x in words
            if x["en"] != w["en"] and not senses_overlap(ans, x["cn"])
        ]
        if len(cands) < 3:
            continue
        add(out, SUB_EN, grade, TPL_EN2CN[v] % w["en"], ans, rng.sample(cands, 3))

    # B. 中 -> 英
    for w in words:
        if len(out) >= need:
            break
        if w["cn"] in used_cn:
            continue
        ans = w["en"]
        cands = [
            x["en"]
            for x in words
            if x["en"] != ans and not senses_overlap(w["cn"], x["cn"])
        ]
        if len(cands) < 3:
            continue
        if add(out, SUB_EN, grade, TPL_CN2EN[v] % w["cn"], ans, rng.sample(cands, 3)):
            used_cn.add(w["cn"])

    # C. 例句 -> 中文
    for w in words:
        if len(out) >= need:
            break
        if not w["ex"] or not w["exCn"]:
            continue
        if w["exCn"] in used_excn:
            continue
        ans = w["exCn"]
        cands = [
            x["exCn"]
            for x in words
            if x["exCn"]
            and x["exCn"] != ans
            and not similar_meaning(ans, x["exCn"])
        ]
        if len(cands) < 3:
            continue
        if add(out, SUB_EN, grade, TPL_EX2CN[v] % w["ex"], ans, rng.sample(cands, 3)):
            used_excn.add(ans)

    # D. 中文 -> 例句
    for w in words:
        if len(out) >= need:
            break
        if not w["ex"] or not w["exCn"]:
            continue
        if w["ex"] in used_ex:
            continue
        ans = w["ex"]
        cands = [x["ex"] for x in words if x["ex"] and x["ex"] != ans]
        if len(cands) < 3:
            continue
        if add(out, SUB_EN, grade, TPL_CN2EX[v] % w["exCn"], ans, rng.sample(cands, 3)):
            used_ex.add(ans)

    # E. 拼写（只对单个纯字母单词出题，避免词组和撇号）
    for w in words:
        if len(out) >= need:
            break
        if w["cn"] in used_cn_spell:
            continue
        if not re.fullmatch(r"[A-Za-z]+", w["en"]):
            continue
        muts = [m for m in mutate_word(w["en"]) if m not in ALL_EN]
        if len(muts) < 3:
            continue
        if add(out, SUB_EN, grade, TPL_SPELL[v] % w["cn"], w["en"], rng.sample(muts, 3)):
            used_cn_spell.add(w["cn"])

    # F. 首字母
    for w in words:
        if len(out) >= need:
            break
        en = w["en"]
        first = en[0].lower()
        letters = [c for c in "abcdefghijklmnopqrstuvwxyz" if c != first]
        add(out, SUB_EN, grade, TPL_FIRST[v] % en, first, rng.sample(letters, 3))

    # G. 字母个数（兜底）
    for w in words:
        if len(out) >= need:
            break
        n = len(w["en"])
        wrongs = []
        for d in (1, -1, 2, -2, 3, -3):
            v2 = n + d
            if v2 > 0 and v2 != n and str(v2) not in wrongs:
                wrongs.append(str(v2))
            if len(wrongs) == 3:
                break
        if len(wrongs) < 3:
            continue
        add(out, SUB_EN, grade, TPL_COUNT[v] % w["en"], str(n), wrongs)

    return out


# ---------------------------------------------------------------- 主流程
def main():
    base = make_questions.build()
    base_questions = base["questions"]

    groups = {}
    for q in base_questions:
        key = (q["grade"], q["subject"])
        groups.setdefault(key, []).append(q)

    for subject in (SUB_CN, SUB_MA, SUB_EN):
        for q in base_questions:
            if q["subject"] == subject:
                USED[subject].add(q["q"])

    builders = {
        SUB_CN: lambda g, n: chinese_questions(g, n),
        SUB_MA: lambda g, n: math_questions(g, n),
        SUB_EN: lambda g, n: english_questions(g, n),
    }

    all_questions = []
    for g in range(1, 7):
        for subject in (SUB_CN, SUB_MA, SUB_EN):
            existing = groups.get((g, subject), [])
            need = TARGET - len(existing)
            generated = builders[subject](g, need)
            # 保证正好补齐
            if len(generated) < need:
                raise SystemExit(
                    "%d 年级 %s 生成不足：需要 %d，实际 %d" % (g, subject, need, len(generated))
                )
            generated = generated[:need]

            rows = list(existing)
            for q in generated:
                rows.append(q)

            subkey = {SUB_CN: "chinese", SUB_MA: "math", SUB_EN: "english"}[subject]
            for i, q in enumerate(rows):
                if "id" not in q or not q["id"]:
                    q["id"] = "g%d-%s-%03d" % (g, subkey, i + 1)
                q["subject"] = subject
                q["grade"] = g
                q["answer"] = 0
            all_questions.extend(rows)

    # ---------------- 严格校验
    assert len(all_questions) == 18000, "总题数应为 18000，实际 %d" % len(all_questions)

    ids = set()
    per_gs = {}
    stem_seen = {}
    for q in all_questions:
        assert q["id"] not in ids, "id 重复：" + q["id"]
        ids.add(q["id"])
        assert q["grade"] in range(1, 7), q
        assert q["subject"] in (SUB_CN, SUB_MA, SUB_EN), q
        assert isinstance(q["q"], str) and q["q"].strip(), q
        assert len(q["options"]) == 4, q
        assert all(isinstance(o, str) and o.strip() for o in q["options"]), q
        assert len(set(q["options"])) == 4, "选项重复：" + q["q"] + " " + str(q["options"])
        assert q["answer"] == 0, q
        key = (q["grade"], q["subject"])
        per_gs[key] = per_gs.get(key, 0) + 1
        skey = (q["subject"], q["q"])
        assert skey not in stem_seen, "题干重复：" + q["q"]
        stem_seen[skey] = q["id"]

    for g in range(1, 7):
        for subject in (SUB_CN, SUB_MA, SUB_EN):
            n = per_gs.get((g, subject), 0)
            assert n == TARGET, "%d 年级 %s 应为 %d 题，实际 %d" % (g, subject, TARGET, n)

    doc = {
        "version": VERSION,
        "name": "小学 1-6 年级语数英题库",
        "note": "grade 为年级（1-6）。answer 是正确选项下标（从 0 开始），程序每次出题会自动打乱选项顺序。共 18000 题：1-6 年级每个年级语文/数学/英语各 1000 题。题型覆盖：语文（拼音、声调、笔画、部首、同音字、多音字、成语释义、反义词、近义词）、数学（计算、公式、定义、单位换算、奥数应用题）、英语（词义、例句翻译、拼写、首字母）。所有答案均由公开数据或程序计算得出。",
        "questions": all_questions,
    }

    text = json.dumps(doc, ensure_ascii=False, indent=2)
    io.open(OUT_PATH, "w", encoding="utf-8", newline="\n").write(text + "\n")

    from collections import Counter

    print("总题数:", len(all_questions))
    print("按年级:", dict(sorted(Counter(q["grade"] for q in all_questions).items())))
    print("按学科:", dict(Counter(q["subject"] for q in all_questions)))
    print("每科每年级:", dict(sorted(per_gs.items())))
    print("文件大小:", os.path.getsize(OUT_PATH))
    print("写入:", OUT_PATH)


if __name__ == "__main__":
    main()
