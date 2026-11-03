# DSH Mobile

<img src="tools/icon-master.png" width="96" alt="DSH Mobile whale icon" align="right" />

一个给 **DeepSeek Harness（DSH）** 用的 Android 局域网 WebView 外壳。

首次打开时填一个 DSH 地址（例如 `http://192.168.1.20:3080`），之后启动会自动进入这个地址。DSH 的页面、登录态和移动端布局都由网页自己负责，这个 App 只提供原生 WebView、导航和必要的基础能力。

> 本机没有 Android SDK，按要求也没有安装安卓开发环境，因此仓库里暂时没有预编译 APK。下方提供了 **GitHub Actions 云端打包**，不需要本机装 Android Studio 或 SDK。

---

## 功能

- 首次配置 DSH 局域网地址，保存后自动连接
- **一键扫描局域网**，自动列出同网段里在跑的 DSH 服务（显示页面标题，可区分是不是 DSH）
- **最近使用**：连接过的地址自动保存（最多 8 条），点击即连，长按移除
- **连接前预检**：先探一下端口，把「没监听 / 超时 / 主机名错」直接显示在配置页，而不是进去看空白页
- **断网自动重连**：网络恢复时，若当前停在错误页会自动重试一次
- 支持 `http://` / `https://`，自动补全 `http://`
- 支持局域网明文 HTTP（Android 默认会拦截，工程已配置 network security config）
- WebView 启用 JavaScript、DOM Storage、Cookie、数据库和页面缩放
- 顶部栏：后退、刷新、设置
- 设置项：修改地址、系统浏览器打开、清除缓存、清除 Cookie/站点数据、清除已信任的 HTTPS 证书
- 网页文件上传（`<input type="file">`）
- 网页下载走系统 DownloadManager，保存到 `Download/DSH Mobile/`
- 网页请求摄像头/麦克风时按需申请系统权限
- 主页面加载失败时显示可读错误页，可重试或修改地址
- 自签名 HTTPS 证书：首次访问弹一次提示，确认后继续并记住该地址，之后不再打断
- 不内置 DSH、不代理流量、不申请不必要的权限
- 纯 Java + Android Framework，无 Google Play Services、无原生 so、无第三方运行时

## 兼容性

| 项目 | 值 |
| --- | --- |
| 最低 Android | Android 6.0 / API 23 |
| targetSdk | 34 |
| compileSdk | 34 |
| 构建插件 | AGP 8.5.2 |
| 构建 Gradle | 8.7 |
| JDK | 17 |
| 原生库 | 无（因此不受 ABI / 16KB page size 影响） |
| 谷歌服务 | 无 |
| 鸿蒙 | 通过「卓易通」的 Android 兼容环境运行 |

卓易通兼容性方面刻意做了这些取舍：

- 不依赖 AndroidX/Kotlin 第三方库、DCloud/uni-app 基座或 GMS；
- 只有一个 Activity 和一个系统 WebView；
- 对 WebView 初始化失败做了兜底提示，而不是直接崩溃；
- 使用系统 DownloadManager 和系统文件选择器，不自己实现文件浏览器；
- 锁定较保守的 targetSdk，避免新系统强制边到边布局影响 WebView 尺寸。

---

## 一、先让 DSH 在局域网可访问

> 这一步在电脑上做，不是手机。DSH 当前 Web profile **不接受 `0.0.0.0` 通配地址**，必须绑定一个具体的本机网卡 IP。

### 1. 查电脑局域网 IP

Windows：

```powershell
ipconfig
```

找到当前 Wi-Fi / 以太网网卡的 IPv4 地址，例如 `192.168.1.20`。

### 2. 用这个 IP 启动 DSH

```powershell
dsh --profile web --host 192.168.1.20 --port 3080 --no-open
```

如果电脑有多个网卡，把 `--host` 换成手机能访问的那一个。如果 3080 已经被一个只监听 `127.0.0.1` 的旧 DSH 进程占用，先停掉旧进程再重启。

如果你不是直接用 IP，而是用主机名/域名访问（例如 `dsh.lan:3080`），还需要把它加进 DSH 的信任列表：

```powershell
dsh --profile web --host 192.168.1.20 --port 3080 --no-open --trusted-host dsh.lan:3080
```

DSH 会接受“绑定地址本身”的 Host，所以用 `--host` 的同一个 IP 连接时通常不需要额外的 `--trusted-host`。

### 3. 放行 Windows 防火墙

首次启动 DSH 时如果弹出防火墙提示，勾选“专用网络”。也可以手动放行 TCP 3080：

```powershell
New-NetFirewallRule -DisplayName "DSH Web 3080" -Direction Inbound -Protocol TCP -LocalPort 3080 -Action Allow
```

需要管理员 PowerShell。只在可信局域网使用，不要把 3080 暴露到公网。

### 4. 先用手机系统浏览器验证

在手机浏览器打开：

```text
http://192.168.1.20:3080
```

能打开并且能正常使用 DSH，再安装本 App。这样可以把“网络问题”和“App 问题”分开。

---

## 二、生成 APK（无需本机 Android 环境）

### 方式 A：GitHub Actions（推荐）

1. 在 GitHub 新建一个空仓库。
2. 在本目录执行：

```powershell
git init
git add .
git commit -m "feat: DSH Mobile Android WebView shell"
git branch -M main
git remote add origin https://github.com/<你的用户名>/dsh-mobile.git
git push -u origin main
```

3. 打开仓库的 **Actions** → **Build Android APK** → **Run workflow**。
4. 构建完成后，在该次运行页面底部下载 artifact：`dsh-mobile-debug-apk`。
5. 解压 artifact，得到 `app-debug.apk`。

`debug` APK 已签名，可以直接侧载安装。工程里提交了 `app/dsh-debug.keystore`（口令 `android`，alias `dshmobile`），它是**仅用于 debug 的签名密钥**，这样云端多次构建的 APK 签名一致，可以直接覆盖安装升级。它不适合正式发布；如果要公开分发或上架，请换成自己的 keystore 并配置 release 签名。

工作流文件：[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)

### 方式 B：有 Android SDK 的机器

安装 JDK 17、Android SDK Platform 34 / Build-Tools 34 和 Gradle 8.7，然后：

```powershell
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
gradle assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

工程当前没有提交 Gradle Wrapper 的二进制文件（本机没有环境，未下载）。如果使用 Android Studio，请选择本机 Gradle 8.7，或先执行一次 `gradle wrapper --gradle-version 8.7`。云端工作流不需要 wrapper，它直接使用 setup-gradle 安装的 Gradle。

---

## 三、在卓易通里安装和使用

1. 把 `app-debug.apk` 传到手机。
2. 打开 **卓易通**，使用“本地安装 / 从文件安装 APK”（不同版本入口名称略有差异）。
3. 如果系统提示未知来源或纯净模式限制，按卓易通/系统提示允许安装。
4. 首次联网时，允许卓易通容器访问网络。
5. 打开 **DSH Mobile**，二选一：

   - **点「扫描局域网」**：App 会扫描手机所在网段，把找到的服务列出来（带页面标题）。点一条即可连接。同一网段、同一 Wi-Fi 下不用再手输 IP。
   - **手动输入地址**：

     ```text
     http://192.168.1.20:3080
     ```

6. 连接前会先做一次端口预检，成功才进 WebView；失败会直接告诉你原因。

之后再次启动会直接加载上次保存的地址；连接过的地址会进入「最近使用」，点一下就切过去。要改地址，点右上角齿轮 → “修改 DSH 地址”。

### 扫描发现能覆盖什么

- 扫描范围：手机自己的 IPv4 网段，最多扫到 /24（254 个地址），不会为了一个 /16 网络去探 6 万个地址。
- 端口：默认 `3080`，同时会加上「最近使用」里出现过的端口。
- 探测顺序：先试 `http://`，失败再试 `https://`。HTTPS 探测为了识别自签名服务会临时接受证书，但**只用于读取标题，不传任何数据，也不会改变信任状态**；真正连接时仍会弹证书确认。
- 判定：TCP 能连上且返回 HTTP(S) 就列为候选；页面内容里出现 `dsh` / `deepseek` / `DeepSeek Harness` 会标成「DSH · …」，否则标成「可能不是 DSH · …」，标题也会显示出来。
- 依赖：手机必须和电脑在**同一个广播域**（同一 Wi-Fi、没有开 AP 隔离/访客网络）。跨网段、走 VPN、或用隧道地址时需要手动填。

### 卓易通里打不开时的检查顺序

1. 手机系统浏览器是否能打开同一个地址？
2. 手机和电脑是否在同一个 Wi-Fi/局域网？
3. DSH 是否绑定到了电脑的局域网 IP，而不是 `127.0.0.1`？
4. Windows 防火墙是否放行了对应 TCP 端口？
5. 路由器是否开启了“AP 隔离/访客网络隔离”？
6. 卓易通是否被系统限制了联网权限？
7. 如果使用主机名而不是 IP，DSH 启动时是否加了 `--trusted-host`？

---

## 四、App 内操作说明

| 操作 | 说明 |
| --- | --- |
| 顶部返回箭头 | WebView 有历史时后退；没有历史时退出 App |
| 硬件返回键/手势 | 同顶部返回箭头 |
| 刷新 | 重新加载当前页面 |
| 标题 | 点击也可打开设置 |
| 齿轮 → 修改 DSH 地址 | 回到地址输入页，原地址会预填 |
| 齿轮 → 在系统浏览器中打开 | 用手机浏览器打开当前页面 |
| 齿轮 → 清除网页缓存 | 只清 WebView HTTP 缓存 |
| 齿轮 → 清除 Cookie 和站点数据 | 会退出 DSH 登录，并清空 WebView 的 Cookie/localStorage/database |
| 地址页 → 扫描局域网 | 扫描本机网段；再点一次停止 |
| 地址页 → 发现的服务器 | 点击先预检，成功才进入 WebView |
| 地址页 → 最近使用 | 点击即连；**长按**移除该条记录 |
| 地址页 → 取消 | 从「修改地址」进来时，返回原来的连接 |
| 网页选择文件 | 调起系统文件选择器 |
| 网页下载 | 进入系统下载队列，保存到 `Download/DSH Mobile/` |
| 网页请求摄像头/麦克风 | 首次会弹系统权限；拒绝后网页无法使用对应设备 |

App 只把 DSH 基础地址保存在 SharedPreferences。Cookie、localStorage 由系统 WebView 管理；卸载 App 或清除站点数据会丢失登录态。

---

## 五、实现说明

### 关键文件

| 文件 | 作用 |
| --- | --- |
| [`app/src/main/java/com/dsh/mobile/MainActivity.java`](app/src/main/java/com/dsh/mobile/MainActivity.java) | 全部 UI 与业务逻辑：地址校验、预检、WebView、文件选择、下载、权限、错误页、断网重连 |
| [`app/src/main/java/com/dsh/mobile/ServerDiscovery.java`](app/src/main/java/com/dsh/mobile/ServerDiscovery.java) | 局域网扫描与单地址探测；纯 Java，无 Android 依赖，可脱离模拟器测试 |
| [`tools/DiscoveryCheck.java`](tools/DiscoveryCheck.java) | 上面那个类的本机验证程序，用真实 HTTP/自签名 HTTPS 服务端跑 8 项检查 |
| [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) | 权限、Activity、明文流量和 WebView 配置入口 |
| [`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml) | 允许局域网明文 HTTP；HTTPS 仍走系统信任链 |
| [`app/src/main/res/layout/activity_setup.xml`](app/src/main/res/layout/activity_setup.xml) | 首次地址配置页 |
| [`app/src/main/res/layout/activity_web.xml`](app/src/main/res/layout/activity_web.xml) | WebView 页面、工具栏和错误页 |
| [`app/build.gradle`](app/build.gradle) | SDK 版本、稳定 debug 签名配置 |
| [`app/dsh-debug.keystore`](app/dsh-debug.keystore) | 仅用于 debug 的固定签名密钥，便于覆盖升级 |
| [`tools/make-icons.ps1`](tools/make-icons.ps1) | 从 `tools/icon-master.png` 生成各密度启动图标 |

### WebView 配置

- `javaScriptEnabled` / `domStorageEnabled` / `databaseEnabled`
- Cookie 持久化，允许第三方 Cookie（DSH 的登录/重定向可能用到）
- `MIXED_CONTENT_COMPATIBILITY_MODE`：兼容 HTTPS 页面加载局域网 HTTP 资源
- 禁止 `file://` 访问和通用文件 URL 跨域访问
- 文件上传走系统 document picker，不申请存储权限
- 下载通过 DownloadManager；Android 10 以下才申请旧版存储权限
- HTTPS 证书错误：先弹提示说明原因（自签名 / 过期 / 域名不匹配等），用户点「仍然继续」后 `handler.proceed()`；确认过的 `host:port` 写入 SharedPreferences，之后自动放行。点「取消」仍走错误页，不做静默跳过
- URL 只允许 http/https；`mailto:`、`tel:`、`sms:` 交给系统，其余自定义 scheme 阻止

### 自签名 HTTPS

DSH 用 `--tls-cert/--tls-key` 起自签名 HTTPS 时，系统信任链不认这张证书。App 的处理方式：

1. 第一次访问该 `host:port`，弹出「证书未通过校验」，写明是自签名、过期还是域名不匹配；
2. 点「仍然继续」→ 本次继续加载，并把该地址记为信任；以后再访问不再提示；
3. 点「取消」→ 停在错误页，可以重试或改地址；
4. 想重新看到提示：设置 →「清除已信任的 HTTPS 证书」。

**子资源默认静默放行。** `onReceivedSslError` 不区分主文档和子资源，一个自签名页面里的 CDN、字体、第二个内网服务都会各弹一次。所以默认只对**页面本身的域名**提示：把出错地址和当前页面地址比较，不一致就当作子资源直接放行，并在该进程内只提示一次「已自动放行 xxx」。设置里的「证书提示」可以在两种模式间切换：

| 模式 | 行为 |
| --- | --- |
| 仅主页面（默认） | 页面域名不一致的证书错误直接放行，最多弹一次 toast |
| 所有资源 | 每个不同域名都会弹确认框，适合你想逐个确认的场景 |

> 继续访问等于跳过证书校验，只适合你自己能控制的局域网服务器。公网地址不要这样做：中间人无法被发现。

### 图标

图标使用 DSH 官方品牌图形中的鲸鱼标识（来自 [deepseek-harness BrandWordmark](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-primitives/src/BrandWordmark.tsx)），配色为深蓝渐变底 + `#3EC6FF` 蓝鲸。`tools/icon-master.png` 是 1024px 透明底母版，`tools/make-icons.ps1` 负责生成各密度图标，`tools/icon-source.svg` 是母版来源。

### 本地静态校验（无需 Android SDK）

本机没有 SDK 时，`javac` 无法判断某个 `@Override` 是否真的存在于父类中。`tools/check-overrides.py` 用 Android 14 源码提取的方法签名表（`tools/android-api-signatures.json`）逐个核对 `@Override` 的方法名和参数个数，提前复现 CI 里的那句 `method does not override or implement a method from a supertype`。

```powershell
python -m pip install javalang
python tools/check-overrides.py
```

退出码 0 表示全部 `@Override` 都能对上 `Activity` / `WebViewClient` / `WebChromeClient` 的真实方法。

> 这个检查是本项目自己的工具，不是 Android 官方工具；它只能发现“方法名/参数个数不匹配”这一类错误，其余编译错误仍然只能由 CI 或本地 SDK 发现。

`ServerDiscovery` 完全不依赖 Android，因此可以用本机 JDK 直接编译并跑真实网络测试：

```powershell
javac -encoding UTF-8 -d tools\out app\src\main\java\com\dsh\mobile\ServerDiscovery.java tools\DiscoveryCheck.java
java -cp tools\out com.dsh.mobile.DiscoveryCheck
```

它会真的起一个 HTTP 服务端和一个自签名 HTTPS 服务端，验证探测分类、扫描发现、标题解析、空网段兜底和网段枚举边界：

```text
PASS  probe reaches a live HTTP server   [OK HTTP 200]
PASS  probe reports a refused port   [REFUSED ConnectException]
PASS  probe reports an unknown host   [UNKNOWN_HOST UnknownHostException]
PASS  probe treats a self-signed certificate as 'continue anyway'   [TLS_UNTRUSTED SSLHandshakeException]
PASS  scan finds the live server and reads its title   [finished=true found=1 reasons=[null]]
PASS  scan discovers an HTTPS service behind a self-signed cert   [finished=true found=1 reasons=[null] url=https://127.0.0.1:.../]
PASS  scan reports 'no-local-address' instead of hanging
PASS  local subnet enumeration yields bounded IPv4 hosts   [506 hosts, first=10.1.154.1]
ALL CHECKS PASSED
```

---

## 六、已知限制

- DSH 的移动端布局没有改动；App 只负责把它显示在 WebView 里。
- 不支持 `blob:` 下载（DownloadManager 只能处理 http/https 链接）。
- 自签名 HTTPS 证书由用户手动确认后放行，**这不是安全的默认行为**；只在完全可控的局域网使用，公网地址请换成受信任证书。
- 局域网 `http://` 不属于浏览器定义的“安全上下文”，少数依赖 `navigator.clipboard`、WebCrypto 等安全上下文 API 的网页功能可能不可用；如果 DSH 的某个功能受此影响，需要给 DSH 配 HTTPS。
- `window.open` 多窗口被关闭，目标页会在当前 WebView 内加载。
- 没有做 release keystore、混淆、应用商店签名。
- **后台保活没有做**：没有前台服务，App 被系统回收后需要重新打开（会自动连上次地址）。真正锁屏常驻要加前台服务和通知权限，`卓易通` 对前台服务的支持也需要真机确认，所以先不做。
- 扫描只覆盖本机 IPv4 网段、且不超过 /24；VPN、跨网段、隧道地址需要手动输入。
- 局域网扫描不会探测 DSH 的认证/权限，只判断「这里有没有 HTTP(S) 服务在跑」；列表里的「DSH」标记来自页面标题/正文关键词，不是强认证。
- 断网自动重连依赖系统 `CONNECTIVITY_ACTION`，部分卓易通/厂商 ROM 可能不广播；此时手动点「重试」即可。

---

## 七、排查记录

| 现象 | 优先检查 |
| --- | --- |
| 页面空白、一直转圈 | 地址/端口是否正确；DSH 是否还在运行 |
| `ERR_CONNECTION_REFUSED` | DSH 是否绑定到了手机能访问的网卡 IP；防火墙 |
| `ERR_NAME_NOT_RESOLVED` | 用 IP 而不是主机名；主机名需要能解析 |
| 能打开页面但 API 报 403 | DSH 的 Host/Origin 信任栅栏；用 `--host` 的同一个 IP，或加 `--trusted-host` |
| 文件选择器打不开 | 卓易通/系统的文档选择器是否可用 |
| 提示系统 WebView 不可用 | 卓易通或系统没有提供 Android System WebView |
| 下载没有出现 | Android 10 以下是否拒绝了存储权限；链接是否为 http/https |
| CI 报 `method does not override or implement a method from a supertype` | 某个 `@Override` 放错了类；先跑 `python tools/check-overrides.py` |

### 已修复的构建失败

首次云端构建在 `:app:compileDebugJavaWithJavac` 失败，Java 编译器只报 1 个错误：

```text
error: method does not override or implement a method from a supertype
```

原因是 `onReceivedTitle(WebView, String)` 被写在了 `WebViewClient` 里。这个方法只属于 `WebChromeClient`，`WebViewClient` 没有它。已把该重写移入 `DshChromeClient`（`MainActivity.java` 第 588 行起的内部类），并用 `tools/check-overrides.py` 复现验证过修复。

如果要在公网访问，建议在 DSH 前面放 HTTPS 反向代理，用证书和访问控制，不要把明文 3080 直接暴露到互联网。
