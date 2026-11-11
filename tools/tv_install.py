# -*- coding: utf-8 -*-
"""题锁（QlockOnTV）局域网装机器。

做三件事：
  1. 扫描局域网里哪些电视 / 盒子装了题锁（HTTP 探测 8899）；
  2. 能 adb 的直接 adb install -r 覆盖安装，并回读版本号确认；
  3. adb 不通的（多数家用电视默认关着「网络调试」），起一个局域网下载页，
     让电视自带浏览器打开链接就能装。

用法:
    python tools/tv_install.py --auto                     # 扫描 + 自动装所有能找到的设备
    python tools/tv_install.py --ip 192.168.3.15          # 只装指定设备
    python tools/tv_install.py --auto --pin 1234          # 装完顺带回读版本（验证用）
    python tools/tv_install.py --serve                    # 只起下载页，不装
    python tools/tv_install.py --auto --json              # 给其他 AI agent 读的结构化结果

退出码：0 全部成功 / 1 一台都没装上 / 2 部分成功
"""
import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    from tv_scan import probe, local_cidr, local_ips  # 复用探测逻辑
except Exception:  # 单文件拷贝走时也不至于直接崩
    probe = None
    local_cidr = lambda: "192.168.1.0/24"
    local_ips = lambda: []

APP_PORT = 8899
ADB_PORT = 5555
SERVE_PORT = 8900
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


# ----------------------------------------------------------------- 工具

def find_adb():
    cand = [os.environ.get("ADB", ""), "adb",
            r"D:/android-toolchain/sdk/platform-tools/adb.exe",
            os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
            os.path.expanduser("~/Android/Sdk/platform-tools/adb")]
    for c in cand:
        if not c:
            continue
        try:
            out = subprocess.run([c, "version"], capture_output=True, text=True, timeout=10)
            if out.returncode == 0:
                return c
        except Exception:
            continue
    return None


def find_apk(explicit=""):
    if explicit:
        return explicit if os.path.exists(explicit) else None
    dist = os.path.join(ROOT, "dist")
    best = None
    if os.path.isdir(dist):
        for f in os.listdir(dist):
            if f.lower().endswith(".apk"):
                p = os.path.join(dist, f)
                if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                    best = p
    if best:
        return best
    p = os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk")
    return p if os.path.exists(p) else None


def run(cmd, timeout=180):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return 1, str(e)


def http_json(ip, path, pin="", timeout=6):
    url = "http://%s:%d%s" % (ip, APP_PORT, path)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(url)
    if pin:
        req.add_header("X-PIN", pin)
    try:
        with opener.open(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "ignore"))
    except urllib.error.HTTPError:
        return None
    except Exception:
        return None


def local_ip():
    """局域网地址：优先 192.168.x.x，避免 VPN / TUN 网卡被当成电视能访问的地址。"""
    ips = local_ips()
    if ips:
        return ips[0]
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 53))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


# ----------------------------------------------------------------- 动作

def scan():
    if probe is None:
        return []
    cidr = local_cidr()
    prefix = cidr.split("/")[0].rsplit(".", 1)[0]
    from concurrent.futures import ThreadPoolExecutor
    hosts = ["%s.%d" % (prefix, i) for i in range(1, 255)]
    found = []
    with ThreadPoolExecutor(max_workers=96) as ex:
        for r in ex.map(probe, hosts):
            if r.get("ok"):
                found.append(r["ip"])
    return sorted(found, key=lambda x: int(x.rsplit(".", 1)[-1]))


def adb_install(adb, ip, apk):
    run([adb, "connect", "%s:%d" % (ip, ADB_PORT)], timeout=15)
    code, out = run([adb, "-s", "%s:%d" % (ip, ADB_PORT), "install", "-r", apk], timeout=300)
    ok = "Success" in out
    msg = out.strip().splitlines()[-1] if out.strip() else ""
    if ok:
        # 覆盖安装会把进程停掉；重新拉起来，8899 后台才会恢复
        run([adb, "-s", "%s:%d" % (ip, ADB_PORT), "shell", "am", "start",
             "-n", "com.family.studytv/.MainActivity"], timeout=30)
        time.sleep(4)
    return ok, msg


def adb_version(adb, ip):
    code, out = run([adb, "-s", "%s:%d" % (ip, ADB_PORT), "shell", "dumpsys", "package",
                     "com.family.studytv"], timeout=25)
    for line in out.splitlines():
        if "versionName=" in line:
            return line.split("versionName=")[1].split()[0]
    return None


def serve(apk, port=SERVE_PORT):
    """起一个局域网下载页：电视浏览器打开 http://电脑IP:8900 就能下载安装。"""
    import http.server
    import functools
    import socketserver

    folder = os.path.dirname(apk)
    name = os.path.basename(apk)
    ip = local_ip()

    class H(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                body = ("<!doctype html><meta charset=utf-8><title>安装题锁</title>"
                        "<style>body{font:16px/1.7 -apple-system,'Microsoft YaHei';"
                        "background:#eef1e6;color:#1f2620;padding:32px;max-width:640px;margin:0 auto}"
                        "h1{font-size:20px}a.btn{display:block;background:#1f2b24;color:#fff;"
                        "text-align:center;padding:16px;border-radius:12px;text-decoration:none;"
                        "font-size:18px;margin:18px 0}code{background:#f1f3e9;padding:2px 6px;"
                        "border-radius:4px}</style>"
                        "<h1>在电视上安装「题锁」</h1>"
                        "<p>当前版本文件：<code>%s</code></p>"
                        '<a class="btn" href="/%s">下载并安装（%0.1f MB）</a>'
                        "<p>下载完成后打开它，允许「安装未知来源应用」即可覆盖安装，"
                        "原有题库 / 密码 / 规则都会保留。</p>" % (
                            name, name, os.path.getsize(apk) / 1048576.0))
                data = body.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            return http.server.SimpleHTTPRequestHandler.do_GET(self)

    handler = functools.partial(H, directory=folder)
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("0.0.0.0", port), handler) as httpd:
        print("下载页已启动：http://%s:%d  （电视浏览器打开这个地址）" % (ip, port))
        print("直接用文件地址也行：http://%s:%d/%s" % (ip, port, name))
        print("Ctrl+C 结束。")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n已停止。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--auto", action="store_true", help="扫描局域网并安装所有发现的题锁设备")
    ap.add_argument("--ip", default="", help="指定设备 IP，逗号分隔")
    ap.add_argument("--pin", default="", help="管理密码，用于装完回读版本号")
    ap.add_argument("--apk", default="", help="APK 路径，默认取 dist 下最新的")
    ap.add_argument("--serve", action="store_true", help="启动局域网下载页（电视没开调试时用）")
    ap.add_argument("--serve-port", type=int, default=SERVE_PORT)
    ap.add_argument("--json", action="store_true", help="输出 JSON 结果")
    args = ap.parse_args()

    apk = find_apk(args.apk)
    if args.serve:
        if not apk:
            print("找不到 APK，请用 --apk 指定")
            return 1
        serve(apk, args.serve_port)
        return 0

    ips = [x.strip() for x in args.ip.split(",") if x.strip()]
    if args.auto and not ips:
        ips = scan()
    if not ips:
        print("没找到设备。可以：\n"
              "  · 确认电视和电脑在同一 WiFi / 路由器；\n"
              "  · 用 --ip 直接指定电视 IP（后台页面顶栏能看到）；\n"
              "  · 电视没开网络调试时用 --serve 起下载页，在电视浏览器里装。")
        return 1
    if not apk:
        print("找不到 APK，请先 gradle assembleRelease，或用 --apk 指定")
        return 1

    adb = find_adb()
    if not adb:
        print("找不到 adb。装一个 platform-tools 或用 --serve 走浏览器下载。")
        adb = None

    results = []
    for ip in ips:
        row = {"ip": ip, "installed": False, "adb": bool(adb), "versionBefore": None,
               "versionAfter": None, "note": ""}
        pin = args.pin
        st = http_json(ip, "/api/state", pin) if pin else None
        if st is None and not pin:
            # 出厂默认密码 1234：只用来读版本号，读不到就算了
            st = http_json(ip, "/api/state", "1234")
            if st:
                row["pinDefault"] = True
        if st:
            row["versionBefore"] = st.get("appVersion")
        if adb:
            ok, msg = adb_install(adb, ip, apk)
            row["installed"] = ok
            row["note"] = msg
            if ok:
                row["versionAfter"] = adb_version(adb, ip)
                time.sleep(2)
                st2 = http_json(ip, "/api/state", pin or "1234")
                if st2 and st2.get("appVersion"):
                    row["versionAfter"] = st2["appVersion"]
        else:
            row["note"] = "没有 adb"
        results.append(row)

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        print("\n安装结果：")
        for r in results:
            flag = "OK " if r["installed"] else "失败"
            print("  %s %-15s %s -> %s  %s" % (
                flag, r["ip"], r.get("versionBefore") or "?", r.get("versionAfter") or "?",
                r.get("note") or ""))
        failed = [r for r in results if not r["installed"]]
        if failed:
            ip = local_ip()
            name = os.path.basename(apk)
            print("\n有 %d 台没装成功。这些电视多半关着「网络调试」，可以：\n"
                  "  1. 让电视和电脑都开同一个 WiFi，执行 python tools/tv_install.py --serve；\n"
                  "  2. 在电视浏览器打开 http://%s:%d ，点「下载并安装」（或直接开 http://%s:%d/%s）；\n"
                  "  3. 或者把 %s 拷到 U 盘插电视安装。"
                  % (len(failed), ip, SERVE_PORT, ip, SERVE_PORT, name, name))
            return 2
    return 0 if all(r["installed"] for r in results) else 2


if __name__ == "__main__":
    sys.exit(main())
