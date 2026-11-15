# -*- coding: utf-8 -*-
"""从成品题库中下架「选单词首字母」这类过简单题（v1.10.8）。

判定口径与 QuestionBank.isRetiredFirstLetter() 保持一致：
  1. id 属于官方基础库 g[1-6]-english-<数字>（家长自定义题不碰）；
  2. 题干命中「第一个字母 / 首字母 / 开头字母 / 以哪个字母开头」；
  3. 每个选项都是单个 ASCII 字母。
「字母表顺序」「大写字母对应小写」「数学字母公式」等正常知识点不在范围内。

用法：python tools/remove_first_letter.py [--apply]
默认 dry-run；生成链路重跑 make_full_bank.py 之后应接着跑一次本脚本。
"""
import io
import json
import re
import sys
import collections

BANK = 'app/src/main/assets/questions.json'
VERSION = 10
RE_ID = re.compile(r'^g[1-6]-english-\d+$')
RE_TEXT = re.compile(r'(第一个字母|首字母|开头字母|以哪个字母开头|以什么字母开头)')


def is_retired(q):
    if not RE_ID.match(str(q.get('id', ''))):
        return False
    if not RE_TEXT.search(str(q.get('q', ''))):
        return False
    opts = q.get('options') or []
    if len(opts) < 2:
        return False
    return all(isinstance(o, str) and len(o) == 1 and re.match(r'[A-Za-z]', o)
               for o in opts)


def main():
    with io.open(BANK, encoding='utf-8') as f:
        doc = json.load(f)
    qs = doc['questions']
    drop = [q for q in qs if is_retired(q)]
    keep = [q for q in qs if not is_retired(q)]
    print('原有 %d 题，下架 %d 题，保留 %d 题' % (len(qs), len(drop), len(keep)))
    print('按年级:', dict(collections.Counter(q['grade'] for q in drop)))
    print('按模板:', dict(collections.Counter(
        re.sub(r'「[^」]*」', '「X」', q['q']) for q in drop)))
    print('保留的含「字母」题 %d 道（字母表 / 字母公式等正常知识点）'
          % sum(1 for q in keep if '字母' in q['q']))
    if '--apply' not in sys.argv:
        print('\n(dry-run，未写文件；加 --apply 写入)')
        return
    if not drop and doc.get('version') == VERSION:
        print('题库已是目标版本且无待下架题，无需改动')
        return
    doc['questions'] = keep
    doc['count'] = len(keep)
    doc['version'] = VERSION
    note = doc.get('note', '')
    note = note.replace('英语（词义、例句翻译、拼写、首字母）',
                        '英语（词义、例句翻译、拼写）')
    tag = (' v10 下架 %d 道「选单词首字母 / 第一个字母 / 以哪个字母开头」题：'
           '只需认字母、过于简单；字母表顺序、大小写、字母公式等题保留。'
           '设备端 bankCustom 题库升级时按同一口径清理。' % len(drop))
    if tag.strip() not in note:
        note += tag
    doc['note'] = note
    with io.open(BANK, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, separators=(',', ':'))
    print('\n已写入 %s (version=%d, count=%d)' % (BANK, VERSION, len(keep)))


if __name__ == '__main__':
    main()
