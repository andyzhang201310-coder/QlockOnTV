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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                    String cur = readFile(file);
                    int assetVer = versionOf(asset);
                    if (versionOf(cur) >= assetVer) {
                        return;
                    }
                    if (Prefs.bankCustom()) {
                        // 家长改过题库：整库覆盖会丢他自己的题，但也不能让内置题的
                        // 排版修复永远到不了设备。只按 id 迁移「确认为官方旧版题面」的字段。
                        Migration m = migrateMathText(cur, asset);
                        if (m == null || m.json == null) {
                            return;
                        }
                        try {
                            atomicWrite(backup, cur);
                        } catch (Throwable ignored) {
                        }
                        atomicWrite(file, m.json);
                        cache = null;
                        rawCache = null;
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

    // ---- 自定义题库的题面迁移（v1.10.1）----------------------------------

    /** migrateMathText 的结果：json 为 null 表示不该动设备文件 */
    static final class Migration {
        String json;
        int changed;
        boolean pristine;
    }

    /** 新版内置题库里只用了这两种公式：$\frac{a}{b}$ 与 $x^{2}$/$x^{3}$。 */
    private static final Pattern RE_VERSION =
            Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    /** 只扫文件头，避免每次 /api/state 都全量 parse 4.5 MB 题库。 */
    public int version() {
        synchronized (lock) {
            try {
                String s = rawJson();
                if (s == null) {
                    return 0;
                }
                Matcher m = RE_VERSION.matcher(s.substring(0, Math.min(256, s.length())));
                return m.find() ? Integer.parseInt(m.group(1)) : 0;
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    private static final Pattern RE_LEGACY_FRAC =
            Pattern.compile("\\$\\\\frac\\{([^{}]+)\\}\\{([^{}]+)\\}\\$");
    private static final Pattern RE_LEGACY_SUP =
            Pattern.compile("\\$([^{}$]+)\\^\\{([23])\\}\\$");

    /** 把新版 LaTeX 题面降级回 v7 的斜杠写法，用来判断设备上的题是不是官方原文。 */
    static String toLegacy(String latex) {
        if (latex == null || latex.indexOf('$') < 0) {
            return latex;
        }
        String s = RE_LEGACY_FRAC.matcher(latex).replaceAll("$1/$2");
        Matcher m = RE_LEGACY_SUP.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String sup = "2".equals(m.group(2)) ? "\u00b2" : "\u00b3";
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + sup));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean sameMeta(JSONObject a, JSONObject b) {
        if (a.length() != b.length()) {
            return false;
        }
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            if ("q".equals(k) || "options".equals(k)) {
                continue;
            }
            Object x = a.opt(k);
            Object y = b.opt(k);
            if (x == null ? y != null : !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 家长改过题库时的升级路径：不整库覆盖，只把「设备题面 == 新版题面的旧式降级文本」
     * 的字段换成 LaTeX 版。家长自己加的题、改过的题一律保持原样。
     * 若整份题库其实就是官方旧版（没有任何自定义痕迹），直接用内置题库原文覆盖，
     * 顺带把 name/note/version 也对齐，且不丢任何东西。
     */
    static Migration migrateMathText(String cur, String asset) {
        Migration r = new Migration();
        try {
            JSONObject curDoc = new JSONObject(cur);
            JSONObject assetDoc = new JSONObject(asset);
            JSONArray curQs = curDoc.optJSONArray("questions");
            JSONArray assetQs = assetDoc.optJSONArray("questions");
            if (curQs == null || assetQs == null) {
                return r;
            }
            Map<String, JSONObject> freshById = new HashMap<String, JSONObject>();
            for (int i = 0; i < assetQs.length(); i++) {
                JSONObject q = assetQs.optJSONObject(i);
                if (q != null) {
                    String id = q.optString("id", "");
                    if (id.length() > 0) {
                        freshById.put(id, q);
                    }
                }
            }
            r.pristine = curQs.length() == assetQs.length();
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < curQs.length(); i++) {
                JSONObject q = curQs.optJSONObject(i);
                if (q == null) {
                    r.pristine = false;
                    continue;
                }
                String id = q.optString("id", "");
                seen.add(id);
                JSONObject fresh = freshById.get(id);
                if (fresh == null) {
                    r.pristine = false;      // 家长自己加的题
                    continue;
                }
                if (!sameMeta(q, fresh)) {
                    r.pristine = false;
                }
                String src = q.optString("q", null);
                String dst = fresh.optString("q", null);
                if (src == null || dst == null) {
                    r.pristine = false;
                } else if (src.equals(dst)) {
                    // 已经是新版题面
                } else if (src.equals(toLegacy(dst))) {
                    q.put("q", dst);
                    r.changed++;
                } else {
                    r.pristine = false;      // 家长改过题干，保留
                }
                JSONArray so = q.optJSONArray("options");
                JSONArray fo = fresh.optJSONArray("options");
                if (so == null || fo == null || so.length() != fo.length()) {
                    r.pristine = false;
                    continue;
                }
                for (int j = 0; j < so.length(); j++) {
                    String a = so.optString(j, null);
                    String b = fo.optString(j, null);
                    if (a == null || b == null) {
                        r.pristine = false;
                    } else if (a.equals(b)) {
                        // 已经是新版选项
                    } else if (a.equals(toLegacy(b))) {
                        so.put(j, b);
                        r.changed++;
                    } else {
                        r.pristine = false;  // 家长改过选项，保留
                    }
                }
            }
            if (seen.size() != freshById.size()) {
                r.pristine = false;
            }
            if (r.pristine) {
                r.json = asset;           // 与官方旧版完全一致，直接对齐内置题库
                return r;
            }
            curDoc.put("version", assetDoc.optInt("version", 0));
            r.json = curDoc.toString();  // 有自定义内容，只写回迁移过的题面
            return r;
        } catch (Throwable t) {
            // 解析失败或内存不足时宁可不动，绝不能写坏家长的题库
            r.json = null;
            return r;
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

    /** 按「科目 · 年级」统计，例：语文 · 3年级 → 1133 */
    public Map<String, Integer> countBySubjectGrade() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        for (Question q : all()) {
            String key = q.label();
            Integer v = map.get(key);
            map.put(key, v == null ? 1 : v + 1);
        }
        return map;
    }

    /** 按难度星级统计（1–5 星） */
    public Map<Integer, Integer> countByDifficulty() {
        Map<Integer, Integer> map = new LinkedHashMap<Integer, Integer>();
        for (int i = 1; i <= 5; i++) {
            map.put(i, 0);
        }
        for (Question q : all()) {
            Integer v = map.get(q.difficulty);
            map.put(q.difficulty, v == null ? 1 : v + 1);
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

    // ---------------------------------------------------------------- 单题增删改（v1.7.20）
    //
    // 后台不再把整份 JSON 灌进页面（5 MB 解析 + 渲染要好几秒），改成按页取、按题存：
    // 每次只改动 JSON 里的一颗子树，再整份原子写回，旧文件先进备份位。

    /** 题库文件大小（字节） */
    public long size() {
        synchronized (lock) {
            return file.exists() ? file.length() : 0;
        }
    }

    public long modifiedAt() {
        synchronized (lock) {
            return file.exists() ? file.lastModified() : 0;
        }
    }

    /** 手工备份目录 files/bank-backups */
    public File backupDir() {
        File d = new File(App.get().getFilesDir(), "bank-backups");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /**
     * 分页查询。
     *
     * @param subject 科目，空字符串表示不限
     * @param grade   -1 不限年级；0 只看未标年级；1–6 指定年级
     * @param keyword 题干 / id 模糊匹配，空表示不筛
     */
    public List<Question> query(String subject, int grade, String keyword, int offset, int limit) {
        return query(subject, grade, -1, keyword, offset, limit);
    }

    public int queryCount(String subject, int grade, String keyword) {
        return queryCount(subject, grade, -1, keyword);
    }

    /**
     * 分页查询（带难度星级）。
     *
     * @param star -1 不限星级；1–5 只看该星级的题
     */
    public List<Question> query(String subject, int grade, int star, String keyword,
                                int offset, int limit) {
        List<Question> hits = filter(subject, grade, star, keyword);
        int from = Math.max(0, offset);
        if (from >= hits.size()) {
            return new ArrayList<Question>();
        }
        int to = limit <= 0 ? hits.size() : Math.min(hits.size(), from + limit);
        return new ArrayList<Question>(hits.subList(from, to));
    }

    public int queryCount(String subject, int grade, int star, String keyword) {
        return filter(subject, grade, star, keyword).size();
    }

    private List<Question> filter(String subject, int grade, int star, String keyword) {
        String sub = subject == null ? "" : subject.trim();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.US);
        List<Question> out = new ArrayList<Question>();
        for (Question q : all()) {
            if (sub.length() > 0 && !sub.equals(q.subject)) {
                continue;
            }
            if (grade >= 0 && q.grade != grade) {
                continue;
            }
            if (star >= 1 && q.difficulty != star) {
                continue;
            }
            if (kw.length() > 0) {
                String qq = q.q == null ? "" : q.q.toLowerCase(Locale.US);
                String id = q.id == null ? "" : q.id.toLowerCase(Locale.US);
                if (!qq.contains(kw) && !id.contains(kw)) {
                    continue;
                }
            }
            out.add(q);
        }
        return out;
    }

    /** 按 id 取题，取不到返回 null */
    public Question byId(String id) {
        if (id == null || id.trim().length() == 0) {
            return null;
        }
        String want = id.trim();
        for (Question q : all()) {
            if (want.equals(q.id)) {
                return q;
            }
        }
        return null;
    }

    /**
     * 新增或修改一道题（id 已存在则替换）。返回落盘后的题目。
     * 题库不允许被改成空，改完仍是整份原子写入，旧文件自动进备份位。
     */
    public Question saveOne(String id, String subject, int grade, String q,
                            List<String> options, int answer, int index) throws Exception {
        return saveOne(id, subject, grade, q, options, answer, -1, index);
    }

    /**
     * 同上，带难度星级（1–5）。difficulty 传 0 / 负数表示按年级推默认值。
     */
    public Question saveOne(String id, String subject, int grade, String q,
                            List<String> options, int answer, int difficulty, int index)
            throws Exception {
        final String sub = subject == null ? "" : subject.trim();
        final String text = q == null ? "" : q.trim();
        if (sub.length() == 0) {
            throw new Exception("请填写科目");
        }
        if (text.length() == 0) {
            throw new Exception("请填写题干");
        }
        if (options == null || options.size() < 2) {
            throw new Exception("至少要有 2 个选项");
        }
        List<String> opts = new ArrayList<String>();
        for (int i = 0; i < options.size(); i++) {
            String s = options.get(i) == null ? "" : options.get(i).trim();
            if (s.length() == 0) {
                throw new Exception("第 " + (i + 1) + " 个选项为空");
            }
            opts.add(s);
        }
        if (answer < 0 || answer >= opts.size()) {
            throw new Exception("请选择正确答案");
        }
        if (grade < 0 || grade > 6) {
            throw new Exception("年级只能是 1–6");
        }
        final int star = difficulty >= 1 ? clampDifficulty(difficulty) : defaultDifficulty(grade);

        synchronized (lock) {
            JSONObject root = rootLocked();
            JSONArray arr = root.optJSONArray("questions");
            if (arr == null) {
                arr = new JSONArray();
                root.put("questions", arr);
            }
            String useId = id == null ? "" : id.trim();
            int idx = indexOf(arr, useId, index);
            if (useId.length() == 0 || useId.charAt(0) == '#') {
                useId = newId(arr, sub, grade);
            }

            JSONObject item = new JSONObject();
            item.put("id", useId);
            item.put("subject", sub);
            if (grade > 0) {
                item.put("grade", grade);
            }
            item.put("difficulty", star);
            item.put("q", text);
            JSONArray oa = new JSONArray();
            for (String s : opts) {
                oa.put(s);
            }
            item.put("options", oa);
            item.put("answer", answer);

            if (idx >= 0) {
                arr.put(idx, item);
            } else {
                arr.put(item);
            }
            writeRootLocked(root);
            return new Question(useId, sub, grade, text, opts, answer, star);
        }
    }

    /** 删除一道题；返回是否真的删掉了 */
    public boolean deleteOne(String id, int index) throws Exception {
        synchronized (lock) {
            JSONObject root = rootLocked();
            JSONArray arr = root.optJSONArray("questions");
            if (arr == null) {
                return false;
            }
            int idx = indexOf(arr, id == null ? "" : id.trim(), index);
            if (idx < 0) {
                return false;
            }
            if (arr.length() <= 1) {
                throw new Exception("这是题库里最后一道题，删掉就没题可出了");
            }
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i != idx) {
                    next.put(arr.get(i));
                }
            }
            root.put("questions", next);
            writeRootLocked(root);
            return true;
        }
    }

    /** 导出整份题库（备份下载用） */
    public String exportJson() {
        return rawJson();
    }

    // ---------------- 备份 ----------------

    /** 立即做一份带时间的备份，返回该备份的信息 */
    public JSONObject backupNow() throws Exception {
        synchronized (lock) {
            if (!file.exists() || file.length() < 32) {
                throw new Exception("题库文件不存在，无法备份");
            }
            String text = readFile(file);
            String name = "bank-" + stamp() + ".json";
            atomicWrite(new File(backupDir(), name), text);
            pruneBackups(8);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("size", text.getBytes("UTF-8").length);
            o.put("count", count());
            o.put("at", System.currentTimeMillis());
            return o;
        }
    }

    /** 备份列表（新的在前） */
    public JSONArray backups() {
        JSONArray out = new JSONArray();
        File[] fs = backupDir().listFiles();
        if (fs == null) {
            return out;
        }
        List<File> list = new ArrayList<File>();
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith("bank-") && n.endsWith(".json")) {
                list.add(f);
            }
        }
        Collections.sort(list, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("at", f.lastModified());
            } catch (Throwable ignored) {
            }
            out.put(o);
        }
        return out;
    }

    /** 用某份手工备份覆盖当前题库 */
    public void restoreBackupFile(String name) throws Exception {
        File f = safeBackupFile(name);
        if (f == null || !f.exists()) {
            throw new Exception("备份不存在");
        }
        String text = readFile(f);
        parse(text);
        synchronized (lock) {
            if (file.exists()) {
                try {
                    atomicWrite(backup, readFile(file));
                } catch (Throwable ignored) {
                }
            }
            atomicWrite(file, text);
            cache = null;
            rawCache = null;
            reloadLocked();
        }
        Prefs.setBankCustom(true);
    }

    public boolean deleteBackupFile(String name) {
        File f = safeBackupFile(name);
        return f != null && f.exists() && f.delete();
    }

    /** 用内置题库覆盖当前题库（内置题库带年级，可用来修「年级丢失」） */
    public int restoreBuiltin() throws Exception {
        String asset = readAsset();
        if (asset == null) {
            throw new Exception("内置题库不可用");
        }
        List<Question> parsed = parse(asset);
        synchronized (lock) {
            if (file.exists()) {
                try {
                    String cur = readFile(file);
                    atomicWrite(backup, cur);
                    atomicWrite(new File(backupDir(), "bank-" + stamp() + ".json"), cur);
                    pruneBackups(8);
                } catch (Throwable ignored) {
                }
            }
            atomicWrite(file, asset);
            cache = parsed;
            rawCache = asset;
        }
        // 换回内置题库后，以后内置题库升级可以继续自动跟上
        Prefs.setBankCustom(false);
        return parsed.size();
    }

    private File safeBackupFile(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (!n.startsWith("bank-") || !n.endsWith(".json") || n.length() > 64) {
            return null;
        }
        if (!n.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return new File(backupDir(), n);
    }

    private void pruneBackups(int keep) {
        JSONArray all = backups();
        for (int i = keep; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null) {
                File f = safeBackupFile(o.optString("name", ""));
                if (f != null && f.exists()) {
                    f.delete();
                }
            }
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    // ---------------- 内部工具 ----------------

    /** 取整份 JSON 根对象（数组形式的老题库自动包成 {"questions":[...]}） */
    private JSONObject rootLocked() throws Exception {
        String text = rawJson();
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) {
            throw new Exception("题库为空");
        }
        if (t.charAt(0) == '[') {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("questions", new JSONArray(t));
            return o;
        }
        return new JSONObject(t);
    }

    /** 写回整份题库：旧文件先备份，再原子写入，缓存同步刷新 */
    private void writeRootLocked(JSONObject root) throws Exception {
        JSONArray arr = root.optJSONArray("questions");
        root.put("count", arr == null ? 0 : arr.length());
        String json = root.toString();
        if (file.exists() && file.length() > 32) {
            try {
                atomicWrite(backup, readFile(file));
            } catch (Throwable ignored) {
            }
        }
        atomicWrite(file, json);
        rawCache = json;
        try {
            cache = parse(json);
        } catch (Throwable e) {
            cache = null;
        }
        Prefs.setBankCustom(true);
    }

    private static int indexOf(JSONArray arr, String id, int fallback) {
        if (id != null && id.length() > 0 && id.charAt(0) != '#') {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id", ""))) {
                    return i;
                }
            }
        }
        // 没有 id 的老题：后台用 "#序号" 传回来，序号越界就不动
        if (fallback >= 0 && fallback < arr.length()) {
            return fallback;
        }
        return -1;
    }

    private static String newId(JSONArray arr, String subject, int grade) {
        String base = "u" + (grade > 0 ? grade : 0) + "-" + System.currentTimeMillis();
        String id = base;
        int n = 1;
        while (indexOf(arr, id, -1) >= 0) {
            id = base + "-" + (n++);
        }
        return id;
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
        return pick(n, excludeIds, null, null);
    }

    /**
     * 规则版抽题：可覆盖年级与科目范围（孩子自己选年级时用），null 沿用后台全局设置。
     * 任何覆盖都不放宽原有过滤，只是把候选范围缩小。
     */
    public List<Question> pick(int n, Set<String> excludeIds,
                               Set<Integer> gradeOverride, String subjectOverride) {
        List<Question> pool = all();
        if (n <= 0) {
            n = 1;
        }
        Set<String> allowed = subjectOverride == null ? allowedSubjects() : singleSubject(subjectOverride);
        Set<Integer> grades = gradeOverride == null ? Prefs.gradeSet() : gradeOverride;

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

    private static Set<String> singleSubject(String s) {
        Set<String> out = new LinkedHashSet<String>();
        if (s != null && s.trim().length() > 0) {
            out.add(s.trim());
        }
        return out;
    }

    /** 按 ID 取题（错题库优先复习用），仍受后台的科目 / 年级范围约束 */
    public List<Question> byIds(Set<String> ids, int limit) {
        List<Question> out = new ArrayList<Question>();
        if (ids == null || ids.isEmpty() || limit <= 0) {
            return out;
        }
        Set<String> left = new LinkedHashSet<String>(ids);
        Set<String> allowed = allowedSubjects();
        Set<Integer> grades = Prefs.gradeSet();
        for (Question q : all()) {
            if (!left.contains(q.id)) {
                continue;
            }
            if (!allowed.isEmpty() && !allowed.contains(q.subject)) {
                continue;
            }
            if (!grades.isEmpty() && q.grade != 0 && !grades.contains(q.grade)) {
                continue;
            }
            out.add(q);
            left.remove(q.id);
            if (out.size() >= limit) {
                break;
            }
        }
        Collections.shuffle(out);
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

    // ---------------------------------------------------------------- 难度星级
    //
    // 每道题带 1–5 星难度：老师/家长可以在后台按星筛题、单题改星，电视上答题时
    // 星级跟着「科目 · 年级」一起显示，孩子自己也知道这题难不难。

    public static int clampDifficulty(int d) {
        return d < 1 ? 1 : (d > 5 ? 5 : d);
    }

    /** 老题库没写难度时按年级给个默认值：1–2 年级 1 星，3 年级 2 星，4 年级 3 星，5 年级 4 星，6 年级 5 星 */
    static int defaultDifficulty(int grade) {
        if (grade <= 0) {
            return 3;
        }
        return clampDifficulty(grade <= 2 ? 1 : grade - 1);
    }

    /** "★★★☆☆"：实心星表示难度，空心补齐 5 颗 */
    public static String stars(int difficulty) {
        int d = clampDifficulty(difficulty);
        StringBuilder sb = new StringBuilder(5);
        for (int i = 1; i <= 5; i++) {
            sb.append(i <= d ? '★' : '☆');
        }
        return sb.toString();
    }

    /**
     * 难度字段解析：支持数字 1–5、"3"、"★★★"、"3星"；
     * 没写就按年级推默认值（老题库升级后也能在后台看到星级）。
     */
    static int parseDifficulty(JSONObject o, int grade) {
        if (o == null) {
            return defaultDifficulty(grade);
        }
        Object v = o.opt("difficulty");
        if (v == null || v == JSONObject.NULL) {
            v = o.opt("star");
        }
        if (v == null || v == JSONObject.NULL) {
            v = o.opt("stars");
        }
        if (v == null || v == JSONObject.NULL) {
            return defaultDifficulty(grade);
        }
        if (v instanceof Number) {
            int d = ((Number) v).intValue();
            return d >= 1 && d <= 5 ? d : defaultDifficulty(grade);
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0) {
            return defaultDifficulty(grade);
        }
        int filled = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '★' || s.charAt(i) == '*') {
                filled++;
            }
        }
        if (filled >= 1 && filled <= 5) {
            return filled;
        }
        try {
            int d = Integer.parseInt(s.replaceAll("[^0-9]", ""));
            if (d >= 1 && d <= 5) {
                return d;
            }
        } catch (Throwable ignored) {
        }
        return defaultDifficulty(grade);
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
            int difficulty = parseDifficulty(o, grade);
            String id = o.optString("id", "q" + (i + 1)).trim();
            out.add(new Question(id, subject, grade, q, list, answer, difficulty));
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
        /** 难度星级 1–5（1 星最易，5 星最难） */
        public final int difficulty;

        public Question(String id, String subject, int grade, String q,
                        List<String> options, int answer, int difficulty) {
            this.id = id;
            this.subject = subject;
            this.grade = grade;
            this.q = q;
            this.options = Collections.unmodifiableList(new ArrayList<String>(options));
            this.answer = answer;
            this.difficulty = clampDifficulty(difficulty);
        }

        public Question(String id, String subject, int grade, String q, List<String> options, int answer) {
            this(id, subject, grade, q, options, answer, defaultDifficulty(grade));
        }

        public Question(String id, String subject, String q, List<String> options, int answer) {
            this(id, subject, 0, q, options, answer);
        }

        /** 展示用的「科目 · 年级」，未标年级时只显示科目 */
        public String label() {
            return grade > 0 ? subject + " · " + grade + "年级" : subject;
        }

        /** 「★★★☆☆」，电视端与后台共用这一套星号 */
        public String stars() {
            return QuestionBank.stars(difficulty);
        }
    }
}
