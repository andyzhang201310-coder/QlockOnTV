# -*- coding: utf-8 -*-
"""带登录态完整抓取牛题库章节题（题干 + 选项 + 答案 + 解析），并转成选择题/判断题。

Cookie 只从命令行 --cookie 或环境变量 TIKU_COOKIE 读取，绝不写入仓库文件。

运行：
    python tools/tiku_full_fetch.py --pages 5
    TIKU_COOKIE='PHPSESSID=...' python tools/tiku_full_fetch.py --pages 8

产物：tools/data/tiku_raw/full_fetched.json（题目本体）、full_raw.json（含解析原文）
转换规则：
  · 有 A-D 选项且答案是字母 → 直接收为选择题；
  · 无选项 / 答案是短文本（填空题、简答题）→ 用同知识点其它题的答案做干扰项，
    改成「横线处应填什么」的 4 选 1，并额外出一道判断题；
  · 长篇范文、含图片、答案过长 → 跳过（不适合电视端选择题）。
"""
import argparse
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tiku_fetch import TARGETS, BASE, UA, CARD, STEM, OPT, ANS, clean, opener  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data", "tiku_raw")
ANALYSIS = "https://www.tiku.cn/webapi/question/analysis"
QID = re.compile(r"show_anaylis\(\$\(this\),\s*(\d+)\s*\)")
TAG = re.compile(r"<[^>]+>")

TYPE_MAP = {
    "形近字辨析": "形近字辨析",
    "改正错别字": "错别字辨析",
    "词义字义辨析": "词义字义辨析",
    "成语": "词语解释",
    "歇后语谚语": "歇后语谚语",
    "文言文阅读": "文言字词",
    "诗词鉴赏": "诗词鉴赏",
    "文史常识": "文学常识",
}


class Fetcher:
    def __init__(self, cookie):
        self.op = opener()
        self.headers = dict(UA)
        self.headers["Cookie"] = cookie
        self.headers["X-Requested-With"] = "XMLHttpRequest"
        self.headers["Referer"] = "https://www.tiku.cn/chapterq?cid=21&cno=1"

    def page(self, params, p):
        qp = dict(params)
        qp["p"] = p
        url = BASE + "?" + urllib.parse.urlencode(qp)
        for i in range(3):
            try:
                return self.op.open(urllib.request.Request(url, headers=UA), timeout=15).read().decode("utf-8", "ignore")
            except Exception:
                if i == 2:
                    return ""
                time.sleep(0.6 * (i + 1))
        return ""

    def answer(self, qid):
        data = urllib.parse.urlencode({"id": qid}).encode()
        for i in range(2):
            try:
                r = self.op.open(urllib.request.Request(ANALYSIS, data=data, headers=self.headers), timeout=15)
                j = json.loads(r.read().decode("utf-8", "ignore"))
                if j.get("status") == "success":
                    d = j.get("data") or {}
                    return self.clean_html(d.get("AnswerHtml") or ""), self.clean_html(d.get("AnylysisHtml") or "")
                return "", ""
            except Exception:
                if i == 1:
                    return "", ""
                time.sleep(0.4)
        return "", ""

    @staticmethod
    def clean_html(s):
        s = html.unescape(str(s))
        s = TAG.sub("", s)
        return re.sub(r"\s+", " ", s).strip()


def parse_page(page, subject, kp, typ):
    out = []
    for c in CARD.findall(page):
        m = STEM.search(c)
        qid = QID.search(c)
        if not m or not qid:
            continue
        stem = clean(m.group(1))
        if not stem or len(stem) > 500:
            continue
        opts = [(k, clean(v)) for k, v in OPT.findall(c)]
        a = ANS.search(c)
        out.append({
            "qid": qid.group(1), "subject": subject, "kp": kp, "typ": typ,
            "q": stem, "options": opts,
            "answer": Fetcher.clean_html(a.group(1)) if a else "",
        })
    return out


BAD = ("。", "，", "<", "frac", "——", "(", "（", "1．", "1.", "①", "、", "\n", "?", "？")


def usable(a, max_len=16):
    """适合当电视选项的短答案：不能有句子、编号、括号、多空串。"""
    if not a or len(a) > max_len or len(a) < 1:
        return False
    if any(x in a for x in BAD):
        return False
    if len(a) == 1 and a.upper() in "ABCDEF":
        return False  # 那是别的题的选项字母，不是知识点答案
    return True


def convert(raw):
    """收紧后的转换：宁缺毋滥，只保留电视端读得清、答案唯一的题。"""
    short_answers = {}
    converted = []
    for it in raw:
        a = (it.get("answer") or "").strip()
        if usable(a):
            short_answers.setdefault((it["subject"], it["kp"]), [])
            if a not in short_answers[(it["subject"], it["kp"])]:
                short_answers[(it["subject"], it["kp"])].append(a)
    for it in raw:
        subject, kp, typ = it["subject"], it["kp"], it["typ"]
        stem = it["q"].strip()
        letters = [k for k, _ in it["options"]]
        texts = [v for _, v in it["options"]]
        ans = (it.get("answer") or "").strip()
        # A. 原生单选题：选项别太长
        if (len(texts) >= 3 and len(set(texts)) == len(texts) and ans in letters
                and len(stem) <= 160 and all(2 <= len(x) <= 60 for x in texts)):
            converted.append({
                "subject": subject, "kp": kp, "typ": typ, "q": stem,
                "options": texts, "answer": letters.index(ans), "origin": "单选题",
            })
            continue
        # B. 短答案的填空题 → 4 选 1 + 判断题
        if not usable(ans) or len(stem) > 100 or "\n" in stem:
            continue
        pool = [a for a in short_answers.get((subject, kp), []) if a != ans and usable(a)]
        if len(pool) < 3:
            continue
        wrongs = pool[:3]
        converted.append({
            "subject": subject, "kp": kp, "typ": typ or "填空改编",
            "q": stem + " 横线处应填什么？",
            "options": [ans] + wrongs, "answer": 0, "origin": "填空改编",
        })
        import random as _r
        if _r.random() < 0.5:
            jq, ja = "「%s」这道题的答案是「%s」。" % (stem, ans), 0
        else:
            jq, ja = "「%s」这道题的答案是「%s」。" % (stem, wrongs[0]), 1
        converted.append({
            "subject": subject, "kp": kp, "typ": typ or "填空改编",
            "q": jq, "options": ["说法正确", "说法错误"], "answer": ja,
            "origin": "判断题",
        })
    return converted


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=5, help="每个知识点抓几页（每页 10 题）")
    ap.add_argument("--cookie", default=os.environ.get("TIKU_COOKIE", ""))
    ap.add_argument("--from-raw", action="store_true",
                    help="不联网，直接用已抓的 full_raw.json 重新转换（调整过滤规则时用）")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    if args.from_raw:
        raw = json.load(open(os.path.join(OUT, "full_raw.json"), encoding="utf-8"))
        print("从 full_raw.json 重新转换：%d 道原始题" % len(raw))
    else:
        if not args.cookie:
            print("缺少 Cookie：用 --cookie 或环境变量 TIKU_COOKIE 传入")
            return 2
        f = Fetcher(args.cookie)
        raw = []
        for subject, name, params, _ in TARGETS:
            typ = TYPE_MAP.get(name, "")
            got = 0
            for p in range(1, args.pages + 1):
                page = f.page(params, p)
                if not page:
                    continue
                for item in parse_page(page, subject, name, typ):
                    ans, analysis = f.answer(item["qid"])
                    item["answer"] = item["answer"] or ans
                    item["analysis"] = analysis
                    raw.append(item)
                    got += 1
                    time.sleep(0.12)
                time.sleep(0.2)
            print("%-4s %-14s 抓到 %3d 题" % (subject, name, got), flush=True)
        json.dump(raw, open(os.path.join(OUT, "full_raw.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)

    converted = convert(raw)
    json.dump(converted, open(os.path.join(OUT, "full_fetched.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    from collections import Counter
    print("原始题 %d 道 → 可入库 %d 道 %s" %
          (len(raw), len(converted), dict(Counter(x["origin"] for x in converted))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
