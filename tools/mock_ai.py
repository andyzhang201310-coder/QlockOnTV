"""本地假模型接口：只为了在模拟器/电视上验证 AI 讲解面板的完整链路。

用法：
    python tools/mock_ai.py 8890

然后在后台把 Base URL 填成 http://<本机局域网IP>:8890/v1，Key 随便填。
它只回固定的一段解析，不发真实请求、不花钱。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

# 故意放满 LaTeX：分数、上下标、乘号、根号、加粗，用来验证 RichText 渲染
REPLY = ("**先看正确答案为什么对。**\n"
         "因为 $4 \\times 12 = 48$，也就是 $12 + 12 + 12 + 12 = 48$。\n"
         "你选的 44 少算了一个 4：$44 = 4 \\times 11$，差了 $\\frac{1}{12}$ 那么多。\n"
         "下次可以先算 $12 \\times 2 = 24$，再乘 2 就是 $48$；"
         "也可以记 $\\frac{48}{12} = 4$，大概就是 $\\sqrt{16}$ 这么多步。")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n)
        try:
            req = json.loads(raw or b"{}")
        except Exception:
            req = {}
        user = ""
        try:
            user = req["messages"][-1]["content"][:60].replace("\n", " ")
        except Exception:
            pass
        print("[mock] model=%s auth=%s prompt=%s" % (
            req.get("model"), (self.headers.get("Authorization") or "")[:14], user))
        out = {"id": "mock", "choices": [{"index": 0, "message": {
            "role": "assistant", "content": REPLY}, "finish_reason": "stop"}]}
        data = json.dumps(out, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8890
    print("mock AI listening on 0.0.0.0:%d" % port, flush=True)
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
