#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把数学题库里的 ASCII 分数改成 LaTeX，交给 RichText 渲染成真正的分数。

为什么要做：题库里分数一直写作 `6/8 + 1/8 = ?`，在电视上就是一串斜杠，
和「6 除以 8」看不出区别；RichText 已经能把 `\\frac{6}{8}` 画成上下两行加分数线，
所以这里只做「题目数据 → LaTeX 写法」的转换，渲染逻辑一行不改。

转换范围（只动 subject == 数学，其他学科一个字符都不碰）：
  1) 数字分数  3/4      -> $\\frac{3}{4}$      （644 题，主要收益）
  2) 字母分数  x/2      -> $\\frac{x}{2}$      （1 题，方程题）
  3) 上标字符  πr²      -> π$r^{2}$            （11 题，避开部分电视字体缺 ² 字形）
乘除号 ×÷、比号 :、百分号 %、π 本身就是正常 Unicode 字符，包进 $...$ 只是多一层
解析、屏上观感不变，所以不动它们。

用法：
  python tools/latexify_math.py --check        只统计与抽样，不写文件
  python tools/latexify_math.py --apply        写回 assets/questions.json（version +1）
"""

import argparse
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BANK = os.path.join(ROOT, "app", "src", "main", "assets", "questions.json")

# 数字分数：前后不能紧贴数字、小数点或斜杠，避免把 3.6/1.5、1/2/3 这类切错
RE_FRAC_NUM = re.compile(r"(?<![\d./])(\d{1,4})\s*/\s*(\d{1,4})(?![\d./])")
# 字母分数：x/2、n/3 这类方程里的未知数
RE_FRAC_VAR = re.compile(r"(?<![\w.])([A-Za-z])\s*/\s*(\d{1,4})(?![\d./])")
# 上标字符：只认字母/数字/右括号作底数，后面紧跟 ² 或 ³
RE_SUP = re.compile(r"([A-Za-z0-9)])([²³])")

SUP_NUM = {"²": "2", "³": "3"}


def convert(text):
    """返回（新文本, 分数个数, 上标个数）；已含 $ 的文本原样返回，保证可重复执行"""
    if not text or "$" in text:
        return text, 0, 0
    nf = 0
    ns = 0

    def sub_frac(m):
        return "$\\frac{%s}{%s}$" % (m.group(1), m.group(2))

    out = RE_FRAC_NUM.sub(sub_frac, text)
    nf += len(RE_FRAC_NUM.findall(text))

    def sub_var(m):
        return "$\\frac{%s}{%s}$" % (m.group(1), m.group(2))

    out2 = RE_FRAC_VAR.sub(sub_var, out)
    nf += len(RE_FRAC_VAR.findall(out))
    out = out2

    def sub_sup(m):
        return "$%s^{%s}$" % (m.group(1), SUP_NUM[m.group(2)])

    hit = RE_SUP.findall(out)
    ns += len(hit)
    out = RE_SUP.sub(sub_sup, out)
    return out, nf, ns


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="写回题库文件")
    ap.add_argument("--check", action="store_true", help="只统计，不写文件（默认行为）")
    ap.add_argument("--show", type=int, default=6, help="抽样打印条数")
    args = ap.parse_args()
    apply = args.apply and not args.check

    with io.open(BANK, encoding="utf-8") as f:
        doc = json.load(f)
    qs = doc["questions"]

    touched = 0
    n_frac = 0
    n_sup = 0
    samples = []
    other_touched = 0
    converted = {}
    for q in qs:
        if q.get("subject") != "数学":
            continue
        before = json.dumps([q["q"]] + list(q["options"]), ensure_ascii=False)
        nq, f1, s1 = convert(q["q"])
        opts = []
        f2 = s2 = 0
        for o in q["options"]:
            no, a, b = convert(o)
            opts.append(no)
            f2 += a
            s2 += b
        if nq != q["q"] or opts != list(q["options"]):
            touched += 1
            n_frac += f1 + f2
            n_sup += s1 + s2
            if len(samples) < args.show:
                samples.append((q["id"], before,
                                json.dumps([nq] + opts, ensure_ascii=False)))
            converted[q["id"]] = (nq, opts)
            if apply:
                q["q"] = nq
                q["options"] = opts
    # 防御：确认没有误伤其他学科
    for q in qs:
        if q.get("subject") == "数学":
            continue
        if "$" in q["q"] or any("$" in o for o in q["options"]):
            other_touched += 1

    print("题库总题数：%d" % len(qs))
    print("数学题被改动：%d 题" % touched)
    print("  分数 %d 处，上标 %d 处" % (n_frac, n_sup))
    print("非数学题被波及：%d 题（应为 0）" % other_touched)
    for qid, before, after in samples:
        print("\n  %s\n    原：%s\n    新：%s" % (qid, before, after))

    # 结构自检：所有 $ 必须成对，且不残留裸斜杠分数（检查转换后的文本）
    bad = []
    for q in qs:
        if q.get("subject") != "数学":
            continue
        nq, opts = converted.get(q["id"], (q["q"], list(q["options"])))
        for f in [nq] + list(opts):
            if f.count("$") % 2:
                bad.append((q["id"], "$ 不成对", f))
            if RE_FRAC_NUM.search(f):
                bad.append((q["id"], "残留斜杠分数", f))
    print("\n结构自检：%s" % ("通过" if not bad else "发现 %d 处问题" % len(bad)))
    for b in bad[:10]:
        print("  ", b[0], b[1], b[2][:60])

    if not apply:
        print("\n（未写文件；确认无误后加 --apply）")
        return 0 if not bad and not other_touched else 1
    if bad or other_touched:
        print("\n自检未通过，拒绝写入")
        return 1

    ver = int(doc.get("version") or 0)
    doc["version"] = ver + 1
    doc["count"] = len(qs)
    note = doc.get("note") or ""
    tag = "数学题里的分数已改用 LaTeX（$\\frac{a}{b}$）排版"
    if tag not in note:
        if not note:
            sep = ""
        elif note.rstrip().endswith(("。", "；", ";", "！", "?")):
            sep = ""
        else:
            sep = "；"
        doc["note"] = note.rstrip() + sep + tag
    # 与现有文件同一套紧凑序列化，避免产生无意义的大 diff
    text = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
    tmp = BANK + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, BANK)
    print("\n已写入 %s（version %d → %d，%.1f MB）"
          % (os.path.relpath(BANK, ROOT), ver, doc["version"], len(text.encode("utf-8")) / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
