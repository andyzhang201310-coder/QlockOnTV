# 把「题锁」装到局域网里的电视上

题锁的后台就跑在电视本机的 8899 端口上，所以「这台电视装没装、装的是哪个版本」都能从局域网里问出来。
本文档给两条路：**能开网络调试的走 adb 一键装**，**开不了的走电视浏览器下载**。

---

## 0. 先说结论（最短路径）

先确认手上有一个 APK。仓库不提交安装包（`dist/` 在 `.gitignore` 里），自己打一个：

```bash
gradle :app:assembleRelease        # 产物 app/build/outputs/apk/release/app-release.apk
cp app/build/outputs/apk/release/app-release.apk dist/QlockOnTV-1.7.28.apk
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
  192.168.3.19    版本=1.7.28      题量=24704    adb=device (MuMu)
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
adb install -r dist/QlockOnTV-1.7.28.apk
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
python tools/tv_install.py --serve --serve-port 8080 --apk dist/QlockOnTV-1.7.28.apk
```

会打印（端口以 `--serve-port` 指定值为准，脚本默认是 8900）：

```
下载页已启动：http://192.168.3.211:8080  （电视浏览器打开这个地址）
直接用文件地址也行：http://192.168.3.211:8080/QlockOnTV-1.7.28.apk
```

电视上用自带浏览器（或在应用商店装个浏览器）打开这个地址 → 点「下载并安装」→
允许「安装未知来源应用」→ 覆盖安装完成。

电视上找不到浏览器时，还有两条：

- **U 盘**：把 `dist/QlockOnTV-1.7.28.apk` 拷到 U 盘插电视，用电视的「文件管理 / 本地媒体」打开安装；
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

## 7. 防卸载：哪些电视能做、哪些做不了

防卸载依赖 Android 的 DevicePolicyManager，分两级：

| 级别 | 所需条件 | 效果 |
| --- | --- | --- |
| 设备管理员 | 系统有 `android.software.device_admin` 特性 | 卸载前必须先「停用设备管理员」，孩子走不完 |
| 设备所有者 | 同一特性 + 电视上没有登录账号 + 单用户 | 系统「卸载」按钮直接置灰，必须回应用输卸载码 |

先查这台电视支不支持：

```bash
adb shell pm list features | grep device_admin
adb shell dumpsys device_policy | head -6      # Enabled Device Admins 下面有没有条目
```

### 已实测：Sony BRAVIA 4K GB ATV3（Android 9）不支持

- `pm list features` 里**没有** `android.software.device_admin`。
- 设置里没有 `DeviceAdminAdd` 页面，隐式 Intent 和 5 个候选组件都 `ActivityNotFoundException`。
- `adb shell dpm set-active-admin ...` 会打印 `Success`，但 `dumpsys device_policy` 的
  `Enabled Device Admins (User 0)` 始终为空，`/data/system/device_policies.xml` 也不生成 —— 命令假成功。
- `adb shell dpm set-device-owner ...` 直接抛 `Can't set package ... as device owner`。
- 应用内用反射调 `setActiveAdmin`（`POST /api/admin/activate`）同样不落地，返回激活码 `code=3`。
- `adb shell pm disable-user com.android.tv.settings/<activity>` 被拒：
  `SecurityException: Shell cannot change component state for ... to 3`（系统应用组件 shell 无权改）。
- 该机 `adb root` 不可用（`adbd cannot run as root in production builds`），也没有第三方 DPC。

结论：**这一机型在系统层无法阻止卸载**。设备管理员 / 设备所有者都走不通，只能改用下面第 3 条「界面拦截」。

### 界面拦截（无障碍）— v1.7.29 起，这类机型的唯一解

思路：系统卸载界面由 `com.android.packageinstaller` 的 `UninstallerActivity` 呈现，第三方应用拿不到回调，
但**无障碍服务能看到这个窗口**。看到就执行一次「返回」，把卸载流程顶掉；只有在本应用里输对卸载码后
（写入 10 分钟放行窗口），才允许系统卸载界面停留。

开启方式（二选一）：

```bash
# 方式 A：电视上有无障碍设置页
# 电视 → 设置 → 无障碍 → 题锁 → 打开

# 方式 B：没有设置页（Sony BRAVIA / 多数 Android TV）用 adb
adb shell settings put secure enabled_accessibility_services \
  com.family.studytv/com.family.studytv.UninstallGuardService
adb shell settings put secure accessibility_enabled 1
```

方式 B 的两个坑（都实测过）：

- Android TV 的 `android.settings.ACCESSIBILITY_SETTINGS` 只解析到
  `com.google.android.tv.frameworkpackagestubs.Stubs$SettingsStub`，是个占位页。应用会识别这种情况，
  直接给 adb 命令，不再打开空白页。
- Android 9 的无障碍管理器**不会因为 settings 变化立刻绑定服务**：写完后 `dumpsys accessibility`
  里 `services:{}` 仍为空。**关机再开一次**，开机扫描时才绑定；之后一直有效。

验证：

```bash
adb shell dumpsys accessibility | head -6
# 应出现 services:{Service[label=题锁 防卸载保护, ...]}

adb shell am start -a android.intent.action.DELETE -d package:com.family.studytv
# 界面应该在 0.1~0.3 秒内消失，焦点回到桌面/设置页
```

后台 `/api/state` 里 `a11yGuard` 为 `true`，`a11yBlockCount` 随每次拦截 +1。

已知边界（不掩饰）：

- **开启它会改变电视桌面的按键体验（实测，最容易踩的坑）**：只要系统里绑定了无障碍服务，
  Google TV Launcher 的「全部应用」页就会把应用图标的**单击确认键**改成长按式上下文菜单
  （打开 / 移动 / 添加到收藏夹 / 信息 / 卸载），点一下确认进不了应用，要再选一次「打开」。
  这与服务能力无关：把 `canRetrieveWindowContent=false`、去掉 `flagRetrieveInteractiveWindows`
  并重启到 `capabilities=0` 后仍复现，且拦截判定同时失效。真正解绑（`services:{}`）后，
  同一按键立即恢复为 `MAIN+LEANBACK_LAUNCHER` 直接启动应用。
  结论：这台 Sony BRAVIA 上「界面拦截」与「桌面单击直接打开」**二选一**，用现有机制无法两全。
  2026-11-13 按家长选择保留桌面体验，界面拦截处于关闭状态（`a11yGuard=false`），
  应用内卸载码仍有效。若日后重新开启，需接受桌面多一次按键。
- **更新应用后要重新开**：`install -r` 会让系统解绑无障碍服务（实测 `services:{}` 归零），
  需要再执行一次命令并重启。
- **孩子若会进无障碍设置关掉它，就失效**（这台电视没有设置页，反而关不掉）。
- 拦截粒度是「卸载确认界面」，不影响正常安装 / 更新（`PackageInstallerActivity` 不在拦截范围，有单测覆盖）。
- 拦不住 `adb shell pm uninstall` —— 那是 shell 权限，不是小孩的路径。
- 答题页会抢占前台。输对卸载码时 `release()` 会同时清掉待答状态并写入放行窗口，
  避免答题页把系统卸载界面盖到后面（v1.7.29 修复）。


### 隐藏应用（v1.7.30 起，这台电视上推荐优先用这条）

前面两条路都有代价：设备管理员在这台 Sony 上是空壳，界面拦截会破坏桌面
「选中应用点确认直接打开」。换一个思路更干净：**让孩子压根找不到这个应用，也就找不到卸载入口**。

做法：把桌面启动入口从 `MainActivity` 挪到 `activity-alias .LauncherAlias`。隐藏时应用对自己调用
`setComponentEnabledSetting(alias, DISABLED, DONT_KILL_APP)` —— 普通应用改自己的组件**不需要任何系统权限**，
不碰设备管理员、不碰无障碍。`MainActivity` 本身始终 enabled，后台 `GuardService`、开机广播、
`QuizActivity` 都不受影响，答题锁照常按时间弹出。

开启方式（二选一）：

- 手机管理页 → 防卸载 → 打开「隐藏桌面图标」开关（接口 `POST /api/admin/icon {"hidden":true}`）
- 电视端 → 防卸载设置 → 「隐藏桌面图标」按钮

恢复（隐藏后本机没有图标，这两条是家长入口）：

```bash
# 方式 A：手机管理页把「隐藏桌面图标」开关关掉
# 方式 B：电脑执行，显式启动 MainActivity（它没有 launcher filter，但 exported=true）
adb shell am start -n com.family.studytv/.MainActivity
```

`App.onCreate()` 与 `BootReceiver` 都会调 `LauncherIcon.enforce()`：应用更新或开机后如果系统把
组件状态重置，会按家长设定自动重新套用，不会「更新一次就冒出来」。

Sony BRAVIA 4K GB ATV3 / Android 9 实测（v1.7.30）：

| 检查项 | 结果 |
|---|---|
| 桌面「全部应用」 | 21 个图标里没有题锁（`LEANBACK_LAUNCHER` 命中数 12 → 0） |
| 系统「设置 → 所有应用」 | 滚到底共 18 个已安装应用，没有题锁，也就点不到卸载按钮 |
| 答题锁 | 隐藏状态下仍自动弹出答题页（实测 QuizActivity 在前台） |
| 桌面确认键 | 弹窗数 0，选中应用点确认直接打开，没有回到上下文菜单 |
| 恢复 | 管理页关开关后命中数回到 12；`am start -n .MainActivity` 可直接进入 |
| 进程重建 | `am force-stop` 后重新启动，隐藏状态保持 |

为什么设置里也看不到：这台电视的「所有应用」页只列**有 launcher 入口的应用**，不是枚举所有已安装包；
禁用别名后它和桌面一起被过滤掉。

已知边界（不掩饰）：

- 第三方应用商店（当贝市场 / 应用助手等）里的「应用管理 / 卸载」有可能仍按包列表枚举到题锁，
  这次没有逐个验证。孩子要主动想到去商店里翻，路径比桌面远得多。
- 「显示系统应用」开关打开后的完整列表未逐项验证；题锁是第三方应用，默认列表本就包含第三方，
  这个开关只额外加入系统应用，不会把它加回来。
- 拦不住 `adb shell pm uninstall`，也拦不住恢复出厂设置 —— 都不是小孩的路径。
- 隐藏后家长自己也要走上面两条恢复路径，别把管理密码和 adb 都忘了。


### 仍然想要硬锁的可行做法

1. 换设备：选支持 `device_admin` 特性的盒子 / 电视，装完执行 `dpm set-device-owner`。
2. 把题锁设为电视桌面（Home）通道：`adb shell cmd package set-home-activity com.family.studytv/.MainActivity`，
   孩子按 Home 只回到学习页、进不去系统设置；副作用是原生主页（流媒体入口）一起没了，执行前要确认。
   恢复：`adb shell cmd package set-home-activity <原桌面组件>`（原组件可用 `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME` 查）。
3. 家长侧兜底：定期在后台看 `/api/state` 的 `appVersion`，或在手机上用局域网扫描脚本 `python tools/tv_scan.py` 确认应用还在。
