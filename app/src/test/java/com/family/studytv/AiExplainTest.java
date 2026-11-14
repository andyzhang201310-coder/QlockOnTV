package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.9.0 AI 错题讲解：配置白名单、Key 打码、提示词、缓存、错误分类。
 *
 * <p>这里守的是三条底线：Key 不落到浏览器、缓存命中不重复花钱、
 * 提示词里必须带着「孩子选了哪个」——少这一项，模型讲出来的就是一篇通用解析。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiExplainTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        sp().edit().clear().apply();
        AiExplain.clearCache(ctx);
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE);
    }

    private static List<String> opts(String... v) {
        return new ArrayList<String>(Arrays.asList(v));
    }

    // ------------------------------------------------------------------ 配置

    @Test
    public void 默认关闭且不预置Key() {
        JSONObject cfg = Rules.get(ctx);
        assertFalse("AI 讲解默认必须关闭：开着就意味着题目会上传", cfg.optBoolean("aiEnabled", true));
        assertEquals("", cfg.optString("aiApiKey", "x"));
        assertTrue("默认 Base URL 应是 OpenAI 兼容地址",
                cfg.optString("aiBaseUrl", "").startsWith("https://"));
        assertEquals("deepseek-chat", cfg.optString("aiModel", ""));
        assertEquals(20, cfg.optInt("aiTimeoutSeconds", 0));
    }

    @Test
    public void 四项齐了才算就绪() throws Exception {
        JSONObject cfg = Rules.defaults();
        assertFalse("默认关闭时不就绪", AiExplain.ready(cfg));

        cfg.put("aiEnabled", true);
        assertFalse("没 Key 不就绪", AiExplain.ready(cfg));

        cfg.put("aiApiKey", "sk-abcdefghijklmn");
        assertTrue("开关 + 地址 + 模型 + Key 齐了才就绪", AiExplain.ready(cfg));

        cfg.put("aiEnabled", false);
        assertFalse("关掉开关立刻不就绪", AiExplain.ready(cfg));
    }

    @Test
    public void BaseUrl只收http并去掉末尾斜杠() throws Exception {
        JSONObject in = Rules.defaults();
        in.put("aiBaseUrl", "https://api.example.com/v1///");
        assertEquals("https://api.example.com/v1",
                Rules.sanitize(in).optString("aiBaseUrl", ""));

        in.put("aiBaseUrl", "ftp://api.example.com");
        assertEquals("协议不对应保留原值",
                Rules.defaults().optString("aiBaseUrl", ""),
                Rules.sanitize(in).optString("aiBaseUrl", ""));

        in.put("aiBaseUrl", "");
        assertEquals("留空不覆盖默认值",
                Rules.defaults().optString("aiBaseUrl", ""),
                Rules.sanitize(in).optString("aiBaseUrl", ""));
    }

    @Test
    public void 超时被夹在5到60秒() throws Exception {
        JSONObject in = Rules.defaults();
        in.put("aiTimeoutSeconds", 1);
        assertEquals(5, Rules.sanitize(in).optInt("aiTimeoutSeconds", 0));
        in.put("aiTimeoutSeconds", 999);
        assertEquals(60, Rules.sanitize(in).optInt("aiTimeoutSeconds", 0));
    }

    // ------------------------------------------------------------------ Key 打码

    @Test
    public void Key发到浏览器前必须打码() {
        String key = "sk-1234567890abcdefghij";
        String masked = AiExplain.mask(key);
        assertEquals("sk-****ghij", masked);
        assertFalse("打码串里不能出现原文中段", masked.contains("1234567890"));
        assertTrue(AiExplain.isMasked(masked));
        assertFalse("真 Key 不该被当成打码串", AiExplain.isMasked(key));
        assertEquals("", AiExplain.mask(""));
        assertEquals("短 Key 整个遮掉", "****", AiExplain.mask("sk-12345"));
    }

    @Test
    public void 打码串回写时沿用原Key() throws Exception {
        JSONObject first = Rules.defaults();
        first.put("aiEnabled", true);
        first.put("aiApiKey", "sk-realkey-0000000000");
        Rules.save(ctx, first);
        assertEquals("sk-realkey-0000000000", Rules.get(ctx).optString("aiApiKey", ""));

        // 模拟后台把打码串原样 POST 回来（家长没动这个输入框）
        JSONObject back = Rules.get(ctx);
        back.put("aiApiKey", AiExplain.mask(back.optString("aiApiKey", "")));
        back.put("aiModel", "qwen-plus");
        Rules.save(ctx, back);

        JSONObject saved = Rules.get(ctx);
        assertEquals("真 Key 不能被打码串覆盖", "sk-realkey-0000000000", saved.optString("aiApiKey", ""));
        assertEquals("同一次保存里的其它字段照常生效", "qwen-plus", saved.optString("aiModel", ""));
    }

    @Test
    public void 换Key时正常覆盖() throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("aiApiKey", "sk-old-1111111111");
        Rules.save(ctx, cfg);

        JSONObject next = Rules.get(ctx);
        next.put("aiApiKey", "sk-new-2222222222");
        Rules.save(ctx, next);
        assertEquals("sk-new-2222222222", Rules.get(ctx).optString("aiApiKey", ""));
    }

    // ------------------------------------------------------------------ 提示词

    @Test
    public void 提示词带上孩子的错误选项() {
        String p = AiExplain.buildPrompt("数学", 3, "12 × 4 = ?",
                opts("44", "48", "52", "56"), 1, 0);
        assertTrue("要有年级与科目", p.contains("3年级数学"));
        assertTrue("要以科任老师身份讲", p.contains("数学科任老师"));
        assertTrue("数学课按数量关系→分步演算讲", p.contains("数量关系"));
        assertTrue("不能写成通用 AI 助手", p.contains("不要写成通用 AI 助手"));
        assertTrue("要有题干", p.contains("12 × 4 = ?"));
        assertTrue("正确答案要标出来", p.contains("正确答案：B. 48"));
        assertTrue("必须告诉模型孩子选了什么", p.contains("孩子选了：A. 44"));
        assertTrue("结论段不许复述题目", p.contains("禁止把题目复述一遍"));
        assertTrue("要逐词拆开讲", p.contains("【拆解】"));
        assertTrue("要逐个分析其余选项", p.contains("【看其他选项】"));
        assertTrue("要讲易错点并收尾", p.contains("【易错点】"));
        assertFalse("已取消举例段", p.contains("换掉数字或词，举一个同类型的例子"));
        assertFalse("已取消举一反三段", p.contains("给一条下次也能用的规律"));
        assertTrue("明确禁止再加这两段", p.contains("不许再加【举个例子】【举一反三】"));
        assertTrue("数学题要盯进位退位", p.contains("进位退位"));
        assertTrue("错因段要点名孩子选的那个选项", p.contains("「44」"));
        assertTrue("标准档要有字数区间", p.contains("200–280 字"));
        assertTrue("四段一段都不能少", p.contains("四段都必须写出来"));
        assertTrue("段序：结论在最前", p.indexOf("【结论】") < p.indexOf("【拆解】"));
        assertTrue("段序：拆解在其他选项之前",
                p.indexOf("【拆解】") < p.indexOf("【看其他选项】"));
        assertTrue("段序：易错点收尾",
                p.indexOf("【看其他选项】") < p.indexOf("【易错点】"));
    }

    @Test
    public void 没选错项时不出现孩子选了这行() {
        String p = AiExplain.buildPrompt("语文", 2, "「明」的部首是什么？",
                opts("日", "月", "目"), 0, 0);
        assertTrue(p.contains("正确答案：A. 日"));
        assertFalse("选对了就不该有「孩子选了」这一行", p.contains("孩子选了"));
    }

    @Test
    public void 未标年级时不说几年级() {
        String p = AiExplain.buildPrompt("英语", 0, "apple 是什么意思？",
                opts("苹果", "香蕉"), 0, 1);
        assertTrue(p.contains("小学英语老师"));
        assertFalse(p.contains("0年级"));
    }

    @Test
    public void 选项字母超过26个不越界() {
        assertEquals("A", AiExplain.letter(0));
        assertEquals("D", AiExplain.letter(3));
        assertEquals("?", AiExplain.letter(30));
        assertEquals("?", AiExplain.letter(-1));
    }

    @Test
    public void 简明档四段结构不打折只是更短() {
        String p = AiExplain.buildPrompt("英语", 1, "She is eighteen. 的中文意思是？",
                opts("喝点果汁吧", "她十八岁了"), 1, 0, AiExplain.DETAIL_BRIEF);
        String[] tags = {"【结论】", "【拆解】", "【看其他选项】", "【易错点】"};
        for (String t : tags) {
            assertTrue("简明档也要有 " + t, p.contains(t));
        }
        assertTrue(p.contains("90–130 字"));
        assertTrue(p.contains("「喝点果汁吧」"));
        assertTrue("英语题要盯时态单复数", p.contains("时态"));
        assertTrue("讲完易错点立刻收尾", p.contains("讲完【易错点】立刻结束"));
    }

    @Test
    public void 详细档字数更长() {
        String p = AiExplain.buildPrompt("数学", 2, "8 ÷ 2 = ?",
                opts("3", "4"), 1, 0, AiExplain.DETAIL_DEEP);
        assertTrue(p.contains("320–420 字"));
        assertTrue(p.contains("【易错点】"));
        assertFalse(p.contains("给一条下次也能用的规律"));
    }

    @Test
    public void 选对时看其他选项不点名孩子选项() {
        String p = AiExplain.buildPrompt("语文", 2, "「明」的部首是什么？",
                opts("日", "月"), 0, 0);
        assertTrue(p.contains("【看其他选项】"));
        assertFalse(p.contains("孩子选了"));
        assertFalse(p.contains("要重点讲"));
    }

    @Test
    public void 简单识记题也不再加举例和举一反三() {
        String p = AiExplain.buildPrompt("语文", 1, "「木」字有几画？",
                opts("3", "4"), 1, 0);
        assertTrue("四段照写", p.contains("四段都必须写出来"));
        assertFalse(p.contains("数笔画、认首字母、背作者朝代这类"));
        assertFalse(p.contains("这一段整段省略"));
        assertTrue(p.contains("不许再加【举个例子】【举一反三】"));
    }

    @Test
    public void 英语题易错点按学科给提示() {
        String p = AiExplain.buildPrompt("英语", 3, "He ___ a boy.",
                opts("is", "are"), 0, 1);
        assertTrue(p.contains("单复数"));
        assertTrue(p.contains("介词"));
        assertTrue(p.contains("He is a boy."));
        assertTrue("英语课按认单词→拼句子→语法讲", p.contains("先带学生认单词"));
        assertTrue(p.contains("不要自称 AI"));
        assertFalse("英语题不该给数学的进位提示", p.contains("进位退位"));
    }

    @Test
    public void 以对应学科科任老师身份讲题() {
        String p = AiExplain.buildPrompt("语文", 0, "「春眠不觉晓」的作者是谁？",
                opts("孟浩然", "李白"), 0, 1);
        assertTrue(p.contains("小学语文老师"));
        assertTrue(p.contains("语文科任老师"));
        assertTrue("语文课按字词→句意→作者朝代讲", p.contains("先讲字音字形字义"));
        assertTrue(p.contains("正在教室里"));
        assertFalse(p.contains("0年级"));
    }

    @Test
    public void 非法档位回落标准档() {
        assertEquals(AiExplain.DETAIL_STANDARD, AiExplain.detailLevelValue(0));
        assertEquals(AiExplain.DETAIL_STANDARD, AiExplain.detailLevelValue(9));
        assertEquals(AiExplain.DETAIL_STANDARD, AiExplain.detailLevel(null));
        assertEquals(AiExplain.DETAIL_DEEP, AiExplain.detailLevelValue(AiExplain.DETAIL_DEEP));
    }

    @Test
    public void 按档位放大token与截断上限() throws Exception {
        assertEquals(900, AiExplain.maxTokens(AiExplain.DETAIL_BRIEF));
        assertEquals(2400, AiExplain.maxTokens(AiExplain.DETAIL_STANDARD));
        assertEquals(3200, AiExplain.maxTokens(AiExplain.DETAIL_DEEP));
        assertTrue("标准档 token 要远大于目标字数，不能再把四段腰斩",
                AiExplain.maxTokens(AiExplain.DETAIL_STANDARD) > 6 * 280);
        assertTrue("详细档不能被标准档的长度腰斩",
                AiExplain.textLimit(AiExplain.DETAIL_DEEP) > AiExplain.textLimit(AiExplain.DETAIL_STANDARD));
        assertEquals(AiExplain.DETAIL_DEEP,
                AiExplain.detailLevel(new JSONObject().put("aiDetail", 3)));
    }

    @Test
    public void 提示词禁止读数字和复述题干() {
        String p = AiExplain.buildPrompt("数学", 3, "487 - 199 = ?",
                opts("278", "288"), 1, 0);
        assertTrue("数学【拆解】要直接讲演算", p.contains("直接讲数量关系与分步演算"));
        assertTrue(p.contains("禁止把题干里的数字用中文读一遍"));
        assertTrue(p.contains("487 是四百八十七"));
        assertTrue("全局禁令：不写废话", p.contains("所有段落一律不写废话"));
        assertTrue("不许把题目原文再抄一遍", p.contains("不把题目或选项原文再抄一遍"));
        assertTrue("要求每段收在完整句上", p.contains("【易错点】必须写完并以句号结束"));
        assertTrue("不许停在半句", p.contains("不要停在"));
        assertTrue("四段齐全优先", p.contains("先保证四段齐全"));
    }

    // ------------------------------------------------------------------ 响应解析

    @Test
    public void 解析OpenAI兼容响应() {
        String raw = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"  因为 4 个 12 加起来正好是 48。  \"}}]}";
        assertEquals("因为 4 个 12 加起来正好是 48。", AiExplain.pickContent(raw));
    }

    @Test
    public void 空响应不崩() {
        assertEquals("", AiExplain.pickContent("{}"));
        assertEquals("", AiExplain.pickContent("{\"choices\":[]}"));
        assertEquals("", AiExplain.pickContent("这不是 JSON"));
        assertEquals("", AiExplain.pickContent(null));
    }

    @Test
    public void 超长讲解按完整句截断且不留省略号() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append('讲');
            if (i % 40 == 39) {
                sb.append('。');
            }
        }
        String raw = sb.toString();
        String brief = AiExplain.clip(raw, AiExplain.DETAIL_BRIEF);
        String std = AiExplain.clip(raw);
        String deep = AiExplain.clip(raw, AiExplain.DETAIL_DEEP);
        assertTrue("简明档要短于标准档：" + brief.length(), brief.length() < std.length());
        assertTrue("详细档要长于标准档：" + deep.length(), deep.length() > std.length());
        assertTrue("简明档不得超过上限", brief.length() <= AiExplain.textLimit(AiExplain.DETAIL_BRIEF));
        assertTrue("标准档不得超过上限", std.length() <= AiExplain.textLimit(AiExplain.DETAIL_STANDARD));
        assertTrue("截断处必须落在句号上，别把孩子留在半句话", std.endsWith("。"));
        assertFalse("不再追加省略号", std.endsWith("…"));
        assertEquals("短文本原样返回", "三句话讲完", AiExplain.clip("  三句话讲完  "));
    }

    @Test
    public void 正常四段讲解不会被本地截断() {
        String text = "【结论】正确答案是 D，288。\n【拆解】减 199 可以先减 200 再补回 1。\n"
                + "【看其他选项】A 少减了 1。\n【易错点】多减了要加回来。";
        assertEquals(text, AiExplain.clip(text));
    }

    @Test
    public void 已取消的两段会被清洗掉且保留其余四段() {
        String old = "【结论】正确答案是 D，288。\n【拆解】减 199 可以先减 200。\n"
                + "【看其他选项】A 少减了 1。\n【举个例子】623 减 198 等于 425。\n"
                + "【易错点】多减了要加回来。\n【举一反三】变式：712 减 297。";
        String tidy = AiExplain.tidy(old);
        for (String tag : new String[]{"【结论】", "【拆解】", "【看其他选项】", "【易错点】"}) {
            assertTrue("保留 " + tag + "：" + tidy, tidy.contains(tag));
        }
        assertFalse("举例段已取消：" + tidy, tidy.contains("623 减 198"));
        assertFalse("举一反三段已取消：" + tidy, tidy.contains("712 减 297"));
        assertTrue(tidy.endsWith("多减了要加回来。"));
        assertEquals("清洗幂等", tidy, AiExplain.tidy(tidy));
    }

    @Test
    public void 拆解里的读数废话会被清掉() {
        String raw = "【拆解】487 是四百八十七，199 是一百九十九。减 199，可以先减 200，再补回 1。";
        String tidy = AiExplain.tidy(raw);
        assertFalse("不能再出现把数字读成中文：" + tidy, tidy.contains("四百八十七"));
        assertFalse(tidy.contains("一百九十九"));
        assertTrue("演算内容必须保留", tidy.contains("可以先减 200"));
        assertTrue(tidy.startsWith("【拆解】减 199"));
        assertEquals("清洗幂等", tidy, AiExplain.tidy(tidy));
        assertFalse("不能留下孤立句号或连续标点", tidy.contains("【拆解】。"));
    }

    @Test
    public void 清洗不误伤有效公式与讲解() {
        String raw = "【拆解】$\\frac{1}{2}$ 是二分之一，287 加 1 等于 288。";
        String tidy = AiExplain.tidy(raw);
        assertTrue(tidy.contains("$\\frac{1}{2}$ 是二分之一"));
        assertTrue(tidy.contains("287 加 1 等于 288"));
    }

    @Test
    public void 旧缓存读出来也会清掉读数废话() {
        AiExplain.putCache(ctx, "q-filler", "【拆解】487 是四百八十七。减 199 可以先减 200。");
        String hit = AiExplain.cached(ctx, "q-filler");
        assertNotNull(hit);
        assertFalse("旧缓存不该再把废话显示出来：" + hit, hit.contains("四百八十七"));
        assertTrue(hit.contains("可以先减 200"));
    }

    @Test
    public void 识别模型侧token腰斩并去掉半句() {
        String cut = "{\"choices\":[{\"finish_reason\":\"length\","
                + "\"message\":{\"content\":\"【易错点】口诀是减整百，看差几，多减了就\"}}]}";
        String ok = "{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"content\":\"【易错点】口诀是减整百，看差几，多减了就加回来。\"}}]}";
        assertTrue(AiExplain.lengthCut(cut));
        assertFalse(AiExplain.lengthCut(ok));
        assertFalse(AiExplain.lengthCut("{}"));
        assertFalse(AiExplain.lengthCut(null));
        assertEquals("【易错点】口诀是减整百，看差几，多减了就加回来。", AiExplain.pickContent(ok));
        assertEquals("没写完的最后一段整段丢掉", "【结论】正确答案是 D，288。",
                AiExplain.dropHalfSentence("【结论】正确答案是 D，288。\n"
                        + "【易错点】口诀是减整百，看差几，多减了就"));
        assertEquals("完整的句子原样保留", "口诀是减整百，看差几，多减了就加回来。",
                AiExplain.dropHalfSentence("口诀是减整百，看差几，多减了就加回来。"));
    }

    @Test
    public void 错误码翻译成人话() {
        assertTrue(AiExplain.httpError(401, "").contains("Key 无效"));
        assertTrue(AiExplain.httpError(404, "").contains("/v1"));
        assertTrue(AiExplain.httpError(429, "").contains("频繁"));
        assertTrue("服务端给的细节要带上，便于排查",
                AiExplain.httpError(400, "model not found").contains("model not found"));
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    public void 缓存命中与清空() {
        assertNull("一开始没有缓存", AiExplain.cached(ctx, "q-1"));

        AiExplain.putCache(ctx, "q-1", "因为 4×12=48。");
        assertEquals("因为 4×12=48。", AiExplain.cached(ctx, "q-1"));
        assertEquals(1, AiExplain.cacheSize(ctx));

        AiExplain.putCache(ctx, "q-2", "另一道题。");
        assertEquals(2, AiExplain.cacheSize(ctx));
        assertEquals("另一道题。", AiExplain.cached(ctx, "q-2"));

        AiExplain.clearCache(ctx);
        assertNull("清空后不再命中", AiExplain.cached(ctx, "q-1"));
        assertEquals(0, AiExplain.cacheSize(ctx));
    }

    @Test
    public void 空题号与空解析不入缓存() {
        AiExplain.putCache(ctx, "", "x");
        AiExplain.putCache(ctx, "q-9", "");
        assertEquals(0, AiExplain.cacheSize(ctx));
        assertNull(AiExplain.cached(ctx, null));
    }

    @Test
    public void 缓存能扛住损坏文件() throws Exception {
        java.io.File f = new java.io.File(ctx.getFilesDir(), "ai_cache.json");
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        out.write("{ 这不是合法 JSON".getBytes("UTF-8"));
        out.close();
        assertNull("文件坏了就当没缓存，不能把答题页拖崩", AiExplain.cached(ctx, "q-1"));
        AiExplain.putCache(ctx, "q-1", "重建一份");
        assertEquals("重建一份", AiExplain.cached(ctx, "q-1"));
    }
}
