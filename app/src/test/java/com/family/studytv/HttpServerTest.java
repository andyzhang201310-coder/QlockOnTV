package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;

/**
 * 真实起一个监听端口，用 HTTP 客户端打过去，验证局域网后台可用，
 * 并且「扫码后必须输入管理密码」这条门槛在服务端真的拦得住。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HttpServerTest {

    private static final int PORT = 18899;

    private HttpServer server;
    /** 最近一次响应里的 Set-Cookie，登录用例靠它取会话 */
    private String lastSetCookie;

    @Before
    public void setUp() throws Exception {
        Prefs.setPin("1234");
        Prefs.setIntervalMinutes(30);
        Prefs.setQuizCount(5);
        // 卸载码相关状态在每个用例前复位，避免用例之间互相影响
        RuntimeEnvironment.getApplication().getSharedPreferences("studytv", Context.MODE_PRIVATE)
                .edit()
                .remove("uninstallHash")
                .remove("uninstallPinCustom")
                .remove("uninstallFail")
                .remove("uninstallLockUntil")
                .apply();
        QuestionBank.resetForTest();
        QuestionBank.get().ensureSeeded();
        server = new HttpServer(RuntimeEnvironment.getApplication(), PORT);
        server.start();
        waitForPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        Prefs.setUninstallGuard(true);
        Prefs.setBlockFactoryReset(false);
    }

    private void waitForPort() throws Exception {
        for (int i = 0; i < 60; i++) {
            Socket s = null;
            try {
                s = new Socket("127.0.0.1", PORT);
                return;
            } catch (Exception e) {
                Thread.sleep(50);
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new IllegalStateException("HTTP 服务未能启动：" + server.error());
    }

    private String[] request(String method, String path, String body, String pin) throws Exception {
        return request(method, path, body, pin, null);
    }

    private String[] request(String method, String path, String body, String pin, String cookie)
            throws Exception {
        lastSetCookie = null;
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        if (pin != null) {
            c.setRequestProperty("X-PIN", pin);
        }
        if (cookie != null) {
            c.setRequestProperty("Cookie", cookie);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = c.getResponseCode();
        lastSetCookie = c.getHeaderField("Set-Cookie");
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
        }
        return new String[]{String.valueOf(code), new String(bos.toByteArray(), "UTF-8")};
    }

    /** 用当前管理密码登录，返回可直接放进 Cookie 头的会话串 */
    private String login() throws Exception {
        String[] r = request("POST", "/api/login", "{\"pin\":\"" + Prefs.pin() + "\"}", null);
        assertEquals("登录应当成功：" + r[1], "200", r[0]);
        assertNotNull("登录成功后要下发会话 Cookie", lastSetCookie);
        return lastSetCookie.split(";")[0];
    }

    // ---------------------------------------------------------------- 登录门槛

    @Test
    public void scanningOnlyShowsLoginPage() throws Exception {
        String[] r = request("GET", "/", null, null);
        assertEquals("200", r[0]);
        assertTrue("没登录只能看到登录页", r[1].contains("请输入电视上设置的管理密码"));
        assertTrue("登录页要能提交密码", r[1].contains("/api/login"));
        assertFalse("登录前不能看到管理内容", r[1].contains("题库编辑"));
        assertFalse("登录前不能看到防卸载区", r[1].contains("防卸载保护"));
    }

    @Test
    public void adminPageAfterLogin() throws Exception {
        String cookie = login();
        String[] r = request("GET", "/", null, null, cookie);
        assertEquals("200", r[0]);
        assertTrue("登录后进入管理页", r[1].contains("题库编辑"));
        assertTrue("管理页应有防卸载卡片", r[1].contains("防卸载保护"));
        assertTrue("管理页应展示设备所有者命令", r[1].contains("deviceOwnerCmd"));
        assertTrue("管理页要有退出登录", r[1].contains("退出登录"));
    }

    @Test
    public void wrongPinIsRejectedAndNoCookieIssued() throws Exception {
        String[] r = request("POST", "/api/login", "{\"pin\":\"0000\"}", null);
        assertEquals("401", r[0]);
        assertNull("登录失败不能下发会话", lastSetCookie);
        assertTrue(r[1].contains("error"));
    }

    @Test
    public void stateAndBankRequireLogin() throws Exception {
        assertEquals("401", request("GET", "/api/state", null, null)[0]);
        assertEquals("未登录不能读题库（里面有答案）", "401",
                request("GET", "/api/questions", null, null)[0]);

        String cookie = login();
        String[] state = request("GET", "/api/state", null, null, cookie);
        assertEquals("200", state[0]);
        assertTrue(state[1].contains("\"questionCount\""));
        assertTrue(state[1].contains("\"url\""));

        String[] bank = request("GET", "/api/questions", null, null, cookie);
        assertEquals("200", bank[0]);
        assertTrue(bank[1].contains("questions"));
    }

    @Test
    public void logoutInvalidatesSession() throws Exception {
        String cookie = login();
        assertEquals("200", request("GET", "/api/state", null, null, cookie)[0]);

        String[] out = request("POST", "/api/logout", "{}", null, cookie);
        assertEquals("200", out[0]);

        assertEquals("退出后旧会话立即失效", "401",
                request("GET", "/api/state", null, null, cookie)[0]);
        assertFalse("退出后回到登录页",
                request("GET", "/", null, null, cookie)[1].contains("题库编辑"));
    }

    @Test
    public void pinQueryFallbackWorksForCookieLessBrowsers() throws Exception {
        String[] ok = request("GET", "/?pin=1234", null, null);
        assertEquals("200", ok[0]);
        assertTrue("禁用 Cookie 的浏览器靠 ?pin= 进管理页", ok[1].contains("题库编辑"));

        String[] bad = request("GET", "/?pin=0000", null, null);
        assertFalse("密码不对时仍是登录页", bad[1].contains("题库编辑"));
    }

    @Test
    public void repeatedWrongPinLocksLogin() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals("401", request("POST", "/api/login", "{\"pin\":\"9999\"}", null)[0]);
        }
        String[] locked = request("POST", "/api/login", "{\"pin\":\"1234\"}", null);
        assertEquals("连错 5 次后即使密码正确也要先等锁定结束", "429", locked[0]);
        assertTrue(locked[1].contains("秒后再试"));
    }

    @Test
    public void changingPinKicksOtherDevices() throws Exception {
        String cookieA = login();
        String cookieB = login();

        String[] r = request("POST", "/api/settings", "{\"pin\":\"9527\"}", "1234", cookieA);
        assertEquals("200", r[0]);
        assertEquals("9527", Prefs.pin());
        assertNotNull("改密后应给当前设备重发会话", lastSetCookie);
        String cookieA2 = lastSetCookie.split(";")[0];

        assertEquals("当前设备保持登录", "200",
                request("GET", "/api/state", null, null, cookieA2)[0]);
        assertEquals("其他设备被踢下线", "401",
                request("GET", "/api/state", null, null, cookieB)[0]);

        Prefs.setPin("1234");
    }

    @Test
    public void gradeSettingRoundTripsThroughApi() throws Exception {
        String cookie = login();

        // 只选三年级
        String[] r = request("POST", "/api/settings", "{\"grades\":\"3\"}", "1234", cookie);
        assertEquals("200", r[0]);
        assertEquals("3", Prefs.grades());

        String[] st = request("GET", "/api/state", null, null, cookie);
        assertTrue("状态里应带上已选年级：" + st[1], st[1].contains("\"grades\":\"3\""));
        assertTrue("状态里应有各年级题量：" + st[1], st[1].contains("countByGrade"));

        // 多选 1-3
        assertEquals("200",
                request("POST", "/api/settings", "{\"grades\":\"3,1,2\"}", "1234", cookie)[0]);
        assertEquals("应去重并排序", "1,2,3", Prefs.grades());

        // 数组写法也支持
        assertEquals("200",
                request("POST", "/api/settings", "{\"grades\":[2,5]}", "1234", cookie)[0]);
        assertEquals("2,5", Prefs.grades());

        // 全选
        assertEquals("200",
                request("POST", "/api/settings", "{\"grades\":\"1,2,3,4,5,6\"}", "1234", cookie)[0]);
        assertTrue(Prefs.allGrades());

        // 非法值 → 归一化成全部，不能把题库筛空
        assertEquals("200",
                request("POST", "/api/settings", "{\"grades\":\"9,abc\"}", "1234", cookie)[0]);
        assertEquals("1,2,3,4,5,6", Prefs.grades());
    }

    // ---------------------------------------------------------------- 原有功能

    @Test
    public void settingsRequiresCorrectPin() throws Exception {
        String[] unauthorized = request("POST", "/api/settings", "{\"intervalMinutes\":15}", null);
        assertEquals("401", unauthorized[0]);

        String[] wrong = request("POST", "/api/settings", "{\"intervalMinutes\":15}", "0000");
        assertEquals("401", wrong[0]);
        assertEquals(30, Prefs.intervalMinutes());

        String cookie = login();
        String[] ok = request("POST", "/api/settings", "{\"intervalMinutes\":15,\"quizCount\":3}",
                null, cookie);
        assertEquals("200", ok[0]);
        assertEquals(15, Prefs.intervalMinutes());
        assertEquals(3, Prefs.quizCount());
    }

    @Test
    public void canReplaceQuestionBank() throws Exception {
        String payload = "{\"json\":\"{\\\"questions\\\":[{\\\"subject\\\":\\\"语文\\\",\\\"q\\\":\\\"接口测试题\\\",\\\"options\\\":[\\\"对\\\",\\\"错\\\"],\\\"answer\\\":0}]}\"}";
        String cookie = login();
        String[] r = request("POST", "/api/questions", payload, null, cookie);
        assertEquals("200", r[0]);
        assertEquals(1, QuestionBank.get().count());

        String[] list = request("GET", "/api/questions", null, null, cookie);
        assertEquals("200", list[0]);
        assertTrue(list[1].contains("接口测试题"));
    }

    @Test
    public void emptyBankIsRejected() throws Exception {
        String[] r = request("POST", "/api/questions", "{\"json\":\"{\\\"questions\\\":[]}\"}", "1234");
        assertEquals("400", r[0]);
        assertTrue(r[1].contains("error"));
    }

    @Test
    public void unknownPathReturns404() throws Exception {
        String[] r = request("GET", "/nope", null, null);
        assertEquals("404", r[0]);
    }

    @Test
    public void stateReportsUninstallGuardFields() throws Exception {
        String cookie = login();
        String[] r = request("GET", "/api/state", null, null, cookie);
        assertEquals("200", r[0]);
        assertTrue(r[1].contains("\"uninstallGuard\""));
        assertTrue(r[1].contains("\"isDeviceOwner\""));
        assertTrue(r[1].contains("\"uninstallBlocked\""));
        assertTrue("应给出 adb 命令", r[1].contains("dpm set-device-owner"));
    }

    @Test
    public void changingUninstallPinRequiresOldCode() throws Exception {
        // 没带旧码
        String[] noOld = request("POST", "/api/settings",
                "{\"uninstallPin\":\"839201\"}", "1234");
        assertEquals("403", noOld[0]);
        assertTrue(Prefs.verifyUninstallPin(UninstallCode.DEFAULT_CODE));

        // 旧码错误
        String[] wrongOld = request("POST", "/api/settings",
                "{\"uninstallPin\":\"839201\",\"oldUninstallPin\":\"111111\"}", "1234");
        assertEquals("403", wrongOld[0]);

        // 新码格式非法
        String[] badNew = request("POST", "/api/settings",
                "{\"uninstallPin\":\"12\",\"oldUninstallPin\":\"246810\"}", "1234");
        assertEquals("400", badNew[0]);

        // 正确
        String[] ok = request("POST", "/api/settings",
                "{\"uninstallPin\":\"839201\",\"oldUninstallPin\":\"246810\"}", "1234");
        assertEquals("200", ok[0]);
        assertTrue(Prefs.verifyUninstallPin("839201"));
        assertTrue(Prefs.hasCustomUninstallPin());
    }

    @Test
    public void uninstallGuardCanBeToggledOverHttp() throws Exception {
        String[] off = request("POST", "/api/settings", "{\"uninstallGuard\":false}", "1234");
        assertEquals("200", off[0]);
        assertFalse(Prefs.uninstallGuard());

        String[] on = request("POST", "/api/settings", "{\"uninstallGuard\":true,\"blockFactoryReset\":true}", "1234");
        assertEquals("200", on[0]);
        assertTrue(Prefs.uninstallGuard());
        assertTrue(Prefs.blockFactoryReset());
    }

    @Test
    public void stateReportsDefaultPinFlag() throws Exception {
        Prefs.setPin("1234");
        String cookie = login();
        String[] weak = request("GET", "/api/state", null, null, cookie);
        assertEquals("200", weak[0]);
        assertTrue("默认密码要能被手机端识别并提示", weak[1].contains("\"pinDefault\":true"));

        Prefs.setPin("9527");
        String[] strong = request("GET", "/api/state", null, null, cookie);
        assertTrue(strong[1].contains("\"pinDefault\":false"));

        Prefs.setPin("1234");
    }

    @Test
    public void brandLogoIsPublicAndIsPng() throws Exception {
        // 登录页也要显示 logo，所以未登录就必须能取到这张图
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + PORT + "/logo.png").openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        assertEquals(200, c.getResponseCode());
        assertTrue("返回类型应为 image/png：" + c.getContentType(),
                c.getContentType() != null && c.getContentType().startsWith("image/png"));
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        byte[] png = bos.toByteArray();
        assertTrue("logo 内容不应为空", png.length > 1000);
        assertTrue("应为 PNG 文件头", png[0] == (byte) 0x89 && png[1] == (byte) 0x50
                && png[2] == (byte) 0x4E && png[3] == (byte) 0x47);
        assertEquals("favicon 复用同一张图，同样不需登录", "200",
                request("GET", "/favicon.ico", null, null)[0]);
    }

    @Test
    public void unlockRequiresLogin() throws Exception {
        assertEquals("未登录不能远程放行，401", "401",
                request("POST", "/api/unlock", "{}", null)[0]);
    }

    @Test
    public void unlockClearsPendingAndRestartsTimer() throws Exception {
        String cookie = login();
        Prefs.setPending(true);
        // 故意让「本轮已到点」，模拟答题弹窗正显示在电视上
        Prefs.setDueAt(System.currentTimeMillis() - 60000L);
        long before = System.currentTimeMillis();

        String[] r = request("POST", "/api/unlock", "{}", null, cookie);
        assertEquals("200", r[0]);
        assertTrue(r[1].contains("\"ok\":true"));
        assertFalse("解锁后不能留着未完成标记，否则开机/重启服务会立刻补弹", Prefs.isPending());
        assertTrue("下一轮应从现在重新计时，否则 15 秒轮询会马上把弹窗拉回来",
                Prefs.dueAt() >= before + Prefs.intervalMinutes() * 60000L - 1000L);
    }

    @Test
    public void stateReportsQuizRunning() throws Exception {
        String cookie = login();
        QuizActivity.running = true;
        try {
            String[] r = request("GET", "/api/state", null, null, cookie);
            assertEquals("200", r[0]);
            assertTrue("后台要能显示电视上是否正在弹题", r[1].contains("\"quizRunning\":true"));
        } finally {
            QuizActivity.running = false;
        }
    }
}
