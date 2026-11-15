# -*- coding: utf-8 -*-
"""把《一本·小学英语必备词汇》索引词表生成「小学必备词汇」分类选择题，合并进内置题库。

数据源：
  tools/vocab_index.json  从 PDF 扫描索引 OCR 出的 874 个词条（w=单词，p=原书页码）
  tools/vocab_mean.txt    人工校订的中文释义（题库里没有的词）
  questions.json          现有英语题里已有的「单词 → 中文释义」映射

用法：
  python tools/build_vocab_bank.py            # 只统计，不写文件
  python tools/build_vocab_bank.py --apply   # 生成并合并进 assets/questions.json

题目格式与现有题库一致；subject 用新分类名，后台「答题范围」会据此自动生成
一个可单独勾选的科目 chip（见 admin.html renderScope / Rules.quizSubjects）。
"""
import io
import json
import os
import random
import re
import sys
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BANK = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'questions.json')
INDEX = os.path.join(ROOT, 'tools', 'vocab_index.json')
MEAN = os.path.join(ROOT, 'tools', 'vocab_mean.txt')
SUBJECT = '小学必备词汇'
PREFIX = 'vocab-'

PAT_EN = re.compile(r'「([A-Za-z][A-Za-z\s\.\'\-]{0,28})」(?:这个单词)?(?:的中文意思|是什么意思|的意思是|中文是)')
PAT_REV = re.compile(r'(?:哪个英文单词|英语单词|英文).{0,12}意思是?「([^」]+)」')


def load_json(path):
    with io.open(path, encoding='utf-8') as f:
        return json.load(f)


def bank_meanings(questions):
    """从现有英语题里反查 单词 → 中文释义。"""
    m = collections.defaultdict(collections.Counter)
    for q in questions:
        if q.get('subject') != '英语':
            continue
        t, opts, a = q.get('q', ''), q.get('options') or [], q.get('answer', 0)
        if not (0 <= a < len(opts)):
            continue
        hit = PAT_EN.search(t)
        if hit:
            ans = opts[a]
            if re.search(r'[\u4e00-\u9fa5]', ans):
                m[hit.group(1).strip().lower()][ans] += 1
            continue
        rev = PAT_REV.search(t)
        if rev and re.fullmatch(r'[A-Za-z\.\'\-\s]{1,30}', opts[a] or ''):
            m[opts[a].strip().lower()][rev.group(1)] += 1
    return {w: c.most_common(1)[0][0] for w, c in m.items()}


def extra_meanings():
    out = {}
    with io.open(MEAN, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            w, m = line.split('=', 1)
            out[w.strip()] = m.strip()
    return out


def bad(m):
    return (not m or len(m) > 16 or '说法' in m or '判断' in m
            or any(c in m for c in '。？！,'))


def build(questions):
    words = load_json(INDEX)
    extra = extra_meanings()
    from_bank = bank_meanings(questions)
    unused_extra = set(extra) - {r['w'] for r in words}

    meanings, source = {}, collections.Counter()
    for r in words:
        w = r['w']
        m = extra.get(w)
        if m:
            source['人工校订'] += 1
        else:
            m = from_bank.get(w)
            if m:
                source['现有题库'] += 1
        if bad(m):
            m = from_bank.get(w) or extra.get(w)
        if bad(m):
            raise SystemExit('词条缺少可用释义: %r' % w)
        meanings[w] = m

    # 同页码 = 原书同一课 / 同一话题，优先拿同课词做干扰项
    by_page = collections.defaultdict(list)
    for r in words:
        if r.get('p'):
            by_page[r['p']].append(r['w'])
    pool = sorted(set(meanings.values()))
    rng = random.Random(20261115)

    def distractors(w, page):
        right = meanings[w]
        # 同课词的释义排前面，后面才是全库释义兜底
        cand = [meanings[x] for x in by_page.get(page, []) if x != w]
        rng.shuffle(cand)
        cand += rng.sample(pool, len(pool))
        out = []
        for m in cand:
            if not m or m == right or m in out:
                continue
            # 「肉 / 牛肉」这类互相包含的词不放在同一题里，免得靠字面猜
            if m in right or right in m:
                continue
            out.append(m)
            if len(out) == 3:
                break
        if len(out) < 3:
            raise SystemExit('%s 的干扰项不足' % w)
        return out

    items = []
    for i, r in enumerate(words, 1):
        w, page = r['w'], r.get('p', '')
        opts = [meanings[w]] + distractors(w, page)
        ans = opts[0]
        rng.shuffle(opts)
        # 短语 / 长词更难一点；该分类不分年级，grade=0 表示全年级通用
        star = 1 if len(w) <= 5 and ' ' not in w else (2 if len(w) <= 9 else 3)
        if ' ' in w and len(w) > 12:
            star = 3
        items.append({
            'id': '%s%04d' % (PREFIX, i),
            'subject': SUBJECT,
            'grade': 0,
            'q': '【单词词义】「%s」的中文意思是？' % w,
            'options': opts,
            'answer': opts.index(ans),
            'difficulty': star,
        })

    kept = [q for q in questions if not str(q.get('id', '')).startswith(PREFIX)]
    return kept + items, items, source, unused_extra


def main():
    doc = load_json(BANK)
    merged, items, source, unused = build(doc['questions'])
    print('词条 %d → 生成 %d 题；释义来源 %s' % (len(items), len(items), dict(source)))
    print('原有 %d 题，合并后 %d 题；未用上的补义 %d 条' %
          (len(doc['questions']), len(merged), len(unused)))
    print('答案位置分布:', dict(collections.Counter(q['answer'] for q in items)))
    print('难度分布:', dict(collections.Counter(q['difficulty'] for q in items)))
    print('--- 抽样 6 题 ---')
    for q in (items[0], items[1], items[100], items[300], items[600], items[-1]):
        print(json.dumps(q, ensure_ascii=False))
    if '--apply' not in sys.argv:
        print('\n(dry-run，未写文件；加 --apply 写入)')
        return
    doc['questions'] = merged
    doc['count'] = len(merged)
    # v1.10.8 起题库已升到 10；重跑本脚本只能往上带，不能把版本降回去
    doc['version'] = max(9, int(doc.get('version', 9)))
    doc['note'] = doc.get('note', '') + (
        ' v9 追加「小学必备词汇」分类 %d 题：取自《一本·小学英语必备词汇》'
        '索引全部词条，一词一题考中文词义，grade=0 表示全年级通用，'
        '可在后台「答题范围」单独勾选。' % len(items))
    with io.open(BANK, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, separators=(',', ':'))
    print('\n已写入 %s (version=%d, count=%d)' % (BANK, doc['version'], len(merged)))


if __name__ == '__main__':
    main()
