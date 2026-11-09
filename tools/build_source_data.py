# -*- coding: utf-8 -*-
"""把公开数据源整理成题库生成器直接可用的紧凑数据表。

数据源（均为公开仓库）：
- 识字表：https://github.com/whitneyli/hanzi-game  (sets/grade*.json)
- 汉字笔画/部首：https://github.com/pwxcoo/chinese-xinhua  (data/word.json)
- 成语释义：https://github.com/pwxcoo/chinese-xinhua  (data/idiom.json)
- 成语词频：https://github.com/fighting41love/funNLP  (data/成语词库/THUOCL_chengyu.txt)
- 反义词：https://github.com/fighting41love/funNLP  (data/同义词库、反义词库、否定词库/反义词库.txt)
- PEP 小学英语词汇：https://github.com/DennisGuo/ps-english  (public/data/words/vocabulary.json)

运行前先把上述原始文件放到 BANK_SOURCE_DIR（默认 %TEMP%/opencode/data 与
%TEMP%/opencode/hanzi-game/sets），然后执行：
    python tools/build_source_data.py
输出写入 tools/data/*.json，生成器不再依赖网络。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "data")
SRC = os.environ.get("BANK_SOURCE_DIR", os.path.join(os.environ.get("TEMP", ""), "opencode", "data"))
HANZI_SRC = os.environ.get("BANK_HANZI_DIR", os.path.join(os.environ.get("TEMP", ""), "opencode", "hanzi-game", "sets"))
PEP_SRC = os.environ.get("BANK_PEP_FILE", os.path.join(os.environ.get("TEMP", ""), "opencode", "ps-english", "public", "data", "words", "vocabulary.json"))


def read_json(path):
    with io.open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_json(name, obj):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote", path, os.path.getsize(path))


def clean_idiom(text, idiom):
    """去掉“逐字释义”式的开头（源数据把冒号吞掉了），只保留整体释义。"""
    t = re.sub(r"\s+", " ", (text or "").strip())
    t = re.sub(r"^[0-9]+[.、]\s*", "", t)
    pieces = []
    for s in re.split(r"[。；]", t):
        s = s.strip(" ，,、;")
        if not s:
            continue
        if "◆" in s or "◇" in s:
            continue
        s = re.sub(r"[①②③④⑤⑥⑦⑧⑨⑩]", "", s)
        s = s.replace('"', "").replace("“", "").replace("”", "")
        if not s:
            continue
        pieces.append(s)
    out = []
    for s in pieces:
        if len(s) <= 10 and s[0] in idiom and len(pieces) > 1:
            continue
        out.append(s)
    if not out:
        return ""
    return "，".join(out)


def build_idioms():
    freq = {}
    path = os.path.join(SRC, "THUOCL_chengyu.txt")
    for line in io.open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) == 2 and parts[0]:
            try:
                freq[parts[0]] = int(parts[1])
            except ValueError:
                pass
    by_word = {}
    for it in read_json(os.path.join(SRC, "idiom.json")):
        w = (it.get("word") or "").strip()
        if len(w) == 4 and re.fullmatch(r"[\u4e00-\u9fff]{4}", w):
            by_word[w] = it
    rows = []
    for w, f in sorted(freq.items(), key=lambda kv: -kv[1]):
        it = by_word.get(w)
        if not it:
            continue
        meaning = clean_idiom(it.get("explanation"), w)
        if not (6 <= len(meaning) <= 46):
            continue
        if meaning.startswith("见") or "同“" in meaning or '同"' in meaning:
            continue
        if re.search(r"[A-Za-z]{2,}", meaning):
            continue
        rows.append({"w": w, "m": meaning, "p": (it.get("pinyin") or "").strip()})
        if len(rows) >= 3000:
            break
    write_json("idioms.json", rows)


def build_hanzi():
    word = {x["word"]: x for x in read_json(os.path.join(SRC, "word.json"))}
    grades = {}
    seen = set()
    for fn in sorted(os.listdir(HANZI_SRC)):
        if not fn.startswith("grade"):
            continue
        g = int(fn[5])
        d = read_json(os.path.join(HANZI_SRC, fn))
        rows = grades.setdefault(str(g), [])
        for lesson in d.get("lessons", []):
            for w in lesson.get("words", []):
                h = w.get("hanzi", "").strip()
                py = w.get("pinyin", "").strip()
                if not h or not py or h in seen:
                    continue
                meta = word.get(h, {})
                strokes = str(meta.get("strokes", "")).strip()
                radical = str(meta.get("radicals", "")).strip()
                rows.append({
                    "h": h,
                    "p": py,
                    "s": int(strokes) if strokes.isdigit() else 0,
                    "r": radical,
                })
                seen.add(h)
    write_json("hanzi.json", grades)


def build_antonyms():
    common = set()
    for rows in read_json(os.path.join(OUT, "hanzi.json")).values():
        for r in rows:
            common.add(r["h"])
    pairs = []
    seen = set()
    path = os.path.join(SRC, "反义词库.txt")
    for line in io.open(path, encoding="utf-8"):
        line = line.strip()
        if "——" not in line:
            continue
        a, b = [x.strip() for x in line.split("——", 1)]
        if not a or not b or a == b:
            continue
        if not (1 <= len(a) <= 2 and 1 <= len(b) <= 2):
            continue
        if not all(c in common for c in a + b):
            continue
        key = tuple(sorted((a, b)))
        if key in seen:
            continue
        seen.add(key)
        pairs.append([a, b])
    write_json("antonyms.json", pairs)


def build_syn_groups():
    """原始同义词库的分组，仅用于排除“另一个也可能正确”的干扰项。"""
    groups = []
    path = os.path.join(SRC, "同义词库.txt")
    if not os.path.exists(path):
        write_json("syn_groups.json", groups)
        return
    for line in io.open(path, encoding="utf-8"):
        line = line.strip()
        if "=" not in line or "#" in line.split("=", 1)[0]:
            continue
        body = line.split("=", 1)[1]
        words = [w for w in re.split(r"[\s,，、]+", body) if w]
        words = [w for w in words if 1 <= len(w) <= 4]
        if len(words) >= 2:
            groups.append(words)
    write_json("syn_groups.json", groups)


def build_antonyms_raw():
    """原始反义词表（未过滤），用于排除同一词的其他合法反义词。"""
    pairs = []
    seen = set()
    path = os.path.join(SRC, "反义词库.txt")
    if not os.path.exists(path):
        write_json("antonyms_raw.json", pairs)
        return
    for line in io.open(path, encoding="utf-8"):
        line = line.strip()
        if "——" not in line:
            continue
        a, b = [x.strip() for x in line.split("——", 1)]
        if not a or not b or a == b:
            continue
        key = tuple(sorted((a, b)))
        if key in seen:
            continue
        seen.add(key)
        pairs.append([a, b])
    write_json("antonyms_raw.json", pairs)


def build_readings():
    """识字表里每个字出现过的全部读音，用于识别多音字、排除有歧义的拼音题。"""
    readings = {}
    if not os.path.isdir(HANZI_SRC):
        write_json("readings.json", readings)
        return
    for fn in sorted(os.listdir(HANZI_SRC)):
        if not fn.startswith("grade"):
            continue
        d = read_json(os.path.join(HANZI_SRC, fn))
        for lesson in d.get("lessons", []):
            for w in lesson.get("words", []):
                h = (w.get("hanzi") or "").strip()
                py = (w.get("pinyin") or "").strip()
                if not h or not py:
                    continue
                readings.setdefault(h, [])
                if py not in readings[h]:
                    readings[h].append(py)
    write_json("readings.json", readings)


def build_pep():
    d = read_json(PEP_SRC)
    words = d.get("words", d)
    out = []
    for x in words:
        en = (x.get("english") or "").strip()
        cn = (x.get("chinese") or "").strip()
        if not en or not cn:
            continue
        out.append({
            "en": en,
            "cn": cn,
            "pos": (x.get("partOfSpeech") or "").strip(),
            "ex": (x.get("example") or "").strip(),
            "exCn": (x.get("exampleCn") or "").strip(),
            "grade": int(x.get("grade") or 0),
            "topic": (x.get("topic") or "").strip(),
        })
    write_json("pep_vocab.json", out)


if __name__ == "__main__":
    build_hanzi()
    build_idioms()
    build_antonyms()
    build_syn_groups()
    build_antonyms_raw()
    build_readings()
    build_pep()
