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
        assertTrue("要举例", p.contains("【举个例子】"));
        assertTrue("要讲易错点", p.contains("【易错点】"));
        assertTrue("要举一反三", p.contains("【举一反三】"));
        assertTrue("数学题要盯进位退位", p.contains("进位退位"));
        assertTrue("错因段要点名孩子选的那个选项", p.contains("「44」"));
        assertTrue("标准档要有字数区间", p.contains("280–360 字"));
        assertTrue("除举例外六段一段都不能少", p.contains("每一段都必须写出来"));
        assertTrue("段序：结论在最前", p.indexOf("【结论】") < p.indexOf("【拆解】"));
        assertTrue("段序：拆解在其他选项之前",
                p.indexOf("【拆解】") < p.indexOf("【看其他选项】"));
        assertTrue("段序：易错点在举一反三之前",
                p.indexOf("【易错点】") < p.indexOf("【举一反三】"));
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
    public void 简明档六段结构不打折只是更短() {
        String p = AiExplain.buildPrompt("英语", 1, "She is eighteen. 的中文意思是？",
                opts("喝点果汁吧", "她十八岁了"), 1, 0, AiExplain.DETAIL_BRIEF);
        String[] tags = {"【结论】", "【拆解】", "【看其他选项】", "【举个例子】",
                "【易错点】", "【举一反三】"};
        for (String t : tags) {
            assertTrue("简明档也要有 " + t, p.contains(t));
        }
        assertTrue(p.contains("130–170 字"));
        assertTrue(p.contains("「喝点果汁吧」"));
        assertTrue("英语题要盯时态单复数", p.contains("时态"));
    }

    @Test
    public void 详细档字数更长() {
        String p = AiExplain.buildPrompt("数学", 2, "8 ÷ 2 = ?",
                opts("3", "4"), 1, 0, AiExplain.DETAIL_DEEP);
        assertTrue(p.contains("400–500 字"));
        assertTrue(p.contains("【举一反三】"));
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
    public void 简单识记题允许整段跳过举例() {
        String p = AiExplain.buildPrompt("语文", 1, "「木」字有几画？",
                opts("3", "4"), 1, 0);
        assertTrue(p.contains("数笔画"));
        assertTrue(p.contains("认首字母"));
        assertTrue(p.contains("整段省略"));
        assertTrue(p.contains("其余五段照写"));
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
        assertEquals(500, AiExplain.maxTokens(AiExplain.DETAIL_BRIEF));
        assertEquals(1300, AiExplain.maxTokens(AiExplain.DETAIL_STANDARD));
        assertEquals(1800, AiExplain.maxTokens(AiExplain.DETAIL_DEEP));
        assertTrue("详细档不能被标准档的长度腰斩",
                AiExplain.textLimit(AiExplain.DETAIL_DEEP) > AiExplain.textLimit(AiExplain.DETAIL_STANDARD));
        assertEquals(AiExplain.DETAIL_DEEP,
                AiExplain.detailLevel(new JSONObject().put("aiDetail", 3)));
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
    public void 超长讲解按档位截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            sb.append('讲');
        }
        assertEquals(281, AiExplain.clip(sb.toString(), AiExplain.DETAIL_BRIEF).length());
        assertEquals(621, AiExplain.clip(sb.toString()).length());
        assertEquals(1001, AiExplain.clip(sb.toString(), AiExplain.DETAIL_DEEP).length());
        assertTrue(AiExplain.clip(sb.toString()).endsWith("…"));
        assertEquals("短文本原样返回", "三句话讲完", AiExplain.clip("  三句话讲完  "));
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
