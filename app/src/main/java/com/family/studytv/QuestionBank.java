package com.family.studytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题库：文件保存在 /data/data/包名/files/questions.json，首次运行从 assets 释放。
 * 网页后台与电视端共用同一份文件，改完立即生效。
 */
public final class QuestionBank {

    private static final String FILE_NAME = "questions.json";
    private static final String BACKUP_NAME = "questions.bak.json";
    private static final String ASSET_NAME = "questions.json";

    private static QuestionBank instance;

    private final File file;
    private final File backup;
    private final Object lock = new Object();

    private List<Question> cache;
    private String rawCache;

    private QuestionBank() {
        Context c = App.get();
        file = new File(c.getFilesDir(), FILE_NAME);
        backup = new File(c.getFilesDir(), BACKUP_NAME);
    }

    public static synchronized QuestionBank get() {
        if (instance == null) {
            instance = new QuestionBank();
        }
        return instance;
    }

    /** 仅供单元测试：丢弃单例与缓存，强制重新从磁盘 / 内置题库加载 */
    static synchronized void resetForTest() {
        if (instance != null) {
            synchronized (instance.lock) {
                instance.cache = null;
                instance.rawCache = null;
            }
        }
        instance = null;
    }

    public File file() {
        return file;
    }

    // ---------------------------------------------------------------- 初始化

    /**
     * 首次运行（或文件被删）时把内置题库释放出来。
     *
     * <p>内置题库升级（例如 v2 开始带年级字段）且家长没有自己改过题库时，
     * 自动把旧题库备份后换成新版；家长改过就绝不动，避免覆盖他自己的题。
     */
    public void ensureSeeded() {
        synchronized (lock) {
            try {
                String asset = readAsset();
                if (asset == null) {
                    return;
                }
                if (file.exists() && file.length() > 32) {
                    if (Prefs.bankCustom()) {
                        return;
                    }
                    String cur = readFile(file);
                    if (versionOf(cur) >= versionOf(asset)) {
                        return;
                    }
                    try {
                        atomicWrite(backup, cur);
                    } catch (Throwable ignored) {
                    }
                    atomicWrite(file, asset);
                    cache = null;
                    rawCache = null;
                    return;
                }
                // 只为确认题库有效就全量 parse，会在首启多构造 18000 个临时对象，
                // 4.7 MB 题库在低端盒子上容易顶到 heap 上限。这里做轻量校验，
                // 写盘后的格式与题量统一交给 reloadLocked() 处理（失败会自动回退到内置题库）。
                if (asset.length() > 32 && asset.contains("\"questions\"")) {
                    atomicWrite(file, asset);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读题库里的 version 字段，读不到按 0 处理 */
    static int versionOf(String json) {
        if (json == null) {
            return 0;
        }
        try {
            String t = json.trim();
            Object root = t.startsWith("[") ? (Object) new JSONArray(t) : (Object) new JSONObject(t);
            if (root instanceof JSONArray) {
                return 0;
            }
            return ((JSONObject) root).optInt("version", 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    private String readAsset() {
        InputStream in = null;
        try {
            in = App.get().getAssets().open(ASSET_NAME);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            close(in);
        }
    }

    // ---------------------------------------------------------------- 读取

    public List<Question> all() {
        synchronized (lock) {
            if (cache == null) {
                reloadLocked();
            }
            return new ArrayList<Question>(cache);
        }
    }

    public int count() {
        return all().size();
    }

    public Map<String, Integer> countBySubject() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        for (Question q : all()) {
            Integer v = map.get(q.subject);
            map.put(q.subject, v == null ? 1 : v + 1);
        }
        return map;
    }

    public String rawJson() {
        synchronized (lock) {
            if (rawCache == null) {
                reloadLocked();
            }
            return rawCache;
        }
    }

    private void reloadLocked() {
        String text = null;
        try {
            if (file.exists()) {
                text = readFile(file);
            }
        } catch (Throwable ignored) {
        }
        if (text == null || text.trim().length() == 0) {
            text = readAsset();
        }
        try {
            cache = parse(text);
            rawCache = text;
        } catch (Throwable e) {
            // 文件损坏：退回内置题库，避免弹窗时无题可出
            String asset = readAsset();
            try {
                cache = parse(asset);
                rawCache = asset;
            } catch (Throwable e2) {
                cache = new ArrayList<Question>();
                rawCache = "{\"questions\":[]}";
            }
        }
    }

    // ---------------------------------------------------------------- 写入

    /** 保存题库（先校验，再原子写入，旧文件自动备份） */
    public void save(String json) throws Exception {
        List<Question> parsed = parse(json);
        if (parsed.isEmpty()) {
            throw new Exception("题库为空，已取消保存");
        }
        synchronized (lock) {
            if (file.exists() && file.length() > 32) {
                try {
                    atomicWrite(backup, readFile(file));
                } catch (Throwable ignored) {
                }
            }
            atomicWrite(file, json);
            cache = parsed;
            rawCache = json;
        }
        // 家长自己改过题库，以后内置题库升级不再覆盖
        Prefs.setBankCustom(true);
    }

    public boolean restoreBackup() {
        synchronized (lock) {
            try {
                if (!backup.exists()) {
                    return false;
                }
                String text = readFile(backup);
                parse(text);
                atomicWrite(file, text);
                Prefs.setBankCustom(true);
                cache = null;
                rawCache = null;
                reloadLocked();
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- 抽题

    /**
     * 按启用学科轮流出题，避免一次弹窗里全是同一科。
     */
    public List<Question> pick(int n) {
        return pick(n, null);
    }

    /**
     * 同上，但尽量避开 excludeIds 里的题目（答错后换一组新题时用）。
     * 剩余题量不够一组时会自动放宽，绝不因为排除导致出不了题。
     */
    public List<Question> pick(int n, Set<String> excludeIds) {
        List<Question> pool = all();
        if (n <= 0) {
            n = 1;
        }
        Set<String> allowed = allowedSubjects();
        Set<Integer> grades = Prefs.gradeSet();

        Map<String, List<Question>> grouped = group(pool, allowed, grades, excludeIds);
        if (totalCount(grouped) < n) {
            // 排除后凑不满一组 → 先放弃排除
            grouped = group(pool, allowed, grades, null);
        }
        if (grouped.isEmpty() && !grades.isEmpty()) {
            // 选中的年级在题库里一道题都没有 → 退到全部年级，绝不因此出不了题
            grouped = group(pool, allowed, emptyGrades(), null);
        }
        if (grouped.isEmpty()) {
            // 学科过滤后也无题 → 退回全库
            grouped = group(pool, emptySet(), emptyGrades(), null);
        }

        List<List<Question>> groups = new ArrayList<List<Question>>(grouped.values());
        for (List<Question> g : groups) {
            Collections.shuffle(g);
        }

        List<Question> out = new ArrayList<Question>();
        int round = 0;
        while (out.size() < n && round < 1000) {
            boolean taken = false;
            for (List<Question> g : groups) {
                if (out.size() >= n) {
                    break;
                }
                if (round < g.size()) {
                    out.add(g.get(round));
                    taken = true;
                }
            }
            if (!taken) {
                break;
            }
            round++;
        }
        return out;
    }

    /** 读取启用的学科（网页后台可改），空集合表示不限制 */
    private static Set<String> allowedSubjects() {
        Set<String> allowed = new LinkedHashSet<String>();
        String sub = Prefs.subjects();
        if (sub != null) {
            for (String s : sub.split("[,，]")) {
                if (s.trim().length() > 0) {
                    allowed.add(s.trim());
                }
            }
        }
        return allowed;
    }

    private static Set<String> emptySet() {
        return new LinkedHashSet<String>();
    }

    private static Set<Integer> emptyGrades() {
        return new LinkedHashSet<Integer>();
    }

    /** 按年级统计题量（0 表示未标年级的通用题） */
    public Map<Integer, Integer> countByGrade() {
        Map<Integer, Integer> map = new LinkedHashMap<Integer, Integer>();
        for (Question q : all()) {
            Integer v = map.get(q.grade);
            map.put(q.grade, v == null ? 1 : v + 1);
        }
        return map;
    }

    /** 年级字段解析：支持 3、"3"、"三年级"、"三年级上"、0/空（未标注） */
    static int parseGrade(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return 0;
        }
        if (v instanceof Number) {
            int g = ((Number) v).intValue();
            return (g >= 1 && g <= 6) ? g : 0;
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0) {
            return 0;
        }
        String[] cn = {"一", "二", "三", "四", "五", "六"};
        for (int i = 0; i < cn.length; i++) {
            if (s.contains(cn[i] + "年级") || s.equals(cn[i])) {
                return i + 1;
            }
        }
        try {
            int g = Integer.parseInt(s.replaceAll("[^0-9]", ""));
            return (g >= 1 && g <= 6) ? g : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 按学科分组；allowed 为空表示不限学科，grades 为空表示不限年级。
     * 未标年级（grade=0）的题视为通用题，任何年级都能出（兼容老题库）。
     */
    private static Map<String, List<Question>> group(List<Question> pool,
                                                     Set<String> allowed,
                                                     Set<Integer> grades,
                                                     Set<String> exclude) {
        Map<String, List<Question>> grouped = new LinkedHashMap<String, List<Question>>();
        for (Question q : pool) {
            if (!allowed.isEmpty() && !allowed.contains(q.subject)) {
                continue;
            }
            if (grades != null && !grades.isEmpty() && q.grade > 0 && !grades.contains(q.grade)) {
                continue;
            }
            if (exclude != null && exclude.contains(q.id)) {
                continue;
            }
            List<Question> l = grouped.get(q.subject);
            if (l == null) {
                l = new ArrayList<Question>();
                grouped.put(q.subject, l);
            }
            l.add(q);
        }
        return grouped;
    }

    private static int totalCount(Map<String, List<Question>> grouped) {
        int t = 0;
        for (List<Question> g : grouped.values()) {
            t += g.size();
        }
        return t;
    }

    // ---------------------------------------------------------------- 解析

    public static List<Question> parse(String json) throws Exception {
        if (json == null || json.trim().length() == 0) {
            throw new Exception("内容为空");
        }
        String t = json.trim();
        Object root;
        try {
            root = t.startsWith("[") ? (Object) new JSONArray(t) : (Object) new JSONObject(t);
        } catch (Exception e) {
            throw new Exception("JSON 格式非法：" + e.getMessage());
        }

        JSONArray arr;
        if (root instanceof JSONArray) {
            arr = (JSONArray) root;
        } else {
            arr = ((JSONObject) root).optJSONArray("questions");
        }
        if (arr == null) {
            throw new Exception("缺少 questions 数组");
        }

        List<Question> out = new ArrayList<Question>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String q = o.optString("q", o.optString("question", "")).trim();
            if (q.length() == 0) {
                throw new Exception("第 " + (i + 1) + " 题缺少题干 q");
            }
            JSONArray opts = o.optJSONArray("options");
            if (opts == null || opts.length() < 2) {
                throw new Exception("第 " + (i + 1) + " 题的 options 至少要有 2 个选项");
            }
            List<String> list = new ArrayList<String>();
            for (int k = 0; k < opts.length(); k++) {
                String s = opts.optString(k, "").trim();
                if (s.length() == 0) {
                    throw new Exception("第 " + (i + 1) + " 题存在空选项");
                }
                list.add(s);
            }

            Object a = o.opt("answer");
            if (a == JSONObject.NULL) {
                a = null;
            }
            int answer = -1;
            if (a instanceof Number) {
                answer = ((Number) a).intValue();
            } else if (a != null) {
                String as = String.valueOf(a).trim();
                try {
                    answer = Integer.parseInt(as);
                } catch (Exception ignore) {
                }
                if (answer < 0 || answer >= list.size()) {
                    int idx = list.indexOf(as);
                    if (idx < 0) {
                        throw new Exception("第 " + (i + 1) + " 题的 answer 无法匹配任何选项：" + as);
                    }
                    answer = idx;
                }
            }
            if (answer < 0 || answer >= list.size()) {
                throw new Exception("第 " + (i + 1) + " 题的 answer 不合法（应为选项下标，从 0 开始）");
            }

            String subject = o.optString("subject", "综合").trim();
            if (subject.length() == 0) {
                subject = "综合";
            }
            int grade = parseGrade(o.opt("grade"));
            String id = o.optString("id", "q" + (i + 1)).trim();
            out.add(new Question(id, subject, grade, q, list, answer));
        }
        if (out.isEmpty()) {
            throw new Exception("没有解析到任何题目");
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static void atomicWrite(File target, String text) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            close(os);
        }
        if (target.exists()) {
            target.delete();
        }
        if (!tmp.renameTo(target)) {
            throw new Exception("写入失败");
        }
    }

    private static String readFile(File f) throws Exception {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 数据类

    public static final class Question {
        public final String id;
        public final String subject;
        /** 年级 1–6；0 表示未标注，任何年级筛选都允许出（兼容老题库） */
        public final int grade;
        public final String q;
        public final List<String> options;
        public final int answer;

        public Question(String id, String subject, int grade, String q, List<String> options, int answer) {
            this.id = id;
            this.subject = subject;
            this.grade = grade;
            this.q = q;
            this.options = Collections.unmodifiableList(new ArrayList<String>(options));
            this.answer = answer;
        }

        public Question(String id, String subject, String q, List<String> options, int answer) {
            this(id, subject, 0, q, options, answer);
        }
    }
}
