# FileSync 局域网双端文件同步软件

手机（Android）与电脑（Windows/JavaFX）在同一局域网内自动双向同步文件，无需云端、不耗移动流量、数据不出内网。

---

## 一、功能特性

- **自动设备发现**：UDP 广播，上线即自动列出局域网内的对端设备
- **双向自动同步**：任一端新增、修改、删除文件，同步后两端保持一致
- **断点续传**：下载中断保留 `.part` 文件，下次自动从断点续传
- **删除同步（墓碑机制）**：删除操作通过 tombstone 传播，不会把“已删除”误判为“待上传”
- **冲突处理**：
  - 按最后修改时间 LWW（last-write-wins）自动决策
  - 修改时间几乎相同但内容不同时，保留双方并生成 `.conflict-设备名` 副本，绝不丢数据
- **路径安全**：所有相对路径规范化，防止 `../` 穿越访问同步目录之外的文件
- **前台服务常驻**：Android 端整个运行期以前台服务（`dataSync` 类型）保活，锁屏/切后台后服务端仍然在线，电脑端随时能连上
- **跨端实时日志**：任一端发起同步时，日志会实时推送到对端，两端看到同一份同步过程（`POST /api/log`）
- **手动刷新 + 选中保持**：两端都有「刷新」按钮可立即重新发现设备；列表刷新/设备上下线时按设备 ID 保持选中，并显示「当前选中」

---

## 二、技术架构

```
┌────────────────────────────┐        ┌────────────────────────────┐
│      Android 端 (Java)     │        │     桌面端 (JavaFX)        │
│  MainActivity / SyncManager│        │  MainApp / AppConfig       │
│  RecursiveFileObserver     │        │  DirectoryWatcher          │
│  (FileObserver 递归监听)    │        │  (WatchService 递归监听)    │
├────────────────────────────┤        ├────────────────────────────┤
│  AndroidHttpServer(NanoHTTPD)│      │  HttpFileServer(JDK HttpServer)│
│  AndroidPeerClient(OkHttp) │        │  DesktopPeerClient(HttpClient)│
└──────────────┬─────────────┘        └──────────────┬─────────────┘
               │            common 共享模块            │
               │  ┌────────────────────────────────┐  │
               └──┤ SyncEngine 双向同步算法（唯一实现）├──┘
                  │ DeviceDiscovery UDP 发现引擎     │
                  │ 模型/协议/JSON/Hash/路径工具      │
                  └────────────────────────────────┘
```

### 分层说明

| 模块 | 职责 | 技术 |
|------|------|------|
| `common` | 同步算法、设备发现、协议模型、工具，纯 Java 8，两端复用 | Gson、JDK 标准库 |
| `desktop` | 桌面 UI、HTTP 服务、目录监听 | JavaFX 21、JDK HttpServer、HttpClient、WatchService |
| `android` | 手机 UI、HTTP 服务、目录监听、前台服务 | OkHttp、NanoHTTPD、FileObserver |

**关键设计**：同步算法只在 `common/SyncEngine` 实现一次，两端通过实现 `RemotePeerClient` 接口接入各自的 HTTP 栈，避免逻辑重复。

---

## 三、目录结构

```
FileSync/
├── settings.gradle          # Android 模块按 SDK 是否存在条件纳入
├── build.gradle
├── gradle.properties
├── common/                  # 共享模块
│   └── src/main/java/com/filesync/common/
│       ├── model/           # DeviceInfo / FileMeta / Manifest / Tombstone
│       ├── protocol/        # API 路径、发现协议、JSON、状态常量
│       ├── discovery/       # DeviceDiscovery UDP 引擎
│       ├── sync/            # SyncEngine、RemotePeerClient、TombstoneStore
│       └── util/            # FileWalker / HashUtils / PathUtils / NetworkUtils
├── desktop/                 # JavaFX 桌面端
│   └── src/main/
│       ├── java/com/filesync/desktop/
│       └── resources/desktop.css
└── android/                 # Android 端
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/filesync/android/
        └── res/
```

---

## 四、网络协议

### 4.1 设备发现（UDP 端口 19090）

- 每 3 秒向局域网广播 `ANNOUNCE`（JSON，含设备 ID、名称、类型、HTTP 端口）
- 收到 `ANNOUNCE` 的设备单播 `REPLY`，实现快速互相发现
- 10 秒未收到某设备报文则判定离线并剔除
- 报文带 `FILESYNC_DISCOVERY_V1` 魔数，过滤无关广播

### 4.2 文件接口（HTTP，默认端口 18080，被占用自动递增）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/info` | 本机设备信息 |
| GET | `/api/manifest` | 同步目录完整清单 + 墓碑 |
| GET | `/api/file?path=xxx` | 下载文件，支持 `Range` 断点 |
| PUT | `/api/file?path=xxx&mtime=xxx` | 上传/覆盖文件（原子落盘） |
| PUT | `/api/file?path=xxx&dir=true` | 创建目录 |
| DELETE | `/api/file?path=xxx` | 删除并记录墓碑 |
| GET | `/api/ping` | 连通性检测 |
| POST | `/api/log` | 跨端日志推送（JSON：`{"from":"设备名","text":"日志行"}`） |

---

## 五、同步策略

一次 `SyncEngine.runOnce()` 流程：

1. 拉取对端 manifest，扫描本地目录，建立两侧文件索引
2. **墓碑处理**：对端墓碑中、本地未再修改的文件删除；本地墓碑通知对端删除
3. **逐路径对比**：
   - 仅一侧存在 → 向另一侧传输
   - 两侧都有且元数据不同 → 比较最后修改时间，新者覆盖（LWW）
   - 时间差 ≤ 2 秒且大小相同 → 视为相同
   - 时间几乎相同但大小不同 → 保留双方，对端版本存为 `*.conflict-设备名`
4. 清理 30 天前的过期墓碑，持久化状态

---

## 六、构建与运行

### 环境要求

- JDK 21（桌面端）
- Android SDK：compileSdk 34、build-tools 34.0.0、minSdk 24
- 已内置 Gradle Wrapper，无需本机安装 Gradle

### 桌面端

```bash
# Windows 下运行（首次自动下载 Gradle 与 JavaFX）
gradlew.bat :desktop:run

# 编译
gradlew.bat :desktop:build
```

启动后：点击「选择目录」绑定同步文件夹 → 左侧列表选择设备 → 「立即同步」。

### Android 端

```bash
# 调试 APK
gradlew.bat :android:assembleDebug
# 发布 APK（未签名，需自行配置签名）
gradlew.bat :android:assembleRelease
```

产物路径：`android/build/outputs/apk/debug/android-debug.apk`

也可用 Android Studio 打开根目录，SDK 路径写入 `local.properties`：

```properties
sdk.dir=C\:/path/to/Android/Sdk
```

> 手机同步目录默认为应用专属外部存储：
> `Android/data/com.filesync.android/files/Sync`，无需申请存储权限。

### 全量构建

```bash
gradlew.bat build -x test
```

---

## 八、真机联调记录（小米 15 / MIUI，Android 14）

以下问题均为真机联调中实际出现并已修复，不是理论推测：

| 现象 | 根因 | 修复 |
|------|------|------|
| 电脑端 PUT 上传返回「空请求体」 | NanoHTTPD 2.3.1 的 `parseBody()` 只在 `POST` 分支写入 `postData`，PUT 的请求体被静默丢弃 | Android 端改为按 `Content-Length` 手工读取 `session.getInputStream()` 落盘 |
| 文件已落盘但客户端收不到响应（`HTTP/1.1 header parser received no bytes`） | `servePut` 用 try-with-resources 关闭了 `session.getInputStream()`，等于关掉了底层 socket 流 | 只关文件流，不关请求流 |
| 电脑端连不上手机 18080，但同网络下其他应用的端口可连 | 服务只监听 `[::]`（IPv6 通配），外部 IPv4 连接进不来 | NanoHTTPD 显式 `super("0.0.0.0", port)`；UDP 发现也显式绑定 IPv4 通配 |
| 手机锁屏/切后台后服务端口消失 | MIUI 把后台进程杀掉（`SmartPower: background->idle`） | 改为启动即拉起前台服务并全程常驻，不再只在同步期间启动 |
| 同步后对端文件被误删 | 服务端自己写盘触发的 `DELETE/MOVED_FROM` 被 FileObserver 当成「用户删除」，生成假墓碑 | 新增 `WriteGuard`：写盘前登记路径，观察者忽略受保护路径；写入成功后清除同名墓碑 |
| `.part` 临时文件混入清单与墓碑 | 中间文件未被过滤 | `FileWalker` 与 `TombstoneStore` 统一忽略含 `.part` 的路径 |
| 同一进程里同时监听 18080 与 18081 | 启动流程可重入，旧实例未释放，端口退让后并存 | `start()` 幂等 + 已有实例则复用；Activity 重建时不再重复 init |
| 本机仍存在的文件被对端「删除」 | 墓碑记录只按删除时间判定，没有校验文件是否又出现；自己下载时的 rename 事件也会被记成墓碑 | `SyncEngine` 遇到「本地文件仍在」的墓碑直接作废；桌面对端补上与手机同款 `WriteGuard`，同步引擎写盘期间忽略监听事件 |
| 手机端无法更换同步目录 | 同步根目录写死为应用私有外部目录 | 新增 `sync_dir` 配置项 + 应用内目录浏览器，任意目录可切换（下面第 5 条） |
| 通知栏样式与系统不一致、锁屏看不到、进度条乱跳 | 旧渠道为默认重要性、彩色图标、每次都重新提醒 | 新渠道 `filesync_service_v2`（低重要性、无声、无角标）、单色小图标、`setOnlyAlertOnce`、进度每 10% 才更新 |
| 电脑端同步日志「不实时」 | 每行都 `runLater` 排队，且 `TextArea` 文字色仍是默认深色，深底上看不清 | 日志先进缓冲、FX 线程批量追加并自动滚到底；CSS 显式设置 `-fx-text-fill` 为浅色 |
| 电脑端点「立即同步」提示「请先在左侧选择要同步的设备」 | 设备列表每次发现回调都 `removeIf` + `add`，把已选中的行刷掉 | 新增 `refreshKeepsSelection()`：刷新前后按设备 ID 还原选中项 |
| 删除目录后同步报 `DELETE_REMOTE … HTTP 404` | 目录先整体删掉，子文件的删除请求自然命中 404 | 404 视为「对端已不存在、删除目标达成」，不再按错误上报 |
| 另一端不知道这一端在同步什么 | 同步事件只回调本端界面 | 新增 `POST /api/log`，发起端把每条日志实时推给对端，对端日志区显示 `[来自 设备名] …` |
| 新设备上线要等一个广播周期才出现 | 发现只有周期性广播，无手动触发 | 两端新增「刷新」按钮 → `requestAnnounce()` 立即补广播一次 |
| 列表刷新后看不清当前选中哪台 | 选中状态在列表重建时丢失，界面也没有显式提示 | 两端按设备 ID 保持选中，并增加「当前选中」提示条（手机列表项带选中底色） |
| 手机端一发起同步就失败：`CLEARTEXT communication … not permitted by network security policy` | Android 9+ 默认禁止明文 HTTP，服务端是纯 HTTP，而 manifest 未声明允许明文 | `<application>` 增加 `android:usesCleartextTraffic="true"`（1.0.3）。实测手机发起同步「同步完成，变更 2 项」，电脑端同秒弹出 `[来自 24129PN74C的手机]` 四条日志 |

实测结果（电脑 ↔ 小米 15，经真实网络地址而非 adb 端口转发）：

- 1 MB 二进制文件双向传输，两端 MD5 完全一致
- 中文文件名、嵌套目录、覆盖写均正常
- 连续两次同步，第二次 `changed=0`（幂等）
- 手机锁屏切后台 15 秒后，进程存活、`/api/info` 仍可访问

### 使用前提与注意事项

1. **两端需在同一网络**：手机连上与电脑相同的 WiFi/路由器。跨网段、开启 AP 隔离的公共网络、校园网认证网络下广播发现会失效（可用 ZeroTier 等虚拟局域网代替，实测可用）
2. **MIUI/澎湃 OS 建议开启**：设置 → 应用管理 → FileSync → 省电策略「无限制」、允许「自启动」，否则极端省电场景仍可能被杀
3. 手机默认同步目录为应用私有外部目录 `Android/data/com.filesync.android/files/Sync`，卸载应用会一并删除；可在应用内「切换目录」改成任意目录
4. 首次运行时系统会询问通知权限，前台服务的常驻通知需要该权限
5. 切换同步目录需要「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）：点「切换目录」时若未授权，会跳转到系统页面，授权返回后自动继续打开目录浏览器；未授权前只能使用应用私有目录

---

## 九、后续可扩展方向

- 自动同步开关打通（监听事件直接触发同步，目前已预留回调）
- 大文件分片上传与上传端断点续传
- 基于内容 hash 的增量/差量同步（rsync 算法）
- 同步历史记录与可视化
- 多设备群组、TLS 加密传输与配对认证
- 用 SAF（Storage Access Framework）支持任意公共目录
- iOS 端与 macOS/Linux 桌面端适配

---

## 十、版本

- protocolVersion：1
- 版本：1.0.3（Android 端修复明文 HTTP 被系统拦截导致的「手机同步一直失败」）
- 上一版：1.0.2（跨端日志实时互推、两端刷新按钮、选中设备保持）
