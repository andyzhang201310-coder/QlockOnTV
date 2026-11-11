# -*- coding: utf-8 -*-
"""题库 v6 升级脚本（一次性数据加工，产物是 app/src/main/assets/questions.json）

做两件事：
1. 给每一道题补 1–5 星难度字段（difficulty），后台/电视端据此显示星级。
2. 把语文题库里「只问一个要素」的诗词题（作者 / 朝代 / 出自哪首诗）重写成
   「朝代 + 作者」「诗名 + 作者 + 朝代」等复合答案，并轮换多种问法，避免同一首诗
   连着出同一句式。

数据来源就是原题库本身：作者 / 朝代 / 诗句都从原有题目里抽出来配对，不额外编造。
只有 5 首原题库缺作者的篇目（敕勒歌、长歌行、迢迢牵牛星、采薇、画）用教科书
通行说法补齐（北朝民歌、汉乐府、佚名）。

用法：python tools/bank_upgrade_v6.py [--check]
"""
import io
import json
import os
import re
import sys
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BANK = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'questions.json')

NEW_VERSION = 6

# 原题库没有「《X》的作者是谁」这类题的篇目，按教材通行说法补
AUTHOR_FIX = {
    '敕勒歌': '北朝民歌',
    '长歌行': '汉乐府',
    '迢迢牵牛星': '佚名',
    '采薇': '佚名',
    '画': '佚名',
}

RE_AUTHOR_T = re.compile(r'^《(.+?)》的作者是谁？$')
RE_DYN_T = re.compile(r'^《(.+?)》的作者生活在哪个朝代？$')
RE_DYN_T2 = re.compile(r'^《(.+?)》是哪个朝代（时期）的作品？$')
RE_FROM_S = re.compile(r'^「(.+?)」出自哪首诗？$')
RE_AUTHOR_S = re.compile(r'^「(.+?)」这句诗的作者是谁？$')
RE_DYN_S = re.compile(r'^「(.+?)」这句诗出自哪个朝代（时期）？$')
RE_LINE_T = re.compile(r'^下列哪一句出自《(.+?)》？$')

# 名句 → 篇目 的映射，由 build_poems() 填好，供 poem_title_of() 认题用
LINE_TO_TITLE = {}

# 这些模板的题会被重写（复合答案）
POEM_TEMPLATES = [RE_AUTHOR_T, RE_DYN_T, RE_DYN_T2, RE_FROM_S,
                  RE_AUTHOR_S, RE_DYN_S, RE_LINE_T]


def is_poem_question(text):
    return any(p.match(text) for p in POEM_TEMPLATES)


def poem_title_of(q):
    """题目指向的篇目名；认不出来返回 None（这类题原样保留）"""
    t = q['q']
    m = RE_AUTHOR_T.match(t) or RE_DYN_T.match(t) or RE_DYN_T2.match(t) or RE_LINE_T.match(t)
    if m:
        return m.group(1)
    m = RE_FROM_S.match(t)
    if m:
        return q['options'][q['answer']].strip('《》')
    # 「S」这句诗的作者/朝代：靠名句反查篇目
    m = RE_AUTHOR_S.match(t) or RE_DYN_S.match(t)
    if m:
        return LINE_TO_TITLE.get(m.group(1))
    return None


def pick_letter(i):
    return 'ABCDEF'[i]


def build_poems(questions):
    """从原题库里抽出 篇目 → 作者 / 朝代 / 名句 / 年级"""
    poems = collections.OrderedDict()

    def slot(title):
        return poems.setdefault(title, {'title': title, 'authors': collections.Counter(),
                                        'dyns': collections.Counter(),
                                        'lines': [], 'grades': collections.Counter()})

    ans = lambda q: q['options'][q['answer']].strip()
    for q in questions:
        if q['subject'] != '语文':
            continue
        t = q['q']
        m = RE_AUTHOR_T.match(t)
        if m:
            p = slot(m.group(1))
            p['authors'][ans(q)] += 1
            p['grades'][q['grade']] += 1
            continue
        m = RE_DYN_T.match(t) or RE_DYN_T2.match(t)
        if m:
            p = slot(m.group(1))
            p['dyns'][ans(q)] += 1
            p['grades'][q['grade']] += 1
            continue
        m = RE_FROM_S.match(t) or RE_LINE_T.match(t)
        if m:
            if RE_FROM_S.match(t):
                line, title = m.group(1), ans(q).strip('《》')
            else:
                title, line = m.group(1), ans(q)
            p = slot(title)
            if line not in p['lines']:
                p['lines'].append(line)
            LINE_TO_TITLE[line] = title
            p['grades'][q['grade']] += 1
            continue
    for title, a in AUTHOR_FIX.items():
        if title in poems and not poems[title]['authors']:
            poems[title]['authors'][a] = 1
    out = []
    for title, p in poems.items():
        if not p['authors'] or not p['dyns']:
            continue
        out.append({
            'title': title,
            'author': p['authors'].most_common(1)[0][0],
            'dyn': p['dyns'].most_common(1)[0][0],
            'lines': p['lines'],
            'grade': p['grades'].most_common(1)[0][0],
        })
    return out


def other_poems(poems, me, grade):
    """干扰项候选：优先同年级，其次全库，且作者与正确答案不同"""
    same = [p for p in poems if p is not me and p['grade'] == grade and p['author'] != me['author']]
    others = [p for p in poems if p is not me and p['author'] != me['author']]
    seen = set()
    out = []
    for p in same + others:
        if p['title'] in seen:
            continue
        seen.add(p['title'])
        out.append(p)
    return out


def take_distractors(pool, need, start):
    """从候选池里取 need 个互不相同的干扰项（按下标错开，避免每题干扰项都一样）"""
    out = []
    if not pool:
        return out
    i = start % len(pool)
    guard = 0
    while len(out) < need and guard < len(pool) * 2:
        p = pool[i % len(pool)]
        if p not in out:
            out.append(p)
        i += 1
        guard += 1
    return out


def make_options(correct, distractor_texts, rot):
    """正确答案 + 干扰项，按 rot 轮换正确项位置，答案下标随之变化。

    干扰项里可能出现同样的文字（同一作者的两首诗、同名朝代等），这里统一去重，
    去重后不够 3 个就返回 None，由调用方跳过这道题，避免出现两个一样/重复的选项。
    """
    texts = []
    for t in distractor_texts:
        if t != correct and t not in texts:
            texts.append(t)
    if len(texts) < 3:
        return None, -1
    texts = texts[:3]
    pos = rot % 4
    opts = list(texts)
    opts.insert(pos, correct)
    return opts, pos


def gen_poem_questions(p, poems, idx):
    """给一首诗生成 5–7 道复合答案题，问法按 idx 轮换"""
    g = p['grade']
    T, A, D = p['title'], p['author'], p['dyn']
    S = p['lines'][0] if p['lines'] else None
    pool = other_poems(poems, p, g)
    out = []

    def dtitle(k, off=0):
        return take_distractors(pool, k, idx * 3 + off)

    def add(stem, correct, texts):
        opts, ans = make_options(correct, texts, len(out) + idx)
        if opts:
            out.append(dict(q=stem, options=opts, answer=ans, _composite=True))

    # ① 名句 → 诗名 + 作者 + 朝代（三要素）
    if S:
        ds = dtitle(3, 1)
        add('「%s」出自哪首诗？它的作者和朝代是？' % S,
            '《%s》· %s · %s' % (T, A, D),
            ['《%s》· %s · %s' % (x['title'], x['author'], x['dyn']) for x in ds])

    # ② 名句 → 作者 + 朝代（两要素，措辞轮换）
    if S:
        ds = dtitle(3, 3)
        stem = ('「%s」的作者生活在哪个朝代？' % S) if idx % 2 == 0 else \
               ('「%s」的作者和朝代是？' % S)
        add(stem, '%s · %s' % (A, D),
            ['%s · %s' % (x['author'], x['dyn']) for x in ds])

    # ③ 篇名 → 作者 + 朝代
    ds = dtitle(3, 5)
    add('《%s》的作者和朝代是？' % T, '%s · %s' % (A, D),
        ['%s · %s' % (x['author'], x['dyn']) for x in ds])

    # ④ 出处 + 作者 + 朝代（换个问法，考「哪一项搭配正确」）
    if S:
        ds = dtitle(3, 7)
        add('「%s」的正确出处（诗名 · 作者 · 朝代）是哪一项？' % S,
            '《%s》· %s · %s' % (T, A, D),
            ['《%s》· %s · %s' % (x['title'], x['author'], x['dyn']) for x in ds])

    # ⑤ 搭配判断：诗句 — 作者 — 朝代
    if S:
        ds = dtitle(3, 9)
        texts = []
        for k, x in enumerate(ds):
            if k % 2 == 0 and x['lines']:
                # 别的诗的名句 + 本诗作者朝代 → 错配
                texts.append('%s · %s · %s' % (x['lines'][0], A, D))
            else:
                # 本诗名句 + 别人的作者朝代 → 错配
                texts.append('%s · %s · %s' % (S, x['author'], x['dyn']))
        add('下列「诗句 — 作者 — 朝代」搭配正确的是？',
            '%s · %s · %s' % (S, A, D), texts)

    # ⑥ 朝代在前：朝代 + 作者（同一事实的另一种组合顺序）
    ds = dtitle(3, 11)
    stem = ('《%s》是哪个朝代、谁的作品？' % T) if idx % 2 == 0 else \
           ('「%s」是哪个朝代、谁的作品？' % S) if S else ('《%s》是哪个朝代、谁的作品？' % T)
    add(stem, '%s · %s' % (D, A),
        ['%s · %s' % (x['dyn'], x['author']) for x in ds])

    # ⑦ 反过来：作者 + 朝代 → 名句
    if S:
        ds = dtitle(3, 13)
        lines = [x['lines'][0] for x in ds if x['lines'] and x['lines'][0] != S][:3]
        if len(lines) >= 3:
            opts, ans = make_options(S, lines, len(out) + idx)
            if opts:
                out.append(dict(q='%s（%s）写的《%s》中，名句是哪一句？' % (A, D, T),
                                options=opts, answer=ans, _composite=True))

    for k, item in enumerate(out):
        item['subject'] = '语文'
        item['grade'] = g
        # 复合题单独给 id（poem-篇目序号-第几道），后台列表里一眼能认出来
        item['id'] = 'poem-%d-%d' % (idx + 1, k + 1)
    return out


def difficulty_of(q, composite=False):
    """1–5 星：年级打底 + 题型复杂度微调（规则写在 note 里，家长可自行改星）"""
    g = q.get('grade') or 0
    base = 3 if g <= 0 else (1 if g <= 2 else g - 1)
    text = q.get('q') or ''
    n = len(text)
    adj = 0
    if composite:
        adj += 1
    if n >= 26:
        adj += 1
    elif n <= 10:
        adj -= 1
    easy = ('有几画', '的部首是', '第几声', '哪个字读', '反义词是', '近义词是', '量词')
    if any(k in text for k in easy):
        adj -= 1
    if text.count('×') + text.count('＋') + text.count('+') + text.count('-') >= 2:
        adj += 1
    if '应用题' in text or '一共' in text or '还剩' in text:
        adj += 1
    return max(1, min(5, base + adj))


def main():
    check = '--check' in sys.argv
    with io.open(BANK, encoding='utf-8') as f:
        bank = json.load(f)
    questions = bank['questions']
    print('原题库 %d 题（version %s）' % (len(questions), bank.get('version')))

    poems = build_poems(questions)
    print('抽到 %d 首（篇）可复合出题的诗词' % len(poems))

    # 先删掉所有单要素诗词题，再按诗补回复合题（顺序尽量保持原位）。
    # 组不成复合题的篇目（缺作者或缺朝代）原样保留，不能把题删没了。
    composable = set(p['title'] for p in poems)
    kept = []
    replaced_at = None
    drop = 0
    for q in questions:
        if q['subject'] == '语文' and is_poem_question(q['q']):
            title = poem_title_of(q)
            if title in composable:
                drop += 1
                if replaced_at is None:
                    replaced_at = len(kept)
                continue
        kept.append(q)
    print('替换单要素诗词题 %d 道' % drop)

    generated = []
    for i, p in enumerate(poems):
        generated.extend(gen_poem_questions(p, poems, i))
    print('生成复合诗词题 %d 道' % len(generated))

    # 插回原位置附近；同时补 id
    at = replaced_at if replaced_at is not None else len(kept)
    merged = kept[:at] + generated + kept[at:]
    for i, q in enumerate(merged):
        composite = q.pop('_composite', False)
        q['difficulty'] = difficulty_of(q, composite=composite)
        if not q.get('id'):
            q['id'] = 'q%d' % (i + 1)

    # id 去重：生成题的 id 与老题可能撞车，这里统一补后缀
    seen = set()
    for i, q in enumerate(merged):
        qid = q['id']
        if qid in seen:
            qid = '%s-%d' % (qid, i)
        seen.add(qid)
        q['id'] = qid

    dist = collections.Counter(q['difficulty'] for q in merged)
    print('难度分布：%s' % dict(sorted(dist.items())))

    bank['questions'] = merged
    bank['version'] = NEW_VERSION
    bank['count'] = len(merged)
    old_note = bank.get('note', '')
    bank['note'] = (old_note + ' v%d：每道题新增 difficulty（1–5 星，按年级打底 + 题型复杂度微调，'
                    '家长可在后台单题改星）；语文里原先只问「作者」「朝代」「出自哪首诗」的'
                    '单要素题改为复合答案（诗名 · 作者 · 朝代、作者 · 朝代、朝代 · 作者、'
                    '诗句 — 作者 — 朝代 搭配判断等），并轮换问法。' % NEW_VERSION)
    print('新题库 %d 题' % len(merged))
    if check:
        return
    tmp = BANK + '.tmp'
    with io.open(tmp, 'w', encoding='utf-8') as f:
        json.dump(bank, f, ensure_ascii=False, separators=(',', ':'))
    os.replace(tmp, BANK)
    print('已写入 %s（%.1f MB）' % (BANK, os.path.getsize(BANK) / 1048576.0))


if __name__ == '__main__':
    main()
