package com.family.studytv;

import java.util.regex.Pattern;

/**
 * 题型分类器：把一道题归到家长看得懂的题型上（拼音 / 古诗词 / 计算 / 单词拼写 …）。
 *
 * <p>题库里没有「题型」字段，这里用题干关键词 + 科目推出来。规则按「越具体越先判」的顺序
 * 排列，改题库模板时同步改这里即可。分类结果同时用于：
 * <ul>
 *   <li>{@link StudyStats}：按题型累计答题数与错题数，后台「学习统计」出柱状图 / 雷达图；</li>
 *   <li>电视答题页：答错时提示「这道是古诗题，再读一遍」一类的引导语。</li>
 * </ul>
 */
public final class QType {

    public static final String OTHER = "其他";

    /** 数学「单位换算」判定用的单位词，出现两个以上即认为是换算题 */
    private static final String[] UNITS = {
            "千米", "公里", "分米", "厘米", "毫米", "千克", "毫升",
            "平方米", "平方厘米", "平方分米", "立方米", "小时", "分钟"
    };

    private static final Pattern HAN = Pattern.compile("[\\u4e00-\\u9fa5]");
    /** 单位换算模板：「12 分 = ? 角」「3 立方分米 = ? 立方厘米」 */
    private static final Pattern UNIT_CONV = Pattern.compile(
            "^\\s*[0-9.]+\\s*[\\u4e00-\\u9fa5]{1,4}\\s*=\\s*\\?\\s*[\\u4e00-\\u9fa5]{0,4}\\s*[？?]?\\s*$");
    private QType() {
    }

    public static String of(QuestionBank.Question q) {
        if (q == null) {
            return OTHER;
        }
        return of(q.subject, q.id, q.q);
    }

    public static String of(String subject, String id, String text) {
        String sub = subject == null ? "" : subject.trim();
        String t = text == null ? "" : text;
        String qid = id == null ? "" : id;
        // v1.7.23 起新题在题首用【题型名】显式标注（形近字辨析、诗词鉴赏、
        // 统计与可能性 …）。有标签时直接采用，比关键词推断准确，也让题库
        // 以后加新题型不必再改这套推断规则。
        if (t.startsWith("【")) {
            int end = t.indexOf("】");
            if (end > 1) {
                return t.substring(1, end);
            }
        }
        if ("语文".equals(sub)) {
            return chinese(t, qid);
        }
        if ("数学".equals(sub)) {
            return math(t);
        }
        if ("英语".equals(sub)) {
            return english(t);
        }
        return OTHER;
    }

    private static String chinese(String t, String id) {
        // 复合诗词题（v1.7.21 起）统一是 poem- 前缀，直接归类，不必再猜关键词
        if (id.startsWith("poem-") || has(t, "作者", "朝代", "名句", "下一句", "出自", "选自",
                "诗句", "诗名")) {
            return "古诗词";
        }
        if (has(t, "多音", "中读作")) {
            return "多音字";
        }
        // 「与…读音相同」也是同音字题，要先于下面的「读音 → 拼音」判断
        if (has(t, "读音相同", "同音")) {
            return "同音字";
        }
        if (has(t, "拼音", "读音", "第几声", "声调", "音节", "读作", "读「")) {
            return "拼音";
        }
        if (has(t, "几画", "几笔", "笔画")) {
            return "笔画";
        }
        if (t.contains("部首")) {
            return "部首";
        }
        if (t.contains("近义词")) {
            return "近义词";
        }
        if (t.contains("反义词")) {
            return "反义词";
        }
        if (t.contains("量词")) {
            return "量词";
        }
        if (has(t, "修辞")) {
            return "修辞手法";
        }
        if (has(t, "成语", "意思", "比喻", "解释", "形容", "道理")) {
            return "词语解释";
        }
        return "语文其他";
    }

    private static String math(String t) {
        if (t.contains("换算") || UNIT_CONV.matcher(t).find()
                || (t.contains("= ?") && unitCount(t) >= 2)) {
            return "单位换算";
        }
        if (has(t, "面积", "周长", "体积", "表面积", "公式", "三角形", "梯形", "平行四边形",
                "圆柱", "圆锥", "长方体", "正方体", "正方形", "圆形", "圆")) {
            return "图形与公式";
        }
        if (has(t, "分数", "百分数", "比例", "平均数", "因数", "倍数", "质数", "合数", "数列",
                "规律", "方程", "x =", "x 等于", "利率", "利息", "折扣", "浓度", "最大", "最小",
                "单数", "双数", "个位", "十位", "位数", "化简", "整数比", "比是")) {
            return "数与代数";
        }
        if (HAN.matcher(t).find() && has(t, "多少", "几", "一共", "还剩", "每人", "平均",
                "需要", "可以", "共有", "多长", "多重", "多高")) {
            return "应用题";
        }
        if (!HAN.matcher(t).find()) {
            return "计算";
        }
        return "数学其他";
    }

    private static String english(String t) {
        if (has(t, "拼写", "写法", "开头", "字母")) {
            return "单词拼写";
        }
        if (has(t, "复数", "过去式", "比较级", "最高级", "第三人称", "现在分词", "时态",
                "be 动词", "be动词", "there be", "介词", "冠词", "情态动词", "疑问词",
                "代词", "单数", "句型", "形式", "人称")) {
            return "语法";
        }
        // 「句子…应该翻译成…」既含「应该」也含「句子」，按句子翻译算更贴切
        if (has(t, "翻译", "句子", "哪句英文", "哪句中文")) {
            return "句子翻译";
        }
        if (has(t, "应该", "怎么回答", "礼貌")) {
            return "情景交际";
        }
        if (has(t, "意思", "表示", "用英语怎么", "英文怎么", "对应的英语单词", "英文是",
                "中文是", "哪个单词", "英文句子", "怎么说", "反义词")) {
            return "单词词义";
        }
        return "英语其他";
    }

    private static int unitCount(String t) {
        int n = 0;
        for (String u : UNITS) {
            if (t.contains(u)) {
                n++;
            }
        }
        return n;
    }

    private static boolean has(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
