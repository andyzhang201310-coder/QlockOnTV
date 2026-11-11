# -*- coding: utf-8 -*-
"""无头 Chrome 交互探针：打开页面 → 执行 JS（点按钮等）→ 截图 / 取返回值。

只在本地开发时用：给「后台页面点了按钮之后长什么样」这类问题取证，不参与打包。

用法:
    python tools/page_probe.py --url "http://192.168.3.19:8899/?pin=2345#bank" \
        --js "document.querySelector('#qList button[data-act=edit]').click()" \
        --wait 1.2 --shot .tmpcheck/inline.png --print \
        --after "var r=document.querySelector('.qedit'); return r ? r.previousElementSibling.className : 'none';"
"""
import argparse
import base64
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request

CHROME_PATHS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/usr/bin/google-chrome",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]


class WS(object):
    """够用的 WebSocket 客户端：只处理文本帧。"""

    def __init__(self, url):
        assert url.startswith("ws://")
        rest = url[5:]
        hostport, path = rest.split("/", 1)
        path = "/" + path
        host, port = hostport.split(":")
        self.sock = socket.create_connection((host, int(port)), timeout=20)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
               "Sec-WebSocket-Version: 13\r\n\r\n") % (path, hostport, key)
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        assert b"101" in buf.split(b"\r\n")[0], buf[:200]
        self.buf = buf.split(b"\r\n\r\n", 1)[1]

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise IOError("socket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, text):
        payload = text.encode()
        header = bytearray([0x81])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv(self):
        while True:
            b1, b2 = self._read(2)
            opcode = b1 & 0x0F
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            data = self._read(length)
            if opcode == 0x1:
                return data.decode()
            if opcode == 0x8:
                raise IOError("closed by peer")

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


class CDP(object):
    def __init__(self, ws):
        self.ws = ws
        self.id = 0

    def call(self, method, **params):
        self.id += 1
        self.ws.send(json.dumps({"id": self.id, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.id:
                if "error" in msg:
                    raise RuntimeError("%s -> %s" % (method, msg["error"]))
                return msg.get("result", {})

    def js(self, expression):
        r = self.call("Runtime.evaluate", expression=expression, returnByValue=True,
                      awaitPromise=True)
        return r.get("result", {}).get("value")


def find_chrome():
    for p in CHROME_PATHS:
        if os.path.exists(p):
            return p
    raise SystemExit("找不到 Chrome，请用 --chrome 指定路径")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--js", default="")
    ap.add_argument("--after", default="")
    ap.add_argument("--wait", type=float, default=1.0)
    ap.add_argument("--shot", default="")
    ap.add_argument("--print", dest="do_print", action="store_true")
    ap.add_argument("--size", default="1440,1600")
    ap.add_argument("--chrome", default="")
    args = ap.parse_args()

    chrome = args.chrome or find_chrome()
    port = 9333
    profile = tempfile.mkdtemp(prefix="cdp-")
    w, h = (args.size.split(",") + ["1600"])[:2]
    proc = subprocess.Popen([
        chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars",
        "--no-proxy-server", "--remote-debugging-port=%d" % port,
        "--user-data-dir=%s" % profile, "--window-size=%s,%s" % (w, h),
        "--no-first-run", "--no-default-browser-check", "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        target = None
        for _ in range(60):
            try:
                with urllib.request.urlopen("http://127.0.0.1:%d/json/list" % port, timeout=2) as r:
                    pages = json.loads(r.read().decode())
                for p in pages:
                    if p.get("type") == "page":
                        target = p["webSocketDebuggerUrl"]
                        break
                if target:
                    break
            except Exception:
                pass
            time.sleep(0.3)
        if not target:
            raise SystemExit("Chrome 调试端口没起来")

        ws = WS(target)
        cdp = CDP(ws)
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")
        cdp.call("Page.navigate", url=args.url)
        time.sleep(args.wait + 2.0)
        if args.js:
            if args.do_print:
                print("js ->", cdp.js(args.js))
            else:
                cdp.js(args.js)
            time.sleep(args.wait)
        if args.after:
            print("after ->", cdp.js(args.after))
        if args.shot:
            data = cdp.call("Page.captureScreenshot", format="png",
                            captureBeyondViewport=True)["data"]
            with open(args.shot, "wb") as f:
                f.write(base64.b64decode(data))
            print("shot ->", args.shot, os.path.getsize(args.shot), "bytes")
        ws.close()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
