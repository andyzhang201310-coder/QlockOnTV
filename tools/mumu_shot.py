"""从 MuMu 模拟器抓屏到本机。

MuMu 的 sh 通道没有 adb pull、也没有共享目录，所以流程是：
  模拟器 screencap 存到 /sdcard → toybox nc 推到宿主机 TCP 监听 → 本脚本收下写文件。

用法：
  python tools/mumu_shot.py shot.png
  python tools/mumu_shot.py shot.png --host 192.168.3.211 --port 19123
"""
import argparse
import re
import socket
import subprocess
import sys
import threading
import time

MUMU = r"C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe"
REMOTE = "/sdcard/mumu_shot.png"


def sh(cmd, device="0"):
    return subprocess.run(
        [MUMU, "sh", "-v", device, "-c", cmd],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60
    ).stdout.decode("utf-8", "replace")


def detect_host(device):
    """选与模拟器同网段的宿主机出口 IP（UDP connect 只选路由、不发包）"""
    out = sh("ip addr show wlan0", device)
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", out)
    if not m:
        raise RuntimeError("拿不到模拟器 IP：" + out[:200])
    dev_ip = m.group(1)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((dev_ip, 1))
    host = s.getsockname()[0]
    s.close()
    return host


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", nargs="?", default="shot.png")
    ap.add_argument("--host", default=None)
    ap.add_argument("--port", type=int, default=19123)
    ap.add_argument("--device", default="0")
    args = ap.parse_args()

    host = args.host or detect_host(args.device)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(1)
    srv.settimeout(40)

    def trigger():
        time.sleep(0.4)
        cmd = "screencap -p %s; toybox nc %s %d < %s" % (
            REMOTE, host, args.port, REMOTE)
        sh(cmd, args.device)

    threading.Thread(target=trigger, daemon=True).start()
    conn, addr = srv.accept()
    data = bytearray()
    with conn:
        conn.settimeout(30)
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                break
            data += chunk
    srv.close()
    if len(data) < 1000:
        print("截图数据异常：%d bytes" % len(data), file=sys.stderr)
        return 1
    with open(args.out, "wb") as f:
        f.write(data)
    print("saved %s  %d bytes  (host %s, from %s)" % (args.out, len(data), host, addr[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
