# -*- coding: utf-8 -*-
"""局域网电视 / 模拟器扫描器。

用途：在同一个 WiFi / 路由器下找出所有装了「题锁」的电视（HTTP 探测 8899），
再用 adb 网络调试（5555）确认能不能直接装包。

用法:
    python tools/tv_scan.py                 # 扫本机所在网段
    python tools/tv_scan.py 192.168.3.0/24  # 指定网段
    python tools/tv_scan.py --adb           # 顺带 adb connect 并读机型
    python tools/tv_scan.py --json          # 机器可读输出

注意：必须直连、绕开系统代理（HTTP_PROXY 会把内网请求转给代理，返回 502），
本脚本的 HTTP 探测已显式禁用代理；TCP 连接判断同样只看真实 HTTP 响应。
"""
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

from concurrent.futures import ThreadPoolExecutor

APP_PORT = 8899
ADB_PORT = 5555
TIMEOUT = float(os.environ.get("TV_SCAN_TIMEOUT", "1.2"))

ADB = os.environ.get("ADB", "adb")


def local_ips():
    """本机所有 IPv4；虚拟网卡（VPN / TUN）排在后面。"""
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.append(info[4][0])
    except Exception:
        pass
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 53))
        ips.append(s.getsockname()[0])
    except Exception:
        pass
    finally:
        s.close()
    seen, out = set(), []
    for ip in ips:
        if ip.startswith("127.") or ip in seen:
            continue
        seen.add(ip)
        out.append(ip)
    out.sort(key=lambda ip: (0 if ip.startswith("192.168.") else
                             1 if ip.startswith("172.") else 2))
    return out


def local_cidr():
    ips = local_ips()
    return (ips[0].rsplit(".", 1)[0] + ".0/24") if ips else "192.168.1.0/24"


def http_json(ip, path, timeout=TIMEOUT):
    """直连读取，绕过系统代理。返回 (状态码, JSON 或 None)。"""
    url = "http://%s:%d%s" % (ip, APP_PORT, path)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(url, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "ignore"))
    except urllib.error.HTTPError as e:
        body = None
        try:
            body = json.loads(e.read().decode("utf-8", "ignore"))
        except Exception:
            pass
        return e.code, body
    except Exception:
        return 0, None


def probe(ip, retry=True):
    """只有真的从题锁接口拿到 JSON 才算命中；代理 502 / 别的服务一律不算。"""
    out = {"ip": ip, "ok": False, "code": 0, "version": None,
           "questions": None, "pinDefault": None}
    code, data = http_json(ip, "/api/state")
    if code == 0 and retry:
        # 电视 / 模拟器偶尔一次超时，再给一次机会（避免漏设备）
        time.sleep(0.4)
        code, data = http_json(ip, "/api/state")
    if code in (200, 401) and isinstance(data, dict) and (
            "appVersion" in data or "ok" in data or "error" in data):
        out["ok"] = True
        out["code"] = code
        if code == 200:
            out["version"] = data.get("appVersion")
            out["questions"] = data.get("questionCount")
            out["pinDefault"] = data.get("pinDefault")
    return out


def adb_run(*args, timeout=20):
    try:
        p = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                           timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return "ERR %s" % e


def adb_info(ip):
    adb_run("connect", "%s:%d" % (ip, ADB_PORT))
    state = ""
    for m in re.finditer(r"^(%s:%d)\s+(\w+)$" % (re.escape(ip), ADB_PORT),
                         adb_run("devices"), re.M):
        state = m.group(2)
    if state != "device":
        return state or "offline", None
    model = adb_run("-s", "%s:%d" % (ip, ADB_PORT), "shell", "getprop",
                    "ro.product.model").strip()
    return state, model


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    with_adb = "--adb" in sys.argv
    as_json = "--json" in sys.argv
    cidr = args[0] if args else local_cidr()
    prefix = cidr.split("/")[0].rsplit(".", 1)[0]
    hosts = ["%s.%d" % (prefix, i) for i in range(1, 255)]
    if not as_json:
        print("扫描 %s.1–254（题锁端口 %d）…" % (prefix, APP_PORT))

    rows = []
    with ThreadPoolExecutor(max_workers=96) as ex:
        for r in ex.map(probe, hosts):
            if r["ok"]:
                rows.append(r)

    for r in rows:
        if with_adb:
            r["adb"], r["model"] = adb_info(r["ip"])

    if as_json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0 if rows else 1

    if not rows:
        print("没有发现题锁设备。检查：电视与电脑是否同一 WiFi；电视上是否已装并打开过题锁；"
              "路由器是否开启了「AP 隔离 / 访客网络」。")
        return 1
    print("\n发现 %d 台题锁设备：" % len(rows))
    for r in rows:
        extra = ""
        if with_adb:
            extra = "  adb=%s%s" % (r.get("adb"), (" (" + r["model"] + ")") if r.get("model") else "")
        print("  %-15s 版本=%-8s 题量=%-6s%s" % (
            r["ip"], r.get("version") or "需密码", r.get("questions") or "-", extra))
    return 0


if __name__ == "__main__":
    sys.exit(main())
