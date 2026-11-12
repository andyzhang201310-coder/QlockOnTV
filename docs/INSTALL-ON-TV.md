# 把「题锁」装到局域网里的电视上

题锁的后台就跑在电视本机的 8899 端口上，所以「这台电视装没装、装的是哪个版本」都能从局域网里问出来。
本文档给两条路：**能开网络调试的走 adb 一键装**，**开不了的走电视浏览器下载**。

---

## 0. 先说结论（最短路径）

先确认手上有一个 APK。仓库不提交安装包（`dist/` 在 `.gitignore` 里），自己打一个：

```bash
gradle :app:assembleRelease        # 产物 app/build/outputs/apk/release/app-release.apk
cp app/build/outputs/apk/release/app-release.apk dist/QlockOnTV-1.7.27.apk
```

或者把别人给你的 APK 用 `--apk <路径>` 指过去。然后在仓库根目录执行：

```bash
python tools/tv_install.py --auto --pin <管理密码>
```

它会自己扫局域网、找到装了题锁的电视、能连 adb 的直接覆盖安装并回读版本号；
装不上的会打印一段提示，告诉你改用浏览器下载页装。

只想看看有哪些设备：

```bash
python tools/tv_scan.py --adb
```

---

## 1. 扫描：局域网里到底有几台

```bash
python tools/tv_scan.py              # 扫本机所在网段（自动跳过 VPN 网卡）
python tools/tv_scan.py 192.168.3.0/24
python tools/tv_scan.py --adb        # 顺带 adb connect，能装的直接标出来
python tools/tv_scan.py --json       # 给脚本 / AI agent 读
```

判定标准是**真的从 `http://<IP>:8899/api/state` 拿到题锁的 JSON**，不是端口通就算——
家里开着代理（`HTTP_PROXY`）时，内网请求会被代理接走并返回 502，脚本已显式绕过代理。

输出示例：

```
发现 2 台题锁设备：
  192.168.3.15    版本=需密码      题量=-        adb=offline
  192.168.3.19    版本=1.7.27      题量=24704    adb=device (MuMu)
```

> 需要密码才能读版本是正常的（题锁不登录不给看题库和版本细节）。
> 传 `--pin` 或者用 `X-PIN` 头就能读到。

---

## 2. 一键安装（有 adb 的情况）

```bash
python tools/tv_install.py --auto                 # 扫描 + 全部安装
python tools/tv_install.py --ip 192.168.3.19      # 只装一台
python tools/tv_install.py --auto --pin 2345      # 装完回读版本，确认真的换上了
python tools/tv_install.py --auto --json          # 结构化结果，便于其他 agent 解析
```

它做的事，其实就是下面三行，你也可以手工执行：

```bash
adb connect <电视IP>:5555
adb install -r dist/QlockOnTV-1.7.27.apk
adb shell am start -n com.family.studytv/.MainActivity   # 覆盖安装会把进程停掉，拉起来
```

覆盖安装（`-r`）**不会清数据**：题库、管理密码、规则、错题库、学习统计都保留。

### 电视怎么开网络调试

多数国产电视 / 盒子：

1. 设置 → 关于本机 → 连点「版本号」7 次，打开开发者选项；
2. 开发者选项 → 打开「USB 调试」或「网络调试 / ADB 调试」；
3. 部分机型还要在「开发者选项」里允许「远程调试」或输入一次随机码。

开好后 `python tools/tv_scan.py --adb` 里那台会变成 `adb=device`（第一次连接电视上可能弹「允许调试」，
要用遥控器点允许）。

#### Sony（BRAVIA）专项

Sony 的菜单层级和国产电视不一样，按系统分两套：

**Android TV 版（2015–2020 年 BRAVIA）**

1. 设置 → **设备偏好设置** → 关于 → 连点「**版本**」7 次（提示"您已处于开发者模式"）；
2. 返回 → 设备偏好设置 → **开发者选项**；
3. 打开「**USB 调试**」；
4. 同一页往下找「**网络调试**」（部分机型写作 *ADB over network*）→ 打开。

> 只开「USB 调试」不会监听 5555，**必须再开「网络调试」**，这是 Sony 上最常见的坑。

**Google TV 版（2021 年起的 BRAVIA XR / 部分 2022+ 机型）**

1. 设置 → **系统** → 关于 → 连点「**版本**」7 次；
2. 返回 → 系统 → **开发者选项**；
3. 同样打开「USB 调试」+「通过网络调试」。

**开完后的第一次连接**

```bash
adb connect 192.168.3.15:5555      # 换成电视实际 IP
```

电视上会弹「允许 USB 调试吗？」，用遥控器勾选「**一律允许使用这台计算机进行调试**」再点允许，
否则电脑侧会一直停在 `unauthorized`。

**开发者选项里没有「网络调试」怎么办**（部分 Sony 固件被裁掉）

用一根 **USB 双公头线**（A 对 A）把电视 USB 口连到电脑：

```bash
adb devices                 # 应出现电视序列号
adb tcpip 5555              # 把 adbd 切到网络模式（只需做一次）
adb connect 192.168.3.15:5555
```

`adb tcpip 5555` 之后就能拔线走 WiFi，但**电视重启后恢复成 USB 模式**，需要重新插线执行一次。

**注意**：如果电视是 2014 年以前的 Sony 智能电视（Opera TV / 非 Android 系统），
既没有开发者选项也装不了 APK，只能放弃。判断方法：电视上有没有 Google Play 商店。

---

## 3. 电视开不了网络调试：走浏览器下载

```bash
python tools/tv_install.py --serve --serve-port 8080 --apk dist/QlockOnTV-1.7.27.apk
```

会打印（端口以 `--serve-port` 指定值为准，脚本默认是 8900）：

```
下载页已启动：http://192.168.3.211:8080  （电视浏览器打开这个地址）
直接用文件地址也行：http://192.168.3.211:8080/QlockOnTV-1.7.27.apk
```

电视上用自带浏览器（或在应用商店装个浏览器）打开这个地址 → 点「下载并安装」→
允许「安装未知来源应用」→ 覆盖安装完成。

电视上找不到浏览器时，还有两条：

- **U 盘**：把 `dist/QlockOnTV-1.7.27.apk` 拷到 U 盘插电视，用电视的「文件管理 / 本地媒体」打开安装；
- **电视助手类 App**：手机装「电视助手」类工具，把 APK 推到电视上安装。

---

## 4. 装完怎么确认

```bash
curl -H "X-PIN: <管理密码>" http://<电视IP>:8899/api/state
```

看 `appVersion` 是不是 `dist/` 里那个版本号；`questionCount` 应该是 19739。
浏览器直接打开 `http://<电视IP>:8899/?pin=<密码>` 也能看到页面底部/顶栏的版本。

---

## 5. 给其他 AI agent 的通用指令

把下面整段丢给任意一个能执行命令的 agent（它需要能访问本仓库目录和同一个局域网）：

> 请把「题锁」这个 Android App 覆盖安装到本局域网里所有电视上，并按下面顺序做：
>
> 1. 在仓库根目录执行 `python tools/tv_install.py --auto --json --pin <管理密码>`。
>    这个脚本会扫描 `192.168.x.0/24`，用 `http://<IP>:8899/api/state` 判断哪些设备装了题锁，
>    能连 adb 的直接 `adb install -r` 覆盖安装，并重新拉起 App。
> 2. 解析它输出的 JSON：`installed=true` 的就是装好的；`installed=false` 的设备，
>    说明电视没开网络调试（adbd 5555 不通）。
> 3. 对没装上的设备执行 `python tools/tv_install.py --serve`，把它打印的
>    `http://<电脑局域网IP>:8900` 交给用户，让用户在电视自带浏览器里打开并点「下载并安装」。
> 4. 最后逐台验证：`curl -H "X-PIN: <管理密码>" http://<设备IP>:8899/api/state`，
>    确认 `appVersion` 等于 `dist/` 里的最新版本号。
> 5. 约束：只做覆盖安装，不要卸载、不要清数据、不要改题库 / 密码 / 规则；
>    不要扫描或访问局域网以外的地址。

只需要一条命令时，用这个：

```bash
python tools/tv_install.py --auto --pin <管理密码>
```

---

## 6. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 扫描一台都没有 | 电视和电脑不在同一 WiFi；路由器开了「AP 隔离 / 访客网络」；电视上题锁从没打开过（服务没起） |
| 扫到但 adb 连不上 | 电视没开网络调试，走第 3 节浏览器下载 |
| `adb install` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 电视上装的是别的签名版本（比如别人改过的包），只能先卸载再装，卸载会清掉该 App 的数据 |
| 装完后台打不开 | 覆盖安装后进程被杀，重新在电视上打开一次「题锁」，或让脚本跑一次 `am start` |
| 扫描结果里有 502 | 电脑开着代理，脚本已绕开；手工 curl 时加 `--noproxy "*"` |
| 版本回读显示 `?` | 没传 `--pin`，或电视改过密码（出厂默认 1234） |
| 脚本说「找不到 APK」 | 仓库里没有打包产物，先 `gradle :app:assembleRelease`，或用 `--apk` 指定路径 |
