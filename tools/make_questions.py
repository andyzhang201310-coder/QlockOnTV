# -*- coding: utf-8 -*-
"""生成 app/src/main/assets/questions.json（1-6 年级 × 语文/数学/英语）。

- 语文、英语：手写题库，正确答案统一放 options[0]，answer=0（运行时打乱）。
- 数学：按年级程序生成口算/计算题，答案由程式算出，避免人工写错。
运行：python tools/make_questions.py
"""
import io
import json
import random

from questions_extra import CHINESE_EXTRA, ENGLISH_EXTRA, MATH_EXTRA

random.seed(20261109)

SUB_CN = "语文"
SUB_MA = "数学"
SUB_EN = "英语"

# ------------------------------------------------------------------ 语文
# (题干, 正确选项, 干扰1, 干扰2, 干扰3)
CHINESE = {
    1: [
        ("「鹅，鹅，鹅」的下一句是？", "曲项向天歌", "白毛浮绿水", "红掌拨清波", "飞入菜花无处寻"),
        ("「白毛浮绿水」的下一句是？", "红掌拨清波", "曲项向天歌", "疑是地上霜", "低头思故乡"),
        ("「床前明月光」的下一句是？", "疑是地上霜", "举头望明月", "低头思故乡", "春眠不觉晓"),
        ("「举头望明月」的下一句是？", "低头思故乡", "疑是地上霜", "夜来风雨声", "花落知多少"),
        ("「春眠不觉晓」的下一句是？", "处处闻啼鸟", "夜来风雨声", "花落知多少", "疑是地上霜"),
        ("「夜来风雨声」的下一句是？", "花落知多少", "处处闻啼鸟", "低头思故乡", "举头望明月"),
        ("下面哪个字读「shān」？", "山", "水", "田", "土"),
        ("「大」的反义词是？", "小", "多", "高", "长"),
        ("「一、二、三」后面的数字是？", "四", "五", "六", "十"),
        ("「日」字有几笔？", "4 笔", "3 笔", "5 笔", "2 笔"),
    ],
    2: [
        ("「草长莺飞二月天」的下一句是？", "拂堤杨柳醉春烟", "儿童散学归来早", "忙趁东风放纸鸢", "春江水暖鸭先知"),
        ("「儿童散学归来早」的下一句是？", "忙趁东风放纸鸢", "拂堤杨柳醉春烟", "草长莺飞二月天", "早有蜻蜓立上头"),
        ("「泉眼无声惜细流」的下一句是？", "树阴照水爱晴柔", "小荷才露尖尖角", "早有蜻蜓立上头", "映日荷花别样红"),
        ("「小荷才露尖尖角」的下一句是？", "早有蜻蜓立上头", "树阴照水爱晴柔", "泉眼无声惜细流", "接天莲叶无穷碧"),
        ("「毕竟西湖六月中」的下一句是？", "风光不与四时同", "接天莲叶无穷碧", "映日荷花别样红", "淡妆浓抹总相宜"),
        ("「接天莲叶无穷碧」的下一句是？", "映日荷花别样红", "风光不与四时同", "毕竟西湖六月中", "小荷才露尖尖角"),
        ("「亡羊补牢」这个成语告诉我们？", "出了问题及时补救还不晚", "做事情要提前计划", "不要轻易相信别人", "做事要有始有终"),
        ("下面哪个词是形容春天的？", "春暖花开", "大雪纷飞", "秋高气爽", "烈日炎炎"),
        ("「一（　）马」应该填哪个量词？", "匹", "头", "只", "条"),
        ("「漂亮」的近义词是？", "美丽", "丑陋", "难看", "普通"),
    ],
    3: [
        ("「日照香炉生紫烟」的下一句是？", "遥看瀑布挂前川", "飞流直下三千尺", "疑是银河落九天", "两岸青山相对出"),
        ("「飞流直下三千尺」的下一句是？", "疑是银河落九天", "遥看瀑布挂前川", "日照香炉生紫烟", "黄河入海流"),
        ("「两个黄鹂鸣翠柳」的下一句是？", "一行白鹭上青天", "窗含西岭千秋雪", "门泊东吴万里船", "孤帆远影碧空尽"),
        ("「窗含西岭千秋雪」的下一句是？", "门泊东吴万里船", "一行白鹭上青天", "两个黄鹂鸣翠柳", "唯见长江天际流"),
        ("「停车坐爱枫林晚」的下一句是？", "霜叶红于二月花", "远上寒山石径斜", "白云生处有人家", "春色满园关不住"),
        ("「远上寒山石径斜」的下一句是？", "白云生处有人家", "霜叶红于二月花", "停车坐爱枫林晚", "千山鸟飞绝"),
        ("下面哪个成语形容做事非常专心？", "全神贯注", "三心二意", "心不在焉", "东张西望"),
        ("「骄傲」的反义词是？", "谦虚", "自豪", "得意", "自满"),
        ("「蜜蜂在花丛中飞」这句话中，「蜜蜂」属于？", "动物", "植物", "物品", "天气"),
        ("下面哪组词全是近义词？", "高兴—快乐", "高兴—难过", "快乐—悲伤", "美丽—丑陋"),
    ],
    4: [
        ("「横看成岭侧成峰」的下一句是？", "远近高低各不同", "不识庐山真面目", "只缘身在此山中", "飞流直下三千尺"),
        ("「不识庐山真面目」的下一句是？", "只缘身在此山中", "远近高低各不同", "横看成岭侧成峰", "疑是银河落九天"),
        ("「一道残阳铺水中」的下一句是？", "半江瑟瑟半江红", "可怜九月初三夜", "露似真珠月似弓", "日出江花红胜火"),
        ("「可怜九月初三夜」的下一句是？", "露似真珠月似弓", "半江瑟瑟半江红", "一道残阳铺水中", "霜叶红于二月花"),
        ("「桃花潭水深千尺」的下一句是？", "不及汪伦送我情", "忽闻岸上踏歌声", "李白乘舟将欲行", "唯见长江天际流"),
        ("「飞流直下三千尺，疑是银河落九天」用了什么修辞手法？", "夸张", "拟人", "排比", "反问"),
        ("「小草偷偷地从土里钻出来」用了什么修辞手法？", "拟人", "夸张", "比喻", "排比"),
        ("下面哪个词语是描写声音的？", "叮咚", "碧绿", "高大", "温暖"),
        ("「坚固」的反义词是？", "脆弱", "结实", "坚硬", "稳固"),
        ("「他跑得像风一样快」这句话把什么比作什么？", "把他跑的速度比作风", "把风比作他", "把他比作路", "把速度比作路"),
    ],
    5: [
        ("「死去元知万事空」的下一句是？", "但悲不见九州同", "王师北定中原日", "家祭无忘告乃翁", "西湖歌舞几时休"),
        ("「王师北定中原日」的下一句是？", "家祭无忘告乃翁", "但悲不见九州同", "死去元知万事空", "直把杭州作汴州"),
        ("「山外青山楼外楼」的下一句是？", "西湖歌舞几时休", "暖风熏得游人醉", "直把杭州作汴州", "但悲不见九州同"),
        ("「暖风熏得游人醉」的下一句是？", "直把杭州作汴州", "西湖歌舞几时休", "山外青山楼外楼", "家祭无忘告乃翁"),
        ("「千山鸟飞绝」的下一句是？", "万径人踪灭", "孤舟蓑笠翁", "独钓寒江雪", "空山不见人"),
        ("「孤舟蓑笠翁」的下一句是？", "独钓寒江雪", "万径人踪灭", "千山鸟飞绝", "但闻人语响"),
        ("「温故而知新」中「故」的意思是？", "旧的知识", "原因", "故事", "故意"),
        ("「守株待兔」这个寓言讽刺的是？", "不努力只想靠运气的人", "勤劳耕作的人", "善于观察的人", "喜欢兔子的人"),
        ("下面哪句话是比喻句？", "月亮像一只小船", "月亮升起来了", "月亮很圆", "今晚有月亮"),
        ("「举世闻名」的意思是？", "全世界都知道", "只有本国知道", "刚刚出名", "名声不好"),
    ],
    6: [
        ("「千锤万凿出深山」的下一句是？", "烈火焚烧若等闲", "粉骨碎身浑不怕", "要留清白在人间", "咬定青山不放松"),
        ("「粉骨碎身浑不怕」的下一句是？", "要留清白在人间", "烈火焚烧若等闲", "千锤万凿出深山", "任尔东西南北风"),
        ("「咬定青山不放松」的下一句是？", "立根原在破岩中", "千磨万击还坚劲", "任尔东西南北风", "烈火焚烧若等闲"),
        ("「千磨万击还坚劲」的下一句是？", "任尔东西南北风", "立根原在破岩中", "咬定青山不放松", "要留清白在人间"),
        ("「锄禾日当午」的下一句是？", "汗滴禾下土", "谁知盘中餐", "粒粒皆辛苦", "春种一粒粟"),
        ("「谁知盘中餐」的下一句是？", "粒粒皆辛苦", "汗滴禾下土", "锄禾日当午", "秋收万颗子"),
        ("「学而不思则罔」中「罔」的意思是？", "迷惑而无所得", "欺骗", "渔网", "忘记"),
        ("「不入虎穴，焉得虎子」的意思是？", "不冒险就难以成功", "老虎很危险", "山洞不能进", "做事要小心"),
        ("「他的声音像洪钟一样」用了什么修辞？", "比喻", "拟人", "夸张", "反问"),
        ("下面哪个成语含有贬义？", "画蛇添足", "锦上添花", "雪中送炭", "精益求精"),
    ],
}

# ------------------------------------------------------------------ 英语
ENGLISH = {
    1: [
        ("「红色」的英文是？", "red", "blue", "green", "yellow"),
        ("「苹果」的英文是？", "apple", "banana", "orange", "pear"),
        ("「猫」的英文是？", "cat", "dog", "pig", "duck"),
        ("字母 A 后面是哪个字母？", "B", "C", "D", "E"),
        ("「三」的英文是？", "three", "two", "four", "five"),
        ("「书」的英文是？", "book", "bag", "pen", "desk"),
        ("「你好」的英文是？", "hello", "sorry", "bye", "please"),
        ("「狗」的英文是？", "dog", "cat", "cow", "fox"),
        ("「爸爸」的英文是？", "father", "mother", "brother", "sister"),
        ("「水」的英文是？", "water", "milk", "juice", "tea"),
    ],
    2: [
        ("「铅笔」的英文是？", "pencil", "ruler", "eraser", "crayon"),
        ("「老师」的英文是？", "teacher", "student", "doctor", "driver"),
        ("「星期一」的英文是？", "Monday", "Sunday", "Friday", "Tuesday"),
        ("「桌子」的英文是？", "table", "chair", "door", "window"),
        ("「米饭」的英文是？", "rice", "bread", "cake", "noodle"),
        ("「眼睛」的英文是？", "eye", "ear", "nose", "mouth"),
        ("「大」的英文是？", "big", "small", "long", "short"),
        ("「游泳」的英文是？", "swim", "run", "jump", "sing"),
        ("「鸟」的英文是？", "bird", "fish", "rabbit", "tiger"),
        ("「十一」的英文是？", "eleven", "twelve", "ten", "twenty"),
    ],
    3: [
        ("「two apples」中 apples 为什么加 s？", "表示两个以上要用复数", "苹果是可数名词的单数", "表示特指", "没有特别原因"),
        ("「I ___ a student.」应该填？", "am", "is", "are", "be"),
        ("「She ___ my sister.」应该填？", "is", "am", "are", "be"),
        ("「尺子」的英文是？", "ruler", "pencil", "book", "school"),
        ("「橙子」的英文是？", "orange", "apple", "banana", "grape"),
        ("「在……里面」的英文是？", "in", "on", "under", "near"),
        ("「box」的复数形式是？", "boxes", "boxs", "box", "boxies"),
        ("「他会游泳」的英文是？", "He can swim.", "He can swims.", "He can swimming.", "He cans swim."),
        ("「我有一支铅笔」的英文是？", "I have a pencil.", "I has a pencil.", "I am a pencil.", "I have pencil a."),
        ("「今天天气怎么样？」的英文是？", "How is the weather today?", "What is your name?", "How old are you?", "Where are you?"),
    ],
    4: [
        ("「He ___ to school every day.」应该填？", "goes", "go", "going", "went"),
        ("「There ___ two books on the desk.」应该填？", "are", "is", "am", "be"),
        ("「在……上面」的英文是？", "on", "in", "under", "behind"),
        ("「candy」的复数形式是？", "candies", "candys", "candy", "candyies"),
        ("「我的书包比你的重」的英文是？", "My bag is heavier than yours.", "My bag is heavy than yours.", "My bag is more heavy than yours.", "My bag heavier than yours."),
        ("「他正在读书」的英文是？", "He is reading a book.", "He reads a book.", "He read a book.", "He is read a book."),
        ("「星期三」的英文是？", "Wednesday", "Tuesday", "Thursday", "Monday"),
        ("「厨房」的英文是？", "kitchen", "bedroom", "bathroom", "living room"),
        ("「play」的过去式是？", "played", "plaied", "plays", "playing"),
        ("「你会弹钢琴吗？」的英文是？", "Can you play the piano?", "Do you can play the piano?", "Are you play the piano?", "You can play the piano?"),
    ],
    5: [
        ("「He is ___ football now.」应该填？", "playing", "play", "plays", "played"),
        ("「I ___ my homework yesterday.」应该填？", "did", "do", "does", "doing"),
        ("「go」的过去式是？", "went", "goed", "gone", "going"),
        ("「expensive」的反义词是？", "cheap", "big", "long", "heavy"),
        ("「在……后面」的英文是？", "behind", "between", "beside", "before"),
        ("「我们下周要去北京」的英文是？", "We will go to Beijing next week.", "We go to Beijing next week.", "We went to Beijing next week.", "We are go to Beijing next week."),
        ("「医生」的英文是？", "doctor", "teacher", "farmer", "worker"),
        ("「How much is it?」是在问什么？", "价格", "时间", "数量多少岁", "地点"),
        ("「tall」的比较级是？", "taller", "tallest", "more tall", "talls"),
        ("「我比弟弟高」的英文是？", "I am taller than my brother.", "I am tall than my brother.", "I am more tall than my brother.", "I taller than my brother."),
    ],
    6: [
        ("「They ___ to the park last Sunday.」应该填？", "went", "go", "goes", "going"),
        ("「I have ___ this book for two years.」应该填？", "had", "have", "has", "having"),
        ("「good」的比较级是？", "better", "gooder", "best", "more good"),
        ("「If it ___ tomorrow, we will stay at home.」应该填？", "rains", "rain", "rained", "will rain"),
        ("「他被邀请了」的英文是？", "He was invited.", "He invited.", "He is invite.", "He were invited."),
        ("「dictionary」的意思是？", "字典", "日记", "报纸", "杂志"),
        ("「invent」的意思是？", "发明", "邀请", "介绍", "想象"),
        ("「她每天六点起床」的英文是？", "She gets up at six every day.", "She get up at six every day.", "She getting up at six every day.", "She got up at six every day."),
        ("「世界上最长的河」的英文表达是？", "the longest river in the world", "the long river in the world", "the longer river of the world", "the most long river in the world"),
        ("「因为下雨，我们没去公园」的英文是？", "Because it rained, we didn't go to the park.", "Because it rained, we don't go to the park.", "Because rain, we not go to the park.", "Because it rains, we didn't go to the park."),
    ],
}


# ------------------------------------------------------------------ 数学（程序生成）
def math_questions(grade, count=8):
    """按年级生成计算题：返回 [(题干, 正确, d1, d2, d3)]"""
    out = []
    seen = set()

    def add(q, ans, wrongs):
        if q in seen:
            return False
        seen.add(q)
        out.append((q, str(ans), str(wrongs[0]), str(wrongs[1]), str(wrongs[2])))
        return True

    guard = 0
    while len(out) < count and guard < 500:
        guard += 1
        if grade == 1:
            kind = random.choice(["add", "sub"])
            if kind == "add":
                a, b = random.randint(1, 9), random.randint(1, 9)
                if a + b > 20:
                    continue
                add("%d + %d = ?" % (a, b), a + b, [a + b + 1, a + b - 1, a + b + 2])
            else:
                a = random.randint(5, 20)
                b = random.randint(1, a - 1)
                add("%d - %d = ?" % (a, b), a - b, [a - b + 1, a - b - 1, a - b + 2])
        elif grade == 2:
            kind = random.choice(["add", "sub", "mul"])
            if kind == "add":
                a, b = random.randint(11, 80), random.randint(2, 19)
                if a + b > 100:
                    continue
                add("%d + %d = ?" % (a, b), a + b, [a + b + 1, a + b - 1, a + b + 10])
            elif kind == "sub":
                a, b = random.randint(30, 100), random.randint(2, 29)
                add("%d - %d = ?" % (a, b), a - b, [a - b + 1, a - b - 1, a - b + 10])
            else:
                a, b = random.randint(2, 9), random.randint(2, 9)
                add("%d × %d = ?" % (a, b), a * b, [a * b + a, a * b - a, a * b + 1])
        elif grade == 3:
            kind = random.choice(["mul", "div", "add"])
            if kind == "mul":
                a, b = random.randint(12, 99), random.randint(2, 9)
                add("%d × %d = ?" % (a, b), a * b, [a * b + a, a * b - b, a * b + 10])
            elif kind == "div":
                b = random.randint(2, 9)
                c = random.randint(11, 40)
                add("%d ÷ %d = ?" % (b * c, b), c, [c + 1, c - 1, c + 10])
            else:
                a, b = random.randint(120, 800), random.randint(30, 199)
                add("%d + %d = ?" % (a, b), a + b, [a + b + 10, a + b - 10, a + b + 100])
        elif grade == 4:
            kind = random.choice(["mul", "dec", "div"])
            if kind == "mul":
                a, b = random.randint(101, 999), random.randint(11, 25)
                add("%d × %d = ?" % (a, b), a * b, [a * b + a, a * b - b, a * b + 100])
            elif kind == "dec":
                a, b = round(random.uniform(1, 20), 1), round(random.uniform(1, 9), 1)
                add("%.1f + %.1f = ?" % (a, b), round(a + b, 1),
                    [round(a + b + 0.1, 1), round(a + b - 0.1, 1), round(a + b + 1, 1)])
            else:
                b = random.randint(12, 30)
                c = random.randint(11, 30)
                add("%d ÷ %d = ?" % (b * c, b), c, [c + 1, c - 1, c + 10])
        elif grade == 5:
            kind = random.choice(["decmul", "decdiv", "frac"])
            if kind == "decmul":
                a, b = round(random.uniform(1, 9), 1), random.randint(2, 9)
                add("%.1f × %d = ?" % (a, b), round(a * b, 1),
                    [round(a * b + 0.1, 1), round(a * b - 0.1, 1), round(a * b + 1, 1)])
            elif kind == "decdiv":
                b = random.randint(2, 9)
                c = round(random.uniform(1, 9), 1)
                add("%.1f ÷ %d = ?" % (round(b * c, 1), b), round(c, 1),
                    [round(c + 0.1, 1), round(c - 0.1, 1), round(c + 1, 1)])
            else:
                d = random.choice([3, 4, 5, 6, 7, 8])
                a = random.randint(1, d - 2)
                b = random.randint(1, d - 1 - a)
                add("%d/%d + %d/%d = ?" % (a, d, b, d), "%d/%d" % (a + b, d),
                    ["%d/%d" % (a + b, d * 2), "%d/%d" % (a + b + 1, d), "%d/%d" % (abs(a - b), d)])
        else:
            kind = random.choice(["fracmul", "percent", "ratio"])
            if kind == "fracmul":
                # 只出真分数相乘，避免出现 ×2/2 这种没意义的题
                a, b = _proper_fraction()
                c, d = _proper_fraction()
                num, den = a * c, b * d
                g = _gcd(num, den)
                ans = ("%d/%d" % (num // g, den // g)) if den // g != 1 else "%d" % (num // g)
                w1 = "%d/%d" % (a + c, b + d)
                w2 = "%d/%d" % (num, den * 2)
                w3 = "%d/%d" % (num + 1, den)
                add("%d/%d × %d/%d = ?" % (a, b, c, d), ans, [w1, w2, w3])
            elif kind == "percent":
                n = random.choice([20, 40, 50, 60, 80, 100, 200])
                p = random.choice([5, 10, 20, 25, 50])
                # 只用整除得尽的组合，否则 //100 会静默截断（如 50×25%=12.5 被写成 12）。
                # 调整被乘数而不是重新抽样，保证随机序列不变、其余题目可复现。
                step = 100 // _gcd(p, 100)
                n -= n % step
                ans = n * p // 100
                add("%d 的 %d%% 是多少？" % (n, p), ans,
                    [ans + 1, ans + 10, max(1, ans - 1)])
            else:
                # 比值用分数表示，和小数互不冲突
                a, b = random.randint(1, 9), random.randint(2, 9)
                if a == b:
                    continue
                g = _gcd(a, b)
                ans = "%d/%d" % (a // g, b // g)
                add("%d : %d 的比值是？" % (a, b), ans,
                    ["%d/%d" % (b // g, a // g), "%d/%d" % (a + 1, b), "%d/%d" % (a, b + 1)])
    return out


def _proper_fraction():
    """真分数 a/b，且 a<b、a 不为 0、不等于 1（避免出现 ×1/1 之流）"""
    while True:
        b = random.randint(3, 9)
        a = random.randint(1, b - 1)
        if a < b:
            return a, b


def _gcd(a, b):
    while b:
        a, b = b, a % b
    return a


# 每年级的 2 道概念题
MATH_CONCEPT = {
    1: [("15 里面有 1 个十和几个一？", "5", "1", "15", "10"),
        ("下面哪个数最大？", "18", "9", "12", "7")],
    2: [("1 时等于多少分？", "60 分", "30 分", "100 分", "24 分"),
        ("「3 × 4」表示？", "4 个 3 相加", "3 个 4 相乘", "3 加 4", "4 减 3")],
    3: [("1 米等于多少厘米？", "100 厘米", "10 厘米", "1000 厘米", "60 厘米"),
        ("正方形的周长怎么算？", "边长 × 4", "边长 × 2", "边长 + 4", "边长 × 边长")],
    4: [("一个直角是多少度？", "90°", "45°", "180°", "60°"),
        ("1 千米等于多少米？", "1000 米", "100 米", "10 米", "10000 米")],
    5: [("1 立方米等于多少立方分米？", "1000", "100", "10", "10000"),
        ("下面哪个数是 12 的因数？", "3", "5", "7", "8")],
    6: [("圆的面积公式是？", "πr²", "2πr", "πd", "πr"), ("1 小时 30 分等于多少分？", "90 分", "60 分", "130 分", "150 分")],
}


def build():
    out = []
    for g in range(1, 7):
        # 扩充题追加在原 10 题之后，保持前 10 题的 id 与内容不变
        CHINESE[g] = CHINESE[g] + list(CHINESE_EXTRA[g])
        ENGLISH[g] = ENGLISH[g] + list(ENGLISH_EXTRA[g])
        for q, right, d1, d2, d3 in CHINESE[g]:
            out.append({
                "id": "g%d-chinese-%02d" % (g, sum(1 for x in out if x["subject"] == SUB_CN and x["grade"] == g) + 1),
                "subject": SUB_CN, "grade": g, "q": q,
                "options": [right, d1, d2, d3], "answer": 0,
            })
        for q, right, d1, d2, d3 in ENGLISH[g]:
            out.append({
                "id": "g%d-english-%02d" % (g, sum(1 for x in out if x["subject"] == SUB_EN and x["grade"] == g) + 1),
                "subject": SUB_EN, "grade": g, "q": q,
                "options": [right, d1, d2, d3], "answer": 0,
            })
        math_list = math_questions(g, 8) + list(MATH_CONCEPT[g]) + list(MATH_EXTRA[g])
        for q, right, d1, d2, d3 in math_list:
            out.append({
                "id": "g%d-math-%02d" % (g, sum(1 for x in out if x["subject"] == SUB_MA and x["grade"] == g) + 1),
                "subject": SUB_MA, "grade": g, "q": q,
                "options": [right, d1, d2, d3], "answer": 0,
            })

    # 校验：选项必须 4 个且互不重复，answer 必须指向正确项
    for i, x in enumerate(out):
        assert len(x["options"]) == 4, x
        assert len(set(x["options"])) == 4, "选项重复：" + x["q"] + " " + str(x["options"])
        assert 0 <= x["answer"] < 4
        assert x["grade"] in range(1, 7)

    doc = {
        "version": 3,
        "name": "小学 1-6 年级语数英题库",
        "note": "grade 为年级（1-6）。answer 是正确选项下标（从 0 开始），程序每次出题会自动打乱选项顺序。未写 grade 的题会出现在所有年级。题型覆盖：语文（古诗、拼音、多音字、成语、反义词/近义词、量词）、数学（计算、公式、定义、单位换算、奥数应用）、英语（词义、常用短语、句子翻译、情景交际、语法）。",
        "questions": out,
    }
    return doc


if __name__ == "__main__":
    doc = build()
    path = "app/src/main/assets/questions.json"
    text = json.dumps(doc, ensure_ascii=False, indent=2)
    io.open(path, "w", encoding="utf-8", newline="\n").write(text + "\n")
    from collections import Counter
    print("总题数:", len(doc["questions"]))
    print("按年级:", dict(sorted(Counter(q["grade"] for q in doc["questions"]).items())))
    print("按学科:", dict(Counter(q["subject"] for q in doc["questions"])))
    print("写入:", path)
