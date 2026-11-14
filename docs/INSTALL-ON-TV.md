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

**v1.7.31 覆盖升级实测**（这台 Sony BRAVIA，2026-11-13）：

| 时点 | 版本 | `LEANBACK_LAUNCHER` 命中题锁数 | 结论 |
|---|---|---|---|
| 升级前 | 1.7.30 | 0 | 图标已隐藏 |
| `adb install -r dist/QlockOnTV-1.7.31.apk` | — | — | Success |
| 升级后（未重启、未手动改开关） | 1.7.31 | 0 | 隐藏状态自动保持 |

也就是：**以后只要用覆盖安装（`install -r` / 应用商店升级）发新版，图标会继续隐藏**，
不需要家长重新点一次开关。只有「卸载后重装」「清除应用数据」「恢复出厂」会清掉
`launcherHidden` 设定，装完需要重新打开开关。

### 日历计划（v1.7.31）：上学日 / 周末 / 假期自动切换

> **v1.7.32 起入口已合并**：下面说的「日历计划」分段已并入「规则管理 → 时间计划」同一页，
> 排到 ③ 今日时段表，诊断卡挪到该页最上方；旧书签 `#calendar` / `#rules-calendar` 自动跳转。
> 接口与判定顺序不变，旧的按星期时间表收进页面底部的折叠兼容区。

家长反馈的「时间计划没生效」，根因不是判定链路坏了，而是旧的「全天可用」开关默认开着，
会直接忽略下面配的起止时间与周计划表；再加上后台没有一处能直接看到「此刻到底会不会弹题」，
配完很难确认。这一版做了三件事：

1. **实时诊断**：规则管理 →「时间计划」第一张卡直接显示今天属于哪种日子、命中的规则、
   此刻是否允许弹题、下次允许弹题的时刻；`/api/state` 也回 `ruleReason / dayType`。
2. **日历计划接管时段判定**（默认开启）：上学日 07:00–17:30 大人豁免、17:30–23:00 弹题；
   周末与法定假期 06:00–23:00 弹题。四段时间都能在后台改，关掉开关即回到旧逻辑。
   免打扰 / 网课时段 / 按星期的大人豁免优先级仍高于日历计划。
3. **节假日识别**：内置 2027 年国务院放假调休表（断网可用），联网时每天最多同步一次
   holiday-cn 年度 JSON（默认 `https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json`），
   覆盖今年与明年；同步失败保留旧缓存，不会把日历弄空。手动标记日期优先级最高，
   用于寒暑假与临时调课。

接口：`GET /api/calendar`（诊断 + 日历条目）、`POST /api/calendar/sync`（立即同步）、
`POST /api/calendar/override`（标记/取消某天）、`POST /api/calendar/clear`（清同步缓存回到内置表）。

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


### 待机不唤醒（v1.7.33）：到点先看电视是不是睡着

给电视机装题锁最容易踩的坑不是「不弹」，而是**半夜自己把电视点亮**：答题页带
`FLAG_TURN_SCREEN_ON`，只要 Activity 一 create，面板就亮。v1.7.33 起把这条判定放到所有规则之前：

| 顺序 | 判定 | 结果 |
|---|---|---|
| 0 | 电视是否醒着（`DeviceAwake`） | 待机 / 休眠 → 不弹、不计轮数、不动 `dueAt`，只记一笔 `asleepDeferAt` |
| 1 | 总开关 / 自动弹题 | 关 → 不弹、不留闹钟 |
| 2 | 卸载放行窗口 | 家长正在走卸载流程 → 不弹 |
| 3 | 免打扰 / 例外时段 / 今日时段表 | 命中禁用 → 顺延到下一个允许时刻 |

实现要点：

- 判定两层：`PowerManager.isInteractive()` + `Display.getState()`（排除 `STATE_OFF` / `STATE_DOZE_SUSPEND`）。
  有些电视只关背光、`isInteractive` 仍为 true，只有一层会漏判；取不到状态时按「醒着」处理，
  宁可弹题也不让孩子靠异常逃避。
- 三处闸门：`GuardService.checkDue()`、`GuardService.showQuiz()`、`QuizActivity.onCreate()`。
  最后一处是兜底——即使系统把排队的 Activity 拉起来，待机状态下也会立刻 `finish()`。
- 唤醒补弹：`GuardService` 动态注册 `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`，亮屏约 1 秒后
  立刻跑一次 `checkDue()`，不再等 15 秒轮询，孩子开电视后没有空窗期。
- 后台联动：诊断卡新增「电视状态」行；`/api/test` 在待机时返回 `{ok:false, reason:"asleep"}`，
  页面提示「已延后到下次唤醒」，不会叫醒电视。

真机实测（Sony BRAVIA 4K GB ATV3 / Android 9，1.7.33）：

| 场景 | 操作 | 结果 |
|---|---|---|
| 待机信号 | `input keyevent KEYCODE_SLEEP` | `mWakefulness=Asleep`、`Display Power: state=OFF`，两层判定都翻成「待机」 |
| 待机 + 欠题重启 | 待机状态下让进程崩溃（`am crash`），前台服务自启 → `bootstrap` → `showSoon` | 30 秒后仍是 `Asleep` / `OFF`，`QuizActivity` 未拉起（前台还是电视桌面）。改动前这一步会把屏幕点亮 |
| 唤醒补弹 | `input keyevent KEYCODE_WAKEUP` | 6 秒内 `QuizActivity` 回到前台（新 task），待机期间欠的那一轮补上 |
| 覆盖安装 | 1.7.32 → 1.7.33 `install -r` | `versionName=1.7.33`（code 42）；`LEANBACK_LAUNCHER` 命中数仍为 0，隐藏状态保持 |
| 常规弹题 | 醒着状态下到点 | 答题页正常弹出，去掉 `FLAG_TURN_SCREEN_ON` 不影响正常显示 |

边界：

- 深度待机断网的机型，后台 `/api/state` 本身就连不上（既有边界，与本次改动无关）。
- 屏保 / Ambient 模式下屏幕是亮的，`isInteractive` 为 true，算「使用中」，会正常弹题——屏幕本来就亮。
- 待机期间到点不清零 `dueAt`：一开电视就补弹，这是有意为之，否则关电视就能逃过一整轮。

### 仍然想要硬锁的可行做法

1. 换设备：选支持 `device_admin` 特性的盒子 / 电视，装完执行 `dpm set-device-owner`。
2. 把题锁设为电视桌面（Home）通道：`adb shell cmd package set-home-activity com.family.studytv/.MainActivity`，
   孩子按 Home 只回到学习页、进不去系统设置；副作用是原生主页（流媒体入口）一起没了，执行前要确认。
   恢复：`adb shell cmd package set-home-activity <原桌面组件>`（原组件可用 `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME` 查）。
3. 家长侧兜底：定期在后台看 `/api/state` 的 `appVersion`，或在手机上用局域网扫描脚本 `python tools/tv_scan.py` 确认应用还在。

### 每日轮数用完锁屏（v1.8.1）：用满即整屏锁住

「每日轮数上限」在 v1.8.1 之前只是「今天不再弹题」，电视照看。打开这个开关后语义变为
**用满即锁屏**：当天弹满设定轮数后，电视一唤醒就进 `LimitActivity`，整屏只显示
「今天的次数已用完 / 明天 00:00 恢复 / 每日 N 轮 · 今天已弹 N 轮」，次日 0 点自动恢复。

| 触发路径 | 结果 |
|---|---|
| 电视唤醒（`ACTION_SCREEN_ON` / `USER_PRESENT`） | 立刻补一次检查 → 已用满则拉起锁屏页 |
| 到点该弹题 | 不再弹题，改为拉起锁屏页（判定在时段判断之前：次数用完了就不挑时段） |
| 点桌面图标 / 打开应用 | `MainActivity` → `gotoQuiz()` 前先判上限，已用满直接进锁屏页 |
| 后台「立即弹一次题」 | `/api/test` 返回 `{ok:true, limit:true}`，电视上弹的是锁屏页而不是题目 |
| 总开关 / 自动弹题关闭 | 一律不锁屏（家长暂停拦截期间不该被挡住） |

家长放行有两条路，效果完全相同（都是 `GuardService.applyUnlock()`：记「今天已放行」+ 从现在重排下一轮）：

- 手机后台「立即解锁」→ `/api/unlock`，响应里的 `limitPassed` 变成 `true`；
- 电视上连按 5 次「上键」→ 输管理密码 → 当天放行并打开信息页。

放行标记只对当天有效；改「轮数上限 / 每日轮数」会让它立刻作废（`Rules.clearLimitPass`），
否则家长调高上限后电视还停在锁屏页。

实现要点：

- `Rules.limitLocked()` 一处判定，`GuardService.checkDue / showQuiz`、`MainActivity.gotoQuiz`、
  `LimitActivity.onResume` 三处闸门共用，避免「某个入口漏了」。
- 与答题页同一套待机闸门：`LimitActivity.onCreate()` 发现电视待机直接 `finish()`，不会点亮面板。
- 锁屏页没有按钮、返回键吞掉、按 Home 逃到桌面 1.2 秒后拉回（家长操作期间不拉）。
- 后台同步可见：`/api/state` / `/api/rules` 新增 `roundsPerDay`、`limitLocked`、`limitPassedToday`；
  首页「今日轮数」显示成「10 / 8 轮 · 已用完 · 电视锁屏中」，信息页多一行「今日弹题：x / y 轮」。

实测（MuMu / Android 15，1.8.1）：把 `rules_round_count` 写到上限后启动应用，
`topResumedActivity=com.family.studytv/.LimitActivity`，版式与「还没到可用时间」一屏一致；
连按 5 次上键弹出管理密码键盘，输对后落回信息页且**不会再弹一道题**（放行同时重排了下一轮）。

边界：

- 锁屏页挡不住 `pm uninstall` 与恢复出厂设置，也挡不住家长本人在系统设置里操作。
- 电视待机期间不锁：待机本来就看不到画面，唤醒后由 `ACTION_SCREEN_ON` 补上。

### 答案选项视觉（v1.8.2）：默认无边框，只有选中项描边

旧版四个选项各带一圈 2dp 描边、18dp 大圆角，四框并排时页面很吵。v1.8.2 改为：

| 状态 | 外观 |
|---|---|
| 默认（未选中） | 只有一层卡片底色，**不画边框**，圆角 18dp → 10dp |
| 选中（遥控器焦点 / 按下） | 亮出 2dp 描边 + 略亮的填充，仍然只有这一条框 |
| 答错揭示 · 正确项 | 描绿框（2dp）+「← 正确答案」后缀 |
| 答错揭示 · 错选项 | 描红框（2dp）+「← 你选的」后缀 |
| 答错揭示 · 其它项 | 文字变暗、保持无框 |

五套护眼配色（米绿 / 羊皮纸 / 豆沙绿 / 雾蓝灰 / 夜间墨黑）共用同一套画法，
颜色全部取自 `Theme.Palette`，切风格时边框与填充一起换色，不会出现某套风格漏改。

实现要点：

- `Theme.optionBackground(view, palette, role)`：`role` 为 `ok` / `bad` 时描边取对应语义色，
  其余（含揭示时的 `dim`）走普通三态；默认态 `card(..., linePx = 0)` 不设描边。
- 三态圆角、边界完全一致，遥控器上下移动时框只换色不跳位（`ThemeTest` 逐套配色断言）。
- `bg_quiz_option.xml` 是 inflate 瞬间的兜底外观，同样无边框 + 10dp 圆角；
  管理页 / 密码键盘等按钮仍用原来的 `bg_option.xml`，不受影响。

### 圆角与阴影收口（v1.8.3）

三处细节统一：

| 位置 | 改前 | 改后 |
|---|---|---|
| 答案选项 | 浅色三套风格圆角外有一圈灰影 | 关掉 Button 自带的 2dp `elevation` 与按压抬升动画，边框外干净 |
| 底部反馈条（「答对了，继续下一题」） | 2dp 描边 + 16dp 圆角 | **无描边** + 10dp 圆角 |
| 学科标签（语文 · 4年级 ★★★） | 999dp 全胶囊 | 10dp 小圆角（保留强调色描边） |

阴影的成因值得记一笔：`android:style/Widget.DeviceDefault.Button` 自 API 21 起带
`android:elevation=2dp` + `stateListAnimator`，深色底（米绿 / 夜间墨黑）上几乎看不见，
换成羊皮纸 / 豆沙绿 / 雾蓝灰后，圆角外侧就浮出一圈灰边，看着像卡片没对齐。

- 样式层：`QuizOption` 加 `android:elevation=0dp`、`android:stateListAnimator=@null`；
- 代码层：`Theme.flatten(view)` 在每次渲染选项时再兜一次（重画 / 换配色都不会漏）；
- XML 兜底：`bg_feedback.xml` 去描边、圆角 16dp → 10dp；`bg_tag.xml` 圆角 999dp → 10dp。

单测覆盖（`QuizActivityTest`）：选项 `elevation == 0` 且 `stateListAnimator == null`、
学科标签圆角 10dp 且有描边、反馈条圆角 10dp 且无描边。

### 全站圆角统一 6dp（v1.8.4）

电视端所有带圆角的控件统一到 **6dp**，不再出现「选项 10dp / 卡片 20dp / 胶囊 999dp」混排：

| 位置 | 原圆角 |
|---|---|
| 答案选项（`bg_quiz_option` / `Theme.optionBackground`） | 10dp |
| 底部反馈条（`bg_feedback`） | 10dp |
| 学科标签（`bg_tag`） | 10dp |
| 普通按钮 / 密码键盘（`bg_option`） | 18dp |
| 卡片（`bg_card`：信息页、安全设置、PIN 面板） | 20dp |
| 对话框（`bg_dialog`） | 24dp |
| 二维码白底（`bg_qr`） | 24dp |
| 状态胶囊（`bg_pill`：管理地址） | 999dp |
| 主按钮（`bg_button_primary`：渐变 + 聚焦光晕层） | 19dp / 16dp |

改法：drawable 里的 `android:radius` 一次性替换为 6dp；Java 侧只有两个入口 ——
`Theme.OPTION_RADIUS_DP = 6f`（选项）与 `QuizActivity.applyTheme()` 里
`Theme.card(..., 6f, ...)`（学科标签、反馈条）。

二维码、时钟徽标这类图形本身（vector path）不属于「圆角」，未改动；
管理后台网页（`admin.html`）的 CSS 圆角属于另一套视觉体系，也没动。

单测：`ThemeTest` 断言五套配色的选项圆角都等于 6dp；`QuizActivityTest` 断言
学科标签与反馈条圆角 6dp。

### LaTeX 公式（v1.9.1）：题干、选项、反馈、AI 解答统一渲染

模型输出数学题时爱写 `$\frac{1}{2}$`。电视端不引入 WebView 或 KaTeX——那要拖进整个浏览器内核
（首屏 1–2 秒、几十 MB 内存），而这里只需要小学到初中的那点符号。`RichText` 用
`ReplacementSpan` 把分数画成真分数，其余走 Unicode 与字号位移，零第三方依赖。

支持的写法（`$…$`、`$$…$$`、`\(…\)`、`\[…\]` 四种定界符都认）：

| 类别 | 写法 | 屏上效果 |
|---|---|---|
| 分数 | `\frac{1}{2}` `\dfrac{a}{b}` | 分子 / 横线 / 分母，两行小字自绘 |
| 根号 | `\sqrt{2}` `\sqrt{x+1}` `\sqrt[3]{8}` | `√2`、`√(x+1)`、`³√8` |
| 上下标 | `x^2` `a^{10}` `y_1` | 缩小字号并上/下移 |
| 运算符 | `\times \div \pm \cdot` | `× ÷ ± ·` |
| 关系 | `\le \ge \ne \approx \equiv` | `≤ ≥ ≠ ≈ ≡` |
| 几何 | `\angle \triangle \perp \parallel \degree` | `∠ △ ⊥ ∥ °` |
| 字母 | `\pi \alpha \theta \Delta \Sigma` | 对应希腊字母 |
| 文本 | `\text{所以答案是}` | 原样输出，中文照常 |
| 排版命令 | `\left( \right)` `\quad` `\,` | 只当括号/空格，不显示命令 |

另外把 AI 最常输出的 `**加粗**` 一并处理成粗体并去掉星号。

几个必须守住的边界：

- **没有公式时原样透传**：全库 24704 道题里只有 656 道带标记（数学题的分数与上标，见下文 v1.10.0），
  其余 24048 道一条 LaTeX 都没有（乘除号是 Unicode `×÷`）；渲染器在 `hasMarkup()` 为假时
  返回**同一个对象**，零拷贝、零观感变化。
- **不成对的 `$` 不吃掉**：`价格 5$ 元` 这种文本原样保留，避免「5 美元」被静默改坏。
- **未知命令保留名字**：`\foo` 显示成 `foo`，不吞内容，也不抛异常（`\begin{matrix}` 之类只降级不崩）。
- **含分数的行自动抬高**：上下两行小字要占约 1.5 倍行高，不抬会把分母压到下一行文字上。
- **字号联动**：分数比例全部相对 `paint.getTextSize()` 取，学习页字号调到 200% 时一起变大。

接入点四处，都是同一函数：题干 `tvQuestion`、四个选项（保留字母徽标与悬挂缩进）、
底部反馈条、AI 讲解面板的正文与「正确答案 / 你选了」那一行。

实测（MuMu / Android 15）：临时换一份只含公式的题库后，`计算：½ + ¼ = ?` 的题干与四个
分数选项、`√16 + 2³` 的根号与上标、答错反馈里的「正确答案是 C、¾」全部按公式排版；
AI 面板里 `4 × 12 = 48`、`\frac{48}{12}`、`√16` 与加粗标题同时正确，行距不重叠。

### 数学题库 LaTeX 化（v1.10.0）：分数不再是斜杠

v1.9.1 让渲染器认得 LaTeX，但题库里还是 `6/8 + 1/8 = ?` 这种斜杠写法——渲染器无从下手，
孩子看到的仍是「6 除以 8」。v1.10.0 直接把数学题库改成 LaTeX：

| 原来 | 现在（题库里存的） | 电视上显示 |
| --- | --- | --- |
| `6/8 + 1/8 = ?` | `$\frac{6}{8}$ + $\frac{1}{8}$ = ?` | 两个上下两行的真分数相加 |
| `25/100`（选项） | `$\frac{25}{100}$` | 竖排分数选项 |
| `πr²` | `π$r^{2}$` | r 右上角的小 2 |
| `x/2 + 3 = 8` | `$\frac{x}{2}$ + 3 = 8` | x 在上、2 在下 |

规模：**656 道题、3505 处分数、27 处上标**。语文、英语一道题都没动（脚本只扫 `subject=="数学"`）。

转换规则都写了脚本 `tools/latexify_math.py`，可重复执行：

```bash
python tools/latexify_math.py --check     # 只统计 + 抽样 + 自检，不写文件
python tools/latexify_math.py --apply     # 写回 assets/questions.json，并把 version +1
```

几条边界：

- **前后不能贴数字和小数点**：`(?<![\d./])\d+/\d+(?![\d./])`，避免把 `3.6/1.5`、`1/2/3` 切坏；
- **只认 19 种真实上下文**：`用去 2/5，` `6/8 + ` `的 1/2 是` 这类，全是空格或中文标点夹着的干净分数；
- **`×÷: %` 不动**：这些本来就是正常 Unicode 字符，包进 `$...$` 只是多一层解析、屏上观感不变；
- **`²` `³` 要换**：部分电视字体缺这两个字形会显示成方块，换成 `$r^{2}$` 走真上标更保险；
- **可重复执行**：已含 `$` 的字段原样跳过，第二次跑 `--check` 应该是「改动 0 题」。

#### 电视上的题库怎么拿到新题面

内置题库的 `version` 从 **7 升到 8**。`QuestionBank.ensureSeeded()` 的规则是
「设备上的文件版本 < 内置题库版本」才覆盖，**只改题面不升版本，电视永远看不到新题面**，
所以改题库必须连 `version` 一起 bump（单测 `latexBankVersionBumped` 就是钉这件事）。

两种情况例外，都需要手动推一次：

- 家长在后台改过题库（`bankCustom=true`）→ 应用按设计不覆盖，点「恢复内置题库」即可；
- 想立刻确认电视上是新版：后台 → 题库管理 → 搜 `g5-math-03` → 点「编辑」，
  题干显示 `$\frac{6}{8}$ + $\frac{1}{8}$ = ?` 就是新版（列表里会降级显示成 `6/8 + 1/8 = ?`）。

#### 后台页面也跟着改了

题库列表和错题集不跑 LaTeX 渲染，加了一层 `prettyMath()` 降级：`$\frac{3}{4}$` → `3/4`、
`$r^{2}$` → `r²`，家长不会在列表里看到一堆反斜杠；题库编辑器的题干仍是**原始文本**
（否则一保存就把 LaTeX 写没了），下面补了一行写法提示。

#### 系统设置页改版（同一版本）

原来是一列 `.card` 通栏卡片，现在与「规则管理」完全同构：顶部状态条 + 四个分段
（账号与端口 / AI 错题讲解 / 防卸载保护 / 保护说明），每段是「左标题 + 一句说明 + 右侧控件」的行式布局，
开关与步进器一律靠右。深链 `#system-ai`、`#system-guard`、`#system-docs` 可以直接跳分段，
老链接 `#sec-device` 仍落到系统设置（默认第一段）。

### 题库升级被「家长改过题库」挡住（v1.10.1）：按 id 精确迁移

v1.10.0 把数学题库改成 LaTeX 并升了 `version`，但有台电视装完仍是 `6/8 + 1/8 = ?`。
导出设备题库一查：`version 7`、24704 题、0 道含 `$`——**新题库根本没下去**。

`QuestionBank.ensureSeeded()` 原本是这么写的：

```java
if (file.exists() && file.length() > 32) {
    if (Prefs.bankCustom()) { return; }   // 家长改过题库就绝不动
    if (versionOf(cur) >= versionOf(asset)) { return; }
    atomicWrite(file, asset);             // 整库覆盖
}
```

`bankCustom` 只要家长在后台**保存过一次题目**就会置 true，而且永不复位。
那台电视的来历是：有人把 `g6-chinese-997`（「亲切」的近义词）的选项 A 从
「亲热」改成了「亲近」——整库 24704 题里只有这一处非公式差异，
却让此后所有内置题库升级全部被跳过。

#### 修复：不整库覆盖，只迁移「确认为官方旧版」的题面

`migrateMathText(cur, asset)` 在 `bankCustom=true` 且版本落后时执行：

1. 按 `id` 把设备题和内置题配对，家长自己加的题（id 不在内置库）直接跳过；
2. 把新版 LaTeX 题面**降级回旧写法**：`$\frac{6}{8}$` → `6/8`、`$r^{2}$` → `r²`；
3. 只有当「设备里的文本 == 降级文本」时才替换成 LaTeX 版。家长改过的题面、
   改过的选项一律原样保留（`pristine=false`）；
4. 若整份题库确认无任何自定义痕迹（`pristine=true`），直接用内置题库原文覆盖，
   连 `name` / `note` / `version` 一起对齐；
5. 无论哪条路，最后都把 `version` 写成内置版本，避免每次启动都重扫 4.5 MB；
6. 解析失败或内存不足时返回 `null`，宁可不升级也绝不写坏家长的题库。

真机结果：`version 7 → 8`、24704 题不变、656 道含 LaTeX、
「亲近」原样保留、`bankCustom` 仍为 true（以后升级继续走迁移路径）。

`/api/state` 顺带补了 `bankVersion` / `bankCustom` 两个诊断字段，
以后不用导出 4.5 MB 题库也能一眼看出设备停在哪一版。`bankVersion` 只扫文件头
256 字符，不会每次轮询都全量 parse。

> 如果不想等自动迁移，后台「题库管理 → 恢复内置题库」仍可一键对齐，
> 但那会**清掉家长所有的自定义题目**；自动迁移不会。

### 短答案两列显示（v1.10.1）

真分数行高是普通文字的两倍，四个分数选项竖排会把最后一项挤出首屏
（实测答错揭示态下 D 的分母被裁掉一半）。现在短答案自动排成 2×2：

| 判据 | 结果 |
| --- | --- |
| 2–4 个选项，且**每个**渲染后可读文字 ≤ 12 字 | 两行两列 |
| 任一选项超长（诗句、成语解释） | 回退原来的四行 |
| 分数 `$\frac{17}{30}$`、上标 `π$r^{2}$` | 按渲染后长度算，都是短答案 |

实现要点：

- `optionBox` 仍是 vertical `LinearLayout`，两列时每行塞一个 horizontal 行容器，
  两个 Button 各 `width=0dp / weight=1`；奇数个选项补一个等权 `Space` 对齐；
- 加行容器后 `optionBox.getChildAt(k)` 不再是 Button，所有按索引取按钮的地方
  （重绘、揭示答案、兜底焦点）统一走 `optionButtons()` 递归收集；
- 遥控器四向焦点用 `setNextFocusRightId/DownId/...` 显式按行列绑定，
  不依赖系统按坐标猜；
- 两列只有半屏宽，左右 padding 从 30dp 收到 16dp；含公式时再叠一层
  上下 padding 与额外行距的收紧；
- 垂直位置：在 `optionBox` 上方加一个默认 weight=0 的弹性 `Space`，
  两列时按 0.7:1 与下方原有弹性空间分配剩余高度，选项组整体下移、
  上下留白均衡；长答案内容超一屏时两个 Space 自动让位，不影响滚动。
  选年级页与四行布局把 weight 置回 0，观感不变；
- 两排卡片间距：首行与题干保持 5dp，第二行起 topMargin 加到 16dp，
  否则 2×2 的上下两排会几乎贴在一起；
- 反馈条和 AI 按钮一起出现会挤矮滚动区，此时收起底部操作提示，
  把高度还给选项区。

单测覆盖判据本身（短/长/边界/题库真实分数题），电视实拍确认正常态与
答错揭示态下 2×2 四个选项、反馈条里的 `1/15` 全部完整显示。
