package com.family.studytv;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简局域网 HTTP 服务：手机/电脑连同一个 WiFi 打开 http://电视IP:端口 即可管理。
 * 微信「扫一扫」直接打开同一地址，无需装 App。
 */
public class HttpServer {

    private static final int MAX_BODY = 8 * 1024 * 1024;

    /** 登录后下发的会话 Cookie 名 */
    private static final String COOKIE_NAME = "st_session";
    /** 会话有效期（自登录起算，12 小时） */
    private static final long SESSION_TTL_MS = 12 * 60 * 60 * 1000L;
    /** 连续输错几次后锁定 */
    private static final int LOGIN_MAX_FAILS = 5;
    /** 锁定时长 */
    private static final long LOGIN_LOCK_MS = 30 * 1000L;

    private final Context ctx;
    private final int port;

    /** token → 过期时间戳。登录态只存在内存，重启电视后需重新登录 */
    private final Map<String, Long> sessions = new HashMap<String, Long>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    private final Object loginLock = new Object();
    private int loginFails = 0;
    private long loginLockUntil = 0;

    private volatile boolean running = false;
    private volatile String error = "";
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public HttpServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
    }

    public String error() {
        return error;
    }

    public void start() {
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "studytv-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Throwable ignored) {
        }
        serverSocket = null;
    }

    // ------------------------------------------------------------------ 主循环

    private void loop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            serverSocket = ss;
            error = "";
        } catch (Throwable e) {
            error = "端口 " + port + " 启动失败：" + e.getMessage();
            running = false;
            return;
        }
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "studytv-req");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                if (running) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream in = socket.getInputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.trim().length() == 0) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];

            Map<String, String> headers = new HashMap<String, String>();
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && line.length() > 0 && guard++ < 120) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
                }
            }

            int len = 0;
            try {
                len = Integer.parseInt(headers.get("content-length"));
            } catch (Throwable ignored) {
            }
            byte[] body = new byte[0];
            if (len > 0) {
                int n = Math.min(len, MAX_BODY);
                body = new byte[n];
                int off = 0;
                while (off < n) {
                    int r = in.read(body, off, n - off);
                    if (r < 0) {
                        break;
                    }
                    off += r;
                }
            }

            String path = target;
            String query = "";
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                query = target.substring(qi + 1);
            }
            path = decode(path);
            Map<String, String> params = parseQuery(query);

            route(socket, method, path, params, headers, new String(body, "UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 路由

    private void route(Socket socket, String method, String path, Map<String, String> params,
                       Map<String, String> headers, String body) throws Exception {

        if (method.equals("OPTIONS")) {
            respond(socket, 204, "text/plain; charset=utf-8", new byte[0]);
            return;
        }

        // ---- 登录 / 登出（唯一不需要登录态的两个接口）
        if (path.equals("/api/login") && method.equals("POST")) {
            long lockLeft = loginLockRemainMs();
            if (lockLeft > 0) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", "密码错误次数过多，请 " + ((lockLeft + 999) / 1000) + " 秒后再试");
                respondJson(socket, 429, err.toString());
                return;
            }
            String pin = "";
            try {
                pin = new JSONObject(body == null || body.trim().length() == 0 ? "{}" : body)
                        .optString("pin", "").trim();
            } catch (Throwable ignored) {
            }
            if (!pin.equals(Prefs.pin())) {
                noteLoginFail();
                // 放慢失败响应，降低脚本爆破速度
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                unauthorized(socket);
                return;
            }
            noteLoginOk();
            String token = newSession();
            respond(socket, 200, "application/json; charset=utf-8",
                    "{\"ok\":true}".getBytes("UTF-8"),
                    COOKIE_NAME + "=" + token + "; Path=/; Max-Age=" + (SESSION_TTL_MS / 1000)
                            + "; HttpOnly; SameSite=Lax");
            return;
        }

        if (path.equals("/api/logout") && method.equals("POST")) {
            dropSession(headers);
            respond(socket, 200, "application/json; charset=utf-8",
                    "{\"ok\":true}".getBytes("UTF-8"),
                    COOKIE_NAME + "=; Path=/; Max-Age=0");
            return;
        }

        // 品牌图属于公开资源：登录页也要显示，所以放在鉴权判断之前。
        // favicon.ico 复用同一张 PNG，浏览器标签页也能看到新 logo。
        if (path.equals("/logo.png") || path.equals("/favicon.ico")) {
            byte[] img = readAssetBytes("logo.png");
            if (img == null) {
                respond(socket, 404, "text/plain; charset=utf-8", new byte[0]);
                return;
            }
            respond(socket, 200, "image/png", img);
            return;
        }

        boolean logged = hasSession(headers);
        boolean pinOk = auth(params, headers, body);
        boolean authed = logged || pinOk;

        if (path.equals("/") || path.equals("/index.html") || path.equals("/admin")) {
            // 没登录只能看到登录页；?pin= 是为了兼容禁用 Cookie 的内嵌浏览器
            String asset = authed ? "admin.html" : "login.html";
            respond(socket, 200, "text/html; charset=utf-8", readAsset(asset).getBytes("UTF-8"));
            return;
        }

        if (path.equals("/api/state")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            respondJson(socket, 200, stateJson().toString());
            return;
        }

        if (path.equals("/api/questions")) {
            if (method.equals("GET")) {
                // 题库里有正确答案，未登录不给看
                if (!authed) {
                    unauthorized(socket);
                    return;
                }
                respondJson(socket, 200, bankJson().toString(2));
                return;
            }
            if (method.equals("POST")) {
                if (!authed) {
                    unauthorized(socket);
                    return;
                }
                String json = body == null ? "" : body.trim();
                try {
                    if (json.startsWith("{")) {
                        JSONObject o = new JSONObject(json);
                        if (o.has("json")) {
                            json = o.getString("json");
                        } else if (o.has("questions")) {
                            json = new JSONObject().put("questions", o.get("questions")).toString();
                        }
                    }
                    QuestionBank.get().save(json);
                    JSONObject ok = new JSONObject();
                    ok.put("ok", true);
                    ok.put("count", QuestionBank.get().count());
                    respondJson(socket, 200, ok.toString());
                } catch (Throwable e) {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("error", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
                    respondJson(socket, 400, err.toString());
                }
                return;
            }
        }

        if (path.equals("/api/settings") && method.equals("POST")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            try {
                JSONObject in = new JSONObject(body == null || body.trim().length() == 0 ? "{}" : body);
                String reissueCookie = null;
                if (in.has("enabled")) {
                    Prefs.setEnabled(in.optBoolean("enabled", true));
                }
                if (in.has("intervalMinutes")) {
                    Prefs.setIntervalMinutes(in.optInt("intervalMinutes", Prefs.intervalMinutes()));
                }
                if (in.has("quizCount")) {
                    Prefs.setQuizCount(in.optInt("quizCount", Prefs.quizCount()));
                }
                if (in.has("bootQuiz")) {
                    Prefs.setBootQuiz(in.optBoolean("bootQuiz", true));
                }
                if (in.has("subjects")) {
                    Prefs.setSubjects(in.optString("subjects", Prefs.subjects()));
                }
                if (in.has("grades")) {
                    Object gv = in.opt("grades");
                    if (gv instanceof JSONArray) {
                        StringBuilder sb = new StringBuilder();
                        JSONArray arr = (JSONArray) gv;
                        for (int k = 0; k < arr.length(); k++) {
                            if (sb.length() > 0) {
                                sb.append(',');
                            }
                            sb.append(arr.optInt(k, 0));
                        }
                        Prefs.setGrades(sb.toString());
                    } else {
                        Prefs.setGrades(in.optString("grades", Prefs.grades()));
                    }
                }
                if (in.has("pin")) {
                    String p = in.optString("pin", "").trim();
                    if (p.length() >= 4 && !p.equals(Prefs.pin())) {
                        Prefs.setPin(p);
                        // 密码变了：踢掉所有已登录设备，只给当前这台重发会话
                        dropAllSessions();
                        reissueCookie = COOKIE_NAME + "=" + newSession()
                                + "; Path=/; Max-Age=" + (SESSION_TTL_MS / 1000)
                                + "; HttpOnly; SameSite=Lax";
                    }
                }
                // 防卸载：开关 / 禁止恢复出厂 / 改卸载码（改码必须校验原码）
                if (in.has("uninstallGuard")) {
                    Prefs.setUninstallGuard(in.optBoolean("uninstallGuard", Prefs.uninstallGuard()));
                }
                if (in.has("blockFactoryReset")) {
                    Prefs.setBlockFactoryReset(in.optBoolean("blockFactoryReset", Prefs.blockFactoryReset()));
                }
                if (in.has("uninstallPin")) {
                    String np = in.optString("uninstallPin", "").trim();
                    String old = in.optString("oldUninstallPin", "").trim();
                    if (np.length() > 0) {
                        if (!UninstallCode.isAcceptable(np)) {
                            JSONObject err = new JSONObject();
                            err.put("ok", false);
                            err.put("error", "卸载码需 4~12 位数字或字母");
                            respondJson(socket, 400, err.toString());
                            return;
                        }
                        if (!Prefs.verifyUninstallPin(old)) {
                            long left = Prefs.uninstallLockRemainMs();
                            JSONObject err = new JSONObject();
                            err.put("ok", false);
                            err.put("error", left > 0
                                    ? "原卸载码错误，已临时锁定 " + UninstallCode.describeLock(left)
                                    : "原卸载码不正确（出厂默认 246810）");
                            respondJson(socket, 403, err.toString());
                            return;
                        }
                        Prefs.setUninstallPin(np);
                    }
                }
                boolean portChanged = false;
                if (in.has("port")) {
                    int np = in.optInt("port", Prefs.port());
                    if (np >= 1024 && np <= 65500 && np != Prefs.port()) {
                        Prefs.setPort(np);
                        portChanged = true;
                    }
                }
                // 参数变更后从当前时间重新计时
                Prefs.setDueAt(System.currentTimeMillis() + Prefs.intervalMinutes() * 60000L);
                Intent i = new Intent(ctx, GuardService.class);
                i.setAction(portChanged ? GuardService.ACTION_RELOAD : GuardService.ACTION_RESCHEDULE);
                GuardService.start(ctx, i);
                boolean locked = DeviceGuard.sync(ctx);

                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                ok.put("port", Prefs.port());
                ok.put("uninstallBlocked", locked);
                ok.put("isDeviceOwner", DeviceGuard.isDeviceOwner(ctx));
                respondJson(socket, 200, ok.toString(), reissueCookie);
            } catch (Throwable e) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", String.valueOf(e.getMessage()));
                respondJson(socket, 400, err.toString());
            }
            return;
        }

        if (path.equals("/api/import") && method.equals("POST")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            String url = "";
            try {
                JSONObject in = new JSONObject(body == null ? "{}" : body);
                url = in.optString("url", "").trim();
            } catch (Throwable ignored) {
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", "请填写完整的 http(s) 地址");
                respondJson(socket, 400, err.toString());
                return;
            }
            try {
                String remote = fetch(url);
                QuestionBank.get().save(remote);
                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                ok.put("count", QuestionBank.get().count());
                respondJson(socket, 200, ok.toString());
            } catch (Throwable e) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", String.valueOf(e.getMessage()));
                respondJson(socket, 400, err.toString());
            }
            return;
        }

        if (path.equals("/api/test") && method.equals("POST")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            Intent i = new Intent(ctx, GuardService.class);
            i.setAction(GuardService.ACTION_SHOW_NOW);
            GuardService.start(ctx, i);
            respondJson(socket, 200, "{\"ok\":true}");
            return;
        }

        if (path.equals("/api/reschedule") && method.equals("POST")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            Prefs.setDueAt(System.currentTimeMillis() + Prefs.intervalMinutes() * 60000L);
            Intent i = new Intent(ctx, GuardService.class);
            i.setAction(GuardService.ACTION_RESCHEDULE);
            GuardService.start(ctx, i);
            respondJson(socket, 200, "{\"ok\":true}");
            return;
        }

        if (path.equals("/api/unlock") && method.equals("POST")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            // 家长远程放行：退出答题弹窗 + 从现在重排下一轮，避免 15 秒后又被拉回来
            GuardService.applyUnlock(ctx);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("dueAt", Prefs.dueAt());
            respondJson(socket, 200, o.toString());
            return;
        }

        if (path.equals("/api/restore") && method.equals("POST")) {
            if (!authed) {
                unauthorized(socket);
                return;
            }
            boolean ok = QuestionBank.get().restoreBackup();
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            if (!ok) {
                o.put("error", "没有可用的备份");
            }
            respondJson(socket, ok ? 200 : 400, o.toString());
            return;
        }

        respondJson(socket, 404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    private boolean auth(Map<String, String> params, Map<String, String> headers, String body) {
        String pin = params.get("pin");
        if (pin == null) {
            pin = headers.get("x-pin");
        }
        if (pin == null && body != null && body.startsWith("{")) {
            try {
                pin = new JSONObject(body).optString("pin", null);
            } catch (Throwable ignored) {
            }
        }
        return pin != null && pin.trim().equals(Prefs.pin());
    }

    private void unauthorized(Socket socket) throws Exception {
        respondJson(socket, 401, "{\"ok\":false,\"error\":\"管理密码错误\"}");
    }

    // ------------------------------------------------------------------ 登录态

    private String newSession() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (int i = 0; i < buf.length; i++) {
            String h = Integer.toHexString(buf[i] & 0xFF);
            if (h.length() < 2) {
                sb.append('0');
            }
            sb.append(h);
        }
        String token = sb.toString();
        long expireAt = System.currentTimeMillis() + SESSION_TTL_MS;
        synchronized (sessions) {
            long now = System.currentTimeMillis();
            java.util.Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() < now) {
                    it.remove();
                }
            }
            sessions.put(token, expireAt);
        }
        return token;
    }

    private static String cookieToken(Map<String, String> headers) {
        String raw = headers.get("cookie");
        if (raw == null) {
            return null;
        }
        for (String part : raw.split(";")) {
            String s = part.trim();
            if (s.startsWith(COOKIE_NAME + "=")) {
                String v = s.substring(COOKIE_NAME.length() + 1).trim();
                return v.length() == 0 ? null : v;
            }
        }
        return null;
    }

    private boolean hasSession(Map<String, String> headers) {
        String token = cookieToken(headers);
        if (token == null) {
            return false;
        }
        synchronized (sessions) {
            Long expireAt = sessions.get(token);
            if (expireAt == null) {
                return false;
            }
            if (expireAt < System.currentTimeMillis()) {
                sessions.remove(token);
                return false;
            }
            return true;
        }
    }

    private void dropSession(Map<String, String> headers) {
        String token = cookieToken(headers);
        if (token == null) {
            return;
        }
        synchronized (sessions) {
            sessions.remove(token);
        }
    }

    /** 改管理密码后，旧登录态统统失效，防止密码泄露后被长期复用 */
    private void dropAllSessions() {
        synchronized (sessions) {
            sessions.clear();
        }
    }

    private long loginLockRemainMs() {
        synchronized (loginLock) {
            long left = loginLockUntil - System.currentTimeMillis();
            return left > 0 ? left : 0;
        }
    }

    private void noteLoginFail() {
        synchronized (loginLock) {
            loginFails++;
            if (loginFails >= LOGIN_MAX_FAILS) {
                loginFails = 0;
                loginLockUntil = System.currentTimeMillis() + LOGIN_LOCK_MS;
            }
        }
    }

    private void noteLoginOk() {
        synchronized (loginLock) {
            loginFails = 0;
            loginLockUntil = 0;
        }
    }

    // ------------------------------------------------------------------ 数据

    private JSONObject stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("appVersion", BuildConfig.VERSION_NAME);
            o.put("enabled", Prefs.isEnabled());
            o.put("intervalMinutes", Prefs.intervalMinutes());
            o.put("quizCount", Prefs.quizCount());
            o.put("bootQuiz", Prefs.bootQuiz());
            o.put("subjects", Prefs.subjects());
            o.put("port", Prefs.port());
            o.put("ip", LanUtils.ip());
            o.put("url", LanUtils.url());
            o.put("pending", Prefs.isPending());
            o.put("quizRunning", QuizActivity.running);
            o.put("dueAt", Prefs.dueAt());
            o.put("lastPassAt", Prefs.lastPassAt());
            o.put("serverError", error);
            o.put("now", System.currentTimeMillis());

            Map<String, Integer> bySubject = QuestionBank.get().countBySubject();
            JSONObject sub = new JSONObject();
            for (Map.Entry<String, Integer> e : bySubject.entrySet()) {
                sub.put(e.getKey(), e.getValue());
            }
            o.put("countBySubject", sub);
            o.put("questionCount", QuestionBank.get().count());

            // ---- 年级：当前选择 + 各年级题量（0 = 未标年级的通用题）
            o.put("grades", Prefs.grades());
            o.put("allGrades", Prefs.allGrades());
            JSONObject byGrade = new JSONObject();
            for (Map.Entry<Integer, Integer> e : QuestionBank.get().countByGrade().entrySet()) {
                byGrade.put(String.valueOf(e.getKey()), e.getValue());
            }
            o.put("countByGrade", byGrade);
            JSONArray gradeOptions = new JSONArray();
            for (int g = Prefs.GRADE_MIN; g <= Prefs.GRADE_MAX; g++) {
                gradeOptions.put(g);
            }
            o.put("gradeOptions", gradeOptions);

            // ---- 防卸载状态
            o.put("uninstallGuard", Prefs.uninstallGuard());
            o.put("blockFactoryReset", Prefs.blockFactoryReset());
            o.put("isAdminActive", DeviceGuard.isAdminActive(ctx));
            o.put("isDeviceOwner", DeviceGuard.isDeviceOwner(ctx));
            o.put("uninstallBlocked", DeviceGuard.isUninstallBlocked(ctx));
            o.put("uninstallPinCustom", Prefs.hasCustomUninstallPin());
            o.put("pinDefault", "1234".equals(Prefs.pin()));
            o.put("uninstallLockMs", Prefs.uninstallLockRemainMs());
            o.put("deviceOwnerCmd", DeviceGuard.adbCommand(ctx));
            o.put("activeAdminCmd", DeviceGuard.adbActiveAdminCommand(ctx));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONObject bankJson() throws Exception {
        List<QuestionBank.Question> list = QuestionBank.get().all();
        JSONArray arr = new JSONArray();
        for (QuestionBank.Question q : list) {
            JSONObject o = new JSONObject();
            o.put("id", q.id);
            o.put("subject", q.subject);
            o.put("q", q.q);
            JSONArray opts = new JSONArray();
            for (String s : q.options) {
                opts.put(s);
            }
            o.put("options", opts);
            o.put("answer", q.answer);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("count", arr.length());
        root.put("questions", arr);
        return root;
    }

    // ------------------------------------------------------------------ 工具

    /** 读二进制 assets（品牌图等），失败返回 null */
    private byte[] readAssetBytes(String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return "<html><body style='font-family:sans-serif;padding:24px'>管理页面缺失：" + name + "</body></html>";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json,*/*");
            c.connect();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("远端返回 HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void respondJson(Socket socket, int code, String json) throws Exception {
        respondJson(socket, code, json, null);
    }

    private void respondJson(Socket socket, int code, String json, String setCookie) throws Exception {
        respond(socket, code, "application/json; charset=utf-8", json.getBytes("UTF-8"), setCookie);
    }

    private void respond(Socket socket, int code, String type, byte[] body) throws Exception {
        respond(socket, code, type, body, null);
    }

    private void respond(Socket socket, int code, String type, byte[] body, String setCookie) throws Exception {
        OutputStream os = socket.getOutputStream();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (setCookie != null && setCookie.length() > 0) {
            sb.append("Set-Cookie: ").append(setCookie).append("\r\n");
        }
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, X-PIN\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        sb.append("Connection: close\r\n\r\n");
        os.write(sb.toString().getBytes("UTF-8"));
        if (body.length > 0) {
            os.write(body);
        }
        os.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            default:
                return "OK";
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
            if (bos.size() > 8192) {
                break;
            }
        }
        if (c == -1 && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (query == null || query.length() == 0) {
            return map;
        }
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                map.put(decode(kv.substring(0, i)), decode(kv.substring(i + 1)));
            } else if (kv.length() > 0) {
                map.put(decode(kv), "");
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }
}
