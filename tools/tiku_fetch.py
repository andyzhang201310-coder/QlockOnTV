# -*- coding: utf-8 -*-
"""抓取牛题库(tiku.cn)章节页的知识点与题面语料。

用途：为题库扩充提供「真实考点 + 真实问法」参考。站点把答案藏在需登录的
/webapi/question/analysis 接口里，所以这里只存题面，不猜测答案；带答案的
公开选择题由 parse 顺带记下来，交给 bank_expand_v7.py 转成本题库格式。

运行：python tools/tiku_fetch.py
产物：tools/data/tiku_raw/knowledge.json、corpus.json、fetched.json
"""
import html
import json
import os
import re
import time
import urllib.parse
import urllib.request

BASE = "https://www.tiku.cn/chapterq"
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"}
OUT = os.path.join(os.path.dirname(__file__), "data", "tiku_raw")

# (科目, 链接名, 额外查询参数, 抓取页数)
TARGETS = [
    ("语文", "形近字辨析", {"cid": 21, "cno": 1, "thirdknowid": 707298}, 3),
    ("语文", "改正错别字", {"cid": 21, "cno": 1, "thirdknowid": 707302}, 3),
    ("语文", "词义字义辨析", {"cid": 21, "cno": 1, "thirdknowid": 707333}, 3),
    ("语文", "成语", {"cid": 21, "cno": 1, "thirdknowid": 707347}, 2),
    ("语文", "歇后语谚语", {"cid": 21, "cno": 1, "thirdknowid": 707357}, 3),
    ("语文", "文言文阅读", {"cid": 21, "cno": 1, "thirdknowid": 707460}, 3),
    ("语文", "诗词鉴赏", {"cid": 21, "cno": 1, "thirdknowid": 707591}, 3),
    ("语文", "文史常识", {"cid": 21, "cno": 1, "thirdknowid": 707597}, 3),
    ("数学", "人教三年级上册", {"cid": 14, "cno": 1, "vid": 800016, "bid": 800104}, 4),
    ("英语", "小学英语基础知识", {"cid": 23, "cno": 1}, 4),
]

CARD = re.compile(
    r'<div class="card mb-3 q-detail rounded-0">(.*?)'
    r'(?=<div class="card mb-3 q-detail rounded-0">|<div class="pagelist")', re.S)
STEM = re.compile(r'<section class="card-text mb-3">(.*?)</section>', re.S)
OPT = re.compile(r'<div class="col-xl-6">([A-D])、(.+?)</div>', re.S)
ANS = re.compile(r'答案:\s*([A-D])')
TAG = re.compile(r'<[^>]+>')


def clean(s):
    s = TAG.sub("", s)
    s = html.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


def opener():
    # 系统代理会把内网/直连请求带偏，这里强制直连
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fetch(url, op, retry=2):
    for i in range(retry + 1):
        try:
            return op.open(urllib.request.Request(url, headers=UA), timeout=15).read().decode("utf-8", "ignore")
        except Exception:
            if i == retry:
                return ""
            time.sleep(0.5 * (i + 1))
    return ""


def parse_cards(page):
    out = []
    for c in CARD.findall(page):
        m = STEM.search(c)
        if not m:
            continue
        stem = clean(m.group(1))
        if not stem or len(stem) > 600:
            continue
        opts = [(k, clean(v)) for k, v in OPT.findall(c)]
        a = ANS.search(c)
        out.append({"stem": stem, "options": opts, "answer": a.group(1) if a else ""})
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    op = opener()
    corpus, fetched, knowledge = [], [], {}
    for subject, name, params, pages in TARGETS:
        items = []
        node_names = []
        for p in range(1, pages + 1):
            qp = dict(params)
            qp["p"] = p
            url = BASE + "?" + urllib.parse.urlencode(qp)
            page = fetch(url, op)
            if not page:
                print("  ! 抓取失败", name, "p=", p)
                continue
            if p == 1:
                node_names = [clean(x) for x in re.findall(
                    r"tourl\(\s*\d+\s*,'thirdknowid'\)\"[^>]*>\s*(.*?)\s*</a>", page, re.S)]
            for card in parse_cards(page):
                corpus.append({"subject": subject, "kp": name, "stem": card["stem"]})
                if card["answer"] and len(card["options"]) >= 3:
                    letters = [k for k, _ in card["options"]]
                    if card["answer"] in letters:
                        fetched.append({
                            "subject": subject, "kp": name,
                            "q": card["stem"],
                            "options": [v for _, v in card["options"]],
                            "answer": letters.index(card["answer"]),
                        })
                items.append(card["stem"])
            time.sleep(0.25)
        knowledge[name] = {"subject": subject, "params": params, "nodes": node_names[:80]}
        print("%-4s %-14s 抓到 %3d 条题面" % (subject, name, len(items)))

    # 去重
    seen, uniq = set(), []
    for x in corpus:
        if x["stem"] not in seen:
            seen.add(x["stem"])
            uniq.append(x)
    json.dump(knowledge, open(os.path.join(OUT, "knowledge.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    json.dump(uniq, open(os.path.join(OUT, "corpus.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    json.dump(fetched, open(os.path.join(OUT, "fetched.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("知识点 %d 组 / 题面语料 %d 条 / 公开带答案选择题 %d 条" %
          (len(knowledge), len(uniq), len(fetched)))


if __name__ == "__main__":
    main()
