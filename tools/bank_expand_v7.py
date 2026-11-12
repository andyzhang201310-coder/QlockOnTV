# -*- coding: utf-8 -*-
"""v1.7.23 题库扩充：把牛题库(tiku.cn)的知识点补齐成本 App 的选择题 / 判断题。

运行：
    python tools/bank_expand_v7.py            # 生成并合并进 assets/questions.json
    python tools/bank_expand_v7.py --dry-run  # 只统计，不写文件

设计要点：
  1. 题干统一以「【题型名】」开头，QType 优先读这个标签 →「学习统计」直接出现新题型，
     而题库 JSON 仍是原来的 7 个字段，编辑 / 备份 / 导出 / 恢复都不受影响；
  2. 数学数值题的答案由程序计算，干扰项由答案派生，绝不出错题；
  3. 判断题用 2 个选项（说法正确 / 说法错误），沿用同一 answer 下标契约；
  4. 所有题目按题干去重，ID 为 v7-xxxxx，年级 1-6 均衡，难度 1-5 星。
"""
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bank_v7_data import (  # noqa: E402
    XINGJIN, CUOBIEZI, ZIYI, CIYI, XIEHOUYU, YANYU, WENYAN, SHICI, WENSHI,
    EN_GRAMMAR, EN_DIALOG,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "questions.json")
DIST = os.path.join(ROOT, "dist")
VOCAB = os.path.join(ROOT, "tools", "data", "pep_vocab.json")
FETCHED = os.path.join(ROOT, "tools", "data", "tiku_raw", "fetched.json")

RNG = random.Random(20261112)
CN_NUM = "一二三四五六七八九十"


class Builder:
    def __init__(self):
        self.items = []
        self.seen = set()

    @staticmethod
    def _clean(options):
        return [str(x).strip() for x in options]

    def add(self, subject, grade, typ, stem, correct, wrong, bonus=0):
        """加一道 4 选 1；干扰项不足或重复则丢弃，宁缺毋滥。"""
        right = str(correct).strip()
        pool = [x for x in self._clean(wrong) if x and x != right]
        uniq = []
        for x in pool:
            if x not in uniq:
                uniq.append(x)
        if len(uniq) < 3:
            return False
        picked = RNG.sample(uniq, 3)
        opts = [right] + picked
        RNG.shuffle(opts)
        return self._put(subject, grade, typ, stem, opts, opts.index(right), bonus)

    def judge(self, subject, grade, typ, true_stmt, false_stmt, bonus=0):
        """加一道判断题：随机给正确或错误的说法，选项固定为『说法正确/说法错误』。"""
        if RNG.random() < 0.5:
            stem, ans = true_stmt, 0
        else:
            stem, ans = false_stmt, 1
        return self._put(subject, grade, typ, stem, ["说法正确", "说法错误"], ans, bonus)

    def _put(self, subject, grade, typ, stem, options, answer, bonus):
        stem = "【%s】%s" % (typ, stem.strip())
        options = self._clean(options)
        if not stem or len(options) < 2 or len(set(options)) != len(options):
            return False
        if answer < 0 or answer >= len(options):
            return False
        key = (stem, options[answer])
        if key in self.seen:
            return False
        self.seen.add(key)
        grade = max(1, min(6, int(grade)))
        star = max(1, min(5, grade - 2 + bonus + RNG.choice([0, 0, 1])))
        self.items.append({
            "id": "v7-%05d" % (len(self.items) + 1),
            "subject": subject,
            "grade": grade,
            "q": stem,
            "options": options,
            "answer": answer,
            "difficulty": star,
        })
        return True

    def count(self):
        return len(self.items)


# ---------------------------------------------------------------- 工具
def fmt(x):
    """数值统一显示，避免 3.0 / 3.00 之类的重复选项。"""
    if isinstance(x, float):
        if abs(x - round(x)) < 1e-9:
            return str(int(round(x)))
        return ("%.2f" % x).rstrip("0").rstrip(".")
    return str(x)


def num_wrong(c, unit="", pool=None):
    """由正确答案派生 4+ 个不重复干扰项。"""
    if pool is None:
        pool = [c + 1, c - 1, c + 2, c - 2, c * 2, c // 2 if c > 2 else c + 3,
                c + 10, c - 10, c + 5, c - 5, c * 10, c + 100]
    out = []
    for v in pool:
        if isinstance(v, float) or isinstance(c, float):
            if abs(float(v) - float(c)) < 1e-9:
                continue
        elif v == c:
            continue
        s = fmt(v) + unit
        if s not in out:
            out.append(s)
    return out[:8]


def grade_for(i, lo=1, hi=6):
    return lo + (i % (hi - lo + 1))


# ---------------------------------------------------------------- 语文
def build_chinese(b):
    # 1) 形近字辨析：每个选项 3 个词，只有一组形近字全部用对
    for gi, (group_name, pairs) in enumerate(XINGJIN):
        chars = group_name.split("/")
        words = [w for _, w in pairs]
        for k in range(3):
            right3 = RNG.sample(pairs, min(3, len(pairs)))
            right = "、".join(w for _, w in right3)
            wrongs = []
            for _ in range(6):
                ws = [w for _, w in right3]
                idx = RNG.randrange(len(ws))
                ch, w = right3[idx]
                others = [c for c in chars if c != ch and c in w]
                if others:
                    ws[idx] = w.replace(others[0], ch, 1)
                else:
                    other = RNG.choice([c for c in chars if c != ch])
                    ws[idx] = w.replace(ch, other, 1) if ch in w else other + w
                cand = "、".join(ws)
                # 干扰项必须真的含错别字，且不能与正确组雷同
                if cand != right and any(x not in words for x in ws) and cand not in wrongs:
                    wrongs.append(cand)
            g = grade_for(gi + k, 2, 6)
            b.add("语文", g, "形近字辨析",
                  "下面哪一组词语中的形近字全部使用正确？",
                  right, wrongs, bonus=1)
        # 判断题：同一组里的两个字能否互换
        if len(pairs) >= 2:
            (c1, w1), (c2, w2) = RNG.sample(pairs, 2)
            if c1 != c2:
                b.judge("语文", grade_for(gi, 2, 6), "形近字辨析",
                        "「%s」和「%s」里的「%s」「%s」是形近字，不能随便互换。" % (w1, w2, c1, c2),
                        "「%s」里的「%s」可以直接换成「%s」，意思完全不变。" % (w1, c1, c2), bonus=1)

    # 2) 找出没有错别字的一组
    pairs = [(a, z) for a, z in CUOBIEZI if a != z]
    for i, (good, bad) in enumerate(pairs):
        goods = [x[0] for x in RNG.sample(pairs, min(3, len(pairs)))]
        if good not in goods:
            goods[0] = good
        right = "、".join(goods)
        wrongs = []
        for _ in range(6):
            ws = list(goods)
            j = RNG.randrange(len(ws))
            ws[j] = dict(pairs).get(ws[j], ws[j] + "（错）")
            if ws[j] == goods[j]:
                continue
            cand = "、".join(ws)
            if cand not in wrongs and cand != right:
                wrongs.append(cand)
        b.add("语文", grade_for(i, 2, 6), "错别字辨析",
              "下面哪一组词语中没有错别字？", right, wrongs, bonus=1)
        b.judge("语文", grade_for(i, 2, 6), "错别字辨析",
                "「%s」这个词语里有错别字，正确写法是「%s」。" % (bad, good),
                "「%s」的写法完全正确，没有错别字。" % bad, bonus=1)

    # 3) 字义 / 词义辨析
    meanings = list(dict.fromkeys([m for _, _, m, _ in ZIYI]))
    for i, (sent, ch, right, wrong) in enumerate(ZIYI):
        g = grade_for(i, 2, 6)
        b.add("语文", g, "词义字义辨析",
              "「%s」中「%s」字的意思是？" % (sent, ch), right,
              list(wrong) + RNG.sample(meanings, 4), bonus=1)
        wrong_m = RNG.choice(list(wrong))
        b.judge("语文", g, "词义字义辨析",
                "「%s」中「%s」应解释为「%s」。" % (sent, ch, right),
                "「%s」中「%s」应解释为「%s」。" % (sent, ch, wrong_m), bonus=1)
    for i, (sent, right, wrong) in enumerate(CIYI):
        g = grade_for(i, 1, 6)
        b.add("语文", g, "词义字义辨析",
              "选词填空：%s" % sent, right,
              list(wrong) + [x[1] for x in RNG.sample(CIYI, 5)], bonus=1)

    # 4) 歇后语、谚语
    tails = [t for _, t in XIEHOUYU]
    for i, (head, tail) in enumerate(XIEHOUYU):
        g = grade_for(i, 2, 6)
        b.add("语文", g, "歇后语谚语",
              "歇后语「%s」的后半句是什么？" % head, tail,
              RNG.sample([t for t in tails if t != tail], 6), bonus=1)
        b.judge("语文", g, "歇后语谚语",
                "歇后语「%s——%s」搭配正确。" % (head, tail),
                "歇后语「%s——%s」搭配正确。" % (head, RNG.choice([t for t in tails if t != tail])), bonus=1)
        b.add("语文", g, "歇后语谚语",
              "「%s」是下面哪句歇后语的后半句？" % tail, head,
              RNG.sample([h for h, _ in XIEHOUYU if h != head], 5), bonus=1)
    senses = [s for _, s in YANYU]
    for i, (proverb, sense) in enumerate(YANYU):
        g = grade_for(i, 2, 6)
        b.add("语文", g, "歇后语谚语",
              "谚语「%s」告诉我们什么道理？" % proverb, sense,
              RNG.sample([s for s in senses if s != sense], 6), bonus=1)
        b.judge("语文", g, "歇后语谚语",
                "谚语「%s」的意思是「%s」。" % (proverb, sense),
                "谚语「%s」的意思是「%s」。" % (proverb, RNG.choice([s for s in senses if s != sense])), bonus=1)

    # 5) 文言文字词
    wy_mean = list(dict.fromkeys([m for _, _, m in WENYAN]))
    for i, (src, word, mean) in enumerate(WENYAN):
        g = grade_for(i, 4, 6)
        b.add("语文", g, "文言字词",
              "%s，句中「%s」的意思是？" % (src, word), mean,
              RNG.sample([m for m in wy_mean if m != mean], 6), bonus=2)
        b.judge("语文", g, "文言字词",
                "%s，其中「%s」解释为「%s」。" % (src, word, mean),
                "%s，其中「%s」解释为「%s」。" % (src, word, RNG.choice([m for m in wy_mean if m != mean])), bonus=2)

    # 6) 诗词鉴赏
    poems = list(dict.fromkeys([p for _, p, _, _, _ in SHICI]))
    poem_answers = [x[3] for x in SHICI] + [w for x in SHICI for w in x[4]]
    for i, (line, poem, ask, right, wrong) in enumerate(SHICI):
        g = grade_for(i, 2, 6)
        b.add("语文", g, "诗词鉴赏",
              "「%s」（%s）%s" % (line, poem, ask), right,
              list(wrong) + RNG.sample([a for a in poem_answers if a != right], 4), bonus=2)
        b.add("语文", g, "诗词鉴赏",
              "「%s」出自哪首诗？" % line, poem,
              RNG.sample([p for p in poems if p != poem], 6), bonus=1)
        b.judge("语文", g, "诗词鉴赏",
                "「%s」出自%s。" % (line, poem),
                "「%s」出自%s。" % (line, RNG.choice([p for p in poems if p != poem])), bonus=1)

    # 7) 文学常识 / 文史常识
    for i, (ask, right, wrong) in enumerate(WENSHI):
        g = grade_for(i, 1, 6)
        all_right = list(dict.fromkeys([x[1] for x in WENSHI]))
        b.add("语文", g, "文学常识", ask, right,
              list(wrong) + RNG.sample([x for x in all_right if x != right], 4), bonus=0)
        wrong_ans = RNG.choice(list(wrong))
        b.judge("语文", g, "文学常识",
                "「%s」的正确答案是「%s」。" % (ask, right),
                "「%s」的正确答案是「%s」。" % (ask, wrong_ans), bonus=0)
        # 反向问法：给答案问知识点
        b.add("语文", g, "文学常识",
              "下面哪一项文学常识的说法是正确的？", "%s → %s" % (ask, right),
              ["%s → %s" % (ask, w) for w in wrong], bonus=1)


# ---------------------------------------------------------------- 数学
UNITS = [
    ("千米", "米", 1000), ("米", "分米", 10), ("米", "厘米", 100),
    ("分米", "厘米", 10), ("厘米", "毫米", 10), ("吨", "千克", 1000),
    ("千克", "克", 1000), ("小时", "分钟", 60), ("分钟", "秒", 60),
    ("元", "角", 10), ("元", "分", 100), ("角", "分", 10),
    ("平方米", "平方分米", 100), ("平方分米", "平方厘米", 100),
    ("立方米", "立方分米", 1000), ("升", "毫升", 1000),
    ("周", "天", 7), ("天", "小时", 24), ("年", "月", 12),
]
FRACTIONS = [("0.5", "1/2"), ("0.25", "1/4"), ("0.75", "3/4"),
             ("0.2", "1/5"), ("0.1", "1/10"), ("0.125", "1/8")]


def build_math(b):
    # 计算题：按年级换难度
    for grade in range(1, 7):
        for i in range(90):
            if grade <= 2:
                x = RNG.randint(3, 20)
                y = RNG.randint(1, 20)
                if i % 2:
                    stem, c = "%d + %d = ?" % (x, y), x + y
                else:
                    stem, c = "%d − %d = ?" % (x + y, x), y
            elif grade <= 4:
                x, y = RNG.randint(11, 99), RNG.randint(2, 9)
                if i % 3 == 0:
                    stem, c = "%d × %d = ?" % (x, y), x * y
                elif i % 3 == 1:
                    stem, c = "%d ÷ %d = ?" % (x * y, y), x
                else:
                    z = RNG.randint(1, 50)
                    stem, c = "%d + %d − %d = ?" % (x, y, z), x + y - z
            else:
                x = round(RNG.uniform(1.1, 30), 1)
                y = round(RNG.uniform(1.1, 30), 1)
                if i % 2:
                    stem, c = "%s + %s = ?" % (fmt(x), fmt(y)), round(x + y, 1)
                else:
                    stem, c = "%s − %s = ?" % (fmt(x + y), fmt(y)), x
            b.add("数学", grade, "计算", stem, fmt(c), num_wrong(c), bonus=0)

    # 单位换算
    for grade in range(1, 7):
        for i in range(45):
            src, dst, k = RNG.choice(UNITS)
            n = RNG.randint(2, 20) if k >= 10 else RNG.randint(2, 9)
            c = n * k
            b.add("数学", grade, "单位换算",
                  "%d %s = ? %s" % (n, src, dst), fmt(c), num_wrong(c), bonus=0)
            if i % 3 == 0:
                b.judge("数学", grade, "单位换算",
                        "1 %s = %d %s。" % (src, k, dst),
                        "1 %s = %d %s。" % (src, k * 10, dst), bonus=0)

    # 图形与公式
    for grade in range(2, 7):
        for i in range(45):
            kind = i % 6
            if kind == 0:
                a, w = RNG.randint(3, 20), RNG.randint(2, 15)
                stem, c = "长方形长 %d 厘米、宽 %d 厘米，面积是多少平方厘米？" % (a, w), a * w
            elif kind == 1:
                a, w = RNG.randint(3, 20), RNG.randint(2, 15)
                stem, c = "长方形长 %d 厘米、宽 %d 厘米，周长是多少厘米？" % (a, w), (a + w) * 2
            elif kind == 2:
                a = RNG.randint(2, 20)
                stem, c = "正方形边长 %d 厘米，周长是多少厘米？" % a, a * 4
            elif kind == 3:
                a = RNG.randint(2, 20)
                stem, c = "正方形边长 %d 厘米，面积是多少平方厘米？" % a, a * a
            elif kind == 4:
                a, h = RNG.randint(2, 20), RNG.randint(2, 10) * 2
                stem, c = "三角形底 %d 厘米、高 %d 厘米，面积是多少平方厘米？" % (a, h), a * h // 2
            else:
                a, w, h = RNG.randint(2, 12), RNG.randint(2, 12), RNG.randint(2, 10)
                stem, c = "长方体长 %d、宽 %d、高 %d 厘米，体积是多少立方厘米？" % (a, w, h), a * w * h
            b.add("数学", grade, "图形与公式", stem, fmt(c), num_wrong(c), bonus=1)

    # 应用题
    for grade in range(1, 7):
        for i in range(55):
            kind = i % 7
            a, d = RNG.randint(10, 90), RNG.randint(2, 9)
            if kind == 0:
                stem, c = "小明有 %d 元，买书用去 %d 元，还剩多少元？" % (a, d), a - d
            elif kind == 1:
                stem, c = "每盒 %d 支铅笔，买 %d 盒一共多少支？" % (a, d), a * d
            elif kind == 2:
                stem, c = "%d 个苹果平均分给 %d 个小朋友，每人分到几个？" % (a * d, d), a
            elif kind == 3:
                stem, c = "一辆车每小时行 %d 千米，行驶 %d 小时共行多少千米？" % (a, d), a * d
            elif kind == 4:
                stem, c = "甲数是 %d，乙数比甲数多 %d，乙数是多少？" % (a, d), a + d
            elif kind == 5:
                stem, c = "一本书 %d 页，每天看 %d 页，几天能看完？" % (a * d, d), a
            else:
                stem, c = "班级有 %d 人，其中男生 %d 人，女生有多少人？" % (a, d if a > d else a // 2), \
                          a - (d if a > d else a // 2)
            b.add("数学", grade, "应用题", stem, fmt(c), num_wrong(c), bonus=1)

    # 数与代数
    primes = [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37]
    composites = [4, 6, 8, 9, 10, 12, 14, 15, 16, 18, 20, 21, 22, 24, 25]
    for grade in range(2, 7):
        for i in range(40):
            kind = i % 6
            if kind == 0:
                p = RNG.choice(primes)
                stem, c, w = "下面哪个数是质数？", str(p), [str(x) for x in RNG.sample(composites, 5)]
            elif kind == 1:
                n = RNG.randint(2, 20)
                stem, c, w = "%d 的 3 倍是多少？" % n, fmt(n * 3), num_wrong(n * 3)
            elif kind == 2:
                x, z = RNG.randint(2, 20), RNG.randint(2, 12)
                stem, c, w = "求 x：x + %d = %d，x = ?" % (z, x + z), fmt(x), num_wrong(x)
            elif kind == 3:
                dec, frac = RNG.choice(FRACTIONS)
                stem, c, w = "小数 %s 化成分数是多少？" % dec, frac, \
                    [f for _, f in FRACTIONS if f != frac] + ["2/3", "5/8"]
            elif kind == 4:
                n, step = RNG.randint(2, 9), RNG.randint(2, 6)
                seq = [n + j * step for j in range(4)]
                stem, c, w = "按规律填数：%s，?" % "、".join(map(str, seq)), fmt(n + 4 * step), \
                    num_wrong(n + 4 * step)
            else:
                a, z = RNG.randint(4, 40), RNG.randint(2, 12)
                g = math.gcd(a, z)
                stem, c, w = "%d 和 %d 的最大公因数是多少？" % (a, z), fmt(g), num_wrong(g)
            b.add("数学", grade, "数与代数", stem, fmt(c) if not isinstance(c, str) else c, w, bonus=1)

    # 百分数（5-6 年级）
    for grade in (5, 6):
        for i in range(40):
            n = RNG.randint(1, 9) * 10
            base = RNG.randint(2, 20) * 10
            c = base * n // 100
            b.add("数学", grade, "数与代数",
                  "%d 的 %d%% 是多少？" % (base, n), fmt(c), num_wrong(c), bonus=1)

    # 统计与可能性
    for grade in range(2, 7):
        for i in range(35):
            if i % 2:
                nums = [RNG.randint(2, 20) for _ in range(4)]
                avg = sum(nums) / 4.0
                stem, c = "求 %s 这四个数的平均数。" % "、".join(map(str, nums)), fmt(round(avg, 1))
                b.add("数学", grade, "统计与可能性", stem, c, num_wrong(avg, pool=[
                    round(avg + d, 1) for d in (1, -1, 2, -2, 3, -3, 5)]), bonus=1)
            else:
                r, w = RNG.randint(2, 8), RNG.randint(1, 1)
                b.add("数学", grade, "统计与可能性",
                      "盒子里有 %d 个红球和 %d 个白球，任意摸一个，摸到哪种球的可能性更大？" % (r, w),
                      "红球", ["白球", "一样大", "无法判断", "都有可能"], bonus=1)
        b.judge("数学", grade, "统计与可能性",
                "条形统计图中，直条越高表示对应的数量越多。",
                "条形统计图中，直条越高表示对应的数量越少。", bonus=0)

    # 位置与方向 / 时间
    dirs = [("东", "西"), ("西", "东"), ("南", "北"), ("北", "南")]
    for grade in range(1, 7):
        for i in range(24):
            front, back = RNG.choice(dirs)
            if i % 3 == 0:
                b.add("数学", grade, "位置与方向",
                      "早晨面向太阳站立，前面是东，后面是哪个方向？", "西",
                      ["东", "南", "北", "东南", "西北"], bonus=0)
            elif i % 3 == 1:
                b.judge("数学", grade, "位置与方向",
                        "面向%s站立时，背后是%s。" % (front, back),
                        "面向%s站立时，背后也是%s。" % (front, front), bonus=0)
            else:
                col, row = RNG.randint(1, 9), RNG.randint(1, 9)
                b.add("数学", grade, "位置与方向",
                      "数对 (%d, %d) 表示的位置是？" % (col, row),
                      "第 %d 列第 %d 行" % (col, row),
                      ["第 %d 列第 %d 行" % (row, col), "第 %d 行第 %d 列" % (row, col),
                       "第 %d 行" % col, "第 %d 列" % row, "无法确定"], bonus=0)
        for i in range(20):
            h, m1, m2 = RNG.randint(1, 10), RNG.choice([0, 10, 15, 20, 30]), RNG.choice([0, 10, 15, 20, 30, 45])
            start = h * 60 + m1
            end = (h + 1) * 60 + m2
            c = end - start
            b.add("数学", grade, "时间与人民币",
                  "从 %d:%02d 到 %d:%02d，一共经过多少分钟？" % (h, m1, h + 1, m2),
                  fmt(c), num_wrong(c), bonus=0)


# ---------------------------------------------------------------- 英语
def misspell(w):
    if len(w) < 3:
        return w + "s"
    i = RNG.randrange(len(w))
    ch = RNG.choice("abcdefghijklmnopqrstuvwxyz")
    out = w[:i] + ch + w[i + 1:]
    if out == w:
        return w + "s"
    return out


def build_english(b, vocab):
    cn_pool = list(dict.fromkeys([v["cn"] for v in vocab]))
    en_pool = list(dict.fromkeys([v["en"] for v in vocab]))
    for i, v in enumerate(vocab):
        en, cn, grade = v["en"], v["cn"], v.get("grade", 3)
        grade = grade if isinstance(grade, int) and 1 <= grade <= 6 else 3
        g = max(1, min(6, grade))
        # 英 → 中
        b.add("英语", g, "单词词义",
              "单词「%s」的中文意思是？" % en, cn,
              RNG.sample([x for x in cn_pool if x != cn], 6), bonus=0)
        # 中 → 英
        b.add("英语", g, "单词词义",
              "「%s」对应的英语单词是？" % cn, en,
              RNG.sample([x for x in en_pool if x != en], 6), bonus=0)
        if i % 3 == 0:
            wrong_cn = RNG.choice([x for x in cn_pool if x != cn])
            b.judge("英语", g, "单词词义",
                    "单词「%s」的意思是「%s」。" % (en, cn),
                    "单词「%s」的意思是「%s」。" % (en, wrong_cn), bonus=0)
        if i % 5 == 0:
            ws = []
            for _ in range(6):
                x = misspell(en)
                if x != en and x not in ws:
                    ws.append(x)
            b.add("英语", g, "单词拼写",
                  "下面哪一个是「%s」的正确拼写？" % cn, en, ws, bonus=0)
        ex, ex_cn = v.get("ex", ""), v.get("exCn", "")
        if ex and ex_cn and i % 8 == 0:
            b.add("英语", g, "句子翻译",
                  "句子「%s」的中文意思是？" % ex, ex_cn,
                  RNG.sample([x for x in cn_pool if x != ex_cn], 6), bonus=1)

    for i, (stem, right, wrong) in enumerate(EN_GRAMMAR):
        g = grade_for(i, 3, 6)
        b.add("英语", g, "语法", "选择正确的一项填空：%s" % stem, right,
              list(wrong) + RNG.sample([x[1] for x in EN_GRAMMAR if x[1] != right], 4), bonus=1)
        wrong_one = RNG.choice(list(wrong))
        b.judge("英语", g, "语法",
                "句子「%s」中应填「%s」。" % (stem, right),
                "句子「%s」中应填「%s」。" % (stem, wrong_one), bonus=1)

    for i, (scene, right, wrong) in enumerate(EN_DIALOG):
        g = grade_for(i, 1, 6)
        b.add("英语", g, "情景交际", scene, right,
              list(wrong) + RNG.sample([x[1] for x in EN_DIALOG if x[1] != right], 4), bonus=0)
        wrong_one = RNG.choice(list(wrong))
        b.judge("英语", g, "情景交际",
                "%s 合适的说法是「%s」。" % (scene, right),
                "%s 最合适的说法是「%s」。" % (scene, wrong_one), bonus=0)


# ---------------------------------------------------------------- 站点公开选择题
FULL_FETCHED = os.path.join(ROOT, "tools", "data", "tiku_raw", "full_fetched.json")


def build_fetched(b):
    """把站点真实题目收进来：优先用登录态抓到的 full_fetched.json。

    有 typ 的题加【题型名】前缀（统计页能归到对应新题型）；数学/英语没有
    明确知识点标签的题不加前缀，交给 QType 按题干关键词推断，避免误标。
    """
    path = FULL_FETCHED if os.path.exists(FULL_FETCHED) else FETCHED
    if not os.path.exists(path):
        return 0
    data = json.load(open(path, encoding="utf-8"))
    n = 0
    for i, item in enumerate(data):
        opts = [str(x).strip() for x in item["options"]]
        ans = item.get("answer", -1)
        if len(opts) < 2 or len(set(opts)) != len(opts) or not (0 <= ans < len(opts)):
            continue
        if any(len(x) > 120 for x in opts):
            continue
        typ = item.get("typ", "")
        stem = ("【%s】%s" % (typ, item["q"])) if typ else item["q"]
        grade = grade_for(i, 2, 6)
        star = max(1, min(5, grade - 2 + 1))
        key = (stem, opts[ans])
        if key in b.seen:
            continue
        b.seen.add(key)
        b.items.append({
            "id": "v7-%05d" % (len(b.items) + 1),
            "subject": item["subject"], "grade": grade, "q": stem,
            "options": opts, "answer": ans, "difficulty": star,
        })
        n += 1
    print("站点真题来源：%s" % os.path.basename(path))
    return n


# ---------------------------------------------------------------- 主流程
def main():
    dry = "--dry-run" in sys.argv
    b = Builder()
    build_chinese(b)
    c1 = b.count()
    build_math(b)
    c2 = b.count() - c1
    vocab = json.load(open(VOCAB, encoding="utf-8"))
    build_english(b, vocab)
    c3 = b.count() - c1 - c2
    c4 = build_fetched(b)

    from collections import Counter

    def tag_of(item):
        q = item["q"]
        if q.startswith("【") and "】" in q:
            return q[1:q.index("】")]
        return "（由分类器推断）"

    print("新增题：语文 %d / 数学 %d / 英语 %d / 站点抓取 %d，合计 %d" %
          (c1, c2, c3, c4, b.count()))
    print("题型分布：", dict(Counter(tag_of(x) for x in b.items)))
    print("年级分布：", dict(sorted(Counter(x["grade"] for x in b.items).items())))

    # 自检：answer 指向正确项、选项互异、ID 唯一、生成器题必带题型标签
    ids = set()
    generated = c1 + c2 + c3
    for i, x in enumerate(b.items):
        assert x["id"] not in ids, "ID 重复"
        ids.add(x["id"])
        assert 0 <= x["answer"] < len(x["options"]), "answer 越界"
        assert len(set(x["options"])) == len(x["options"]), "选项重复"
        assert 1 <= x["grade"] <= 6 and 1 <= x["difficulty"] <= 5, "年级/难度越界"
        assert x["q"].strip(), "题干为空"
        if i < generated:
            assert x["q"].startswith("【"), "生成器题缺少题型标签"

    if dry:
        print("--dry-run：未写入任何文件")
        return

    root = json.load(open(ASSETS, encoding="utf-8"))
    old = root["questions"]
    old_ids = set(x.get("id", "") for x in old)
    assert not (ids & old_ids), "新题 ID 与旧题冲突"
    os.makedirs(DIST, exist_ok=True)
    json.dump(b.items, open(os.path.join(DIST, "bank_v7_new.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    root["questions"] = old + b.items
    root["count"] = len(root["questions"])
    root["version"] = int(root.get("version", 6)) + 1
    root["note"] = root["note"].rstrip() + (
        " v7：按牛题库(tiku.cn)章节知识点补齐语文 7 类题型（形近字辨析、错别字辨析、"
        "词义字义辨析、歇后语谚语、文言字词、诗词鉴赏、文学常识），数学补统计与可能性、"
        "位置与方向、时间与人民币、单位换算、图形公式、方程与百分数等，英语补人教词汇"
        "双向词义、拼写、语法、情景交际与句子翻译；新增 %d 题，题干以【题型名】标注，"
        "含少量判断题（2 选项），数学数值题答案均由程序计算。" % b.count())
    tmp = ASSETS + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(root, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, ASSETS)
    print("已写入 %s：%d → %d 题" % (ASSETS, len(old), len(root["questions"])))


if __name__ == "__main__":
    main()
