# 虚拟定位 · Virtual Location

> 一个 37 KB 的极简 Android 位置模拟工具：**输入经纬度 → 一键写入系统定位**。无广告、无联网、无任何第三方 SDK。

> 界面预览：主界面为深色卡片布局（状态卡 / 经纬度输入 / 已保存位置 / 坐标系与频率 / 一键启动），
> 首次启动弹出免责声明。可自行编译运行后截图，放到 `docs/screenshots/` 并在本节引用。

- **平台**：Android 7.0+（minSdk 24 / targetSdk 34）
- **体积**：约 46 KB（无 AndroidX、无地图 SDK、无网络权限）
- **语言**：Java + 原生 XML 布局
- **构建**：不依赖 Gradle —— `aapt2 → javac → d8 → zipalign → apksigner`

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 经纬度直填 | 默认填好一组坐标，打开即用；点击输入框自动全选，直接覆盖输入 |
| 坐标系 | WGS84 / GCJ-02（高德、腾讯）/ BD-09（百度），后两者按标准算法反算成 WGS84 再写入 |
| 刷新频率 | 200ms / 1s / 3s，循环向 `gps` 与 `network` 两个测试 provider 写入 |
| 已保存位置 | 命名保存当前坐标，列表点一下即可一键导入；支持删除，数据仅存本机 |
| 前台服务 | 通知栏常驻，可直接从通知停止；进程被强杀后靠心跳自动校正状态 |
| 首次免责声明 | 同意一次后不再提示，「不同意」直接退出 |
| 状态持久化 | 记住坐标、坐标系、频率与运行状态 |

## 快速开始

### 直接安装

到 [Releases](https://github.com/Piper-pi/virtual-location/releases) 下载 APK 安装即可。

### 从源码构建

依赖：

- JDK 11+（`JAVA_HOME` 或 PATH 中的 `javac`）
- Android SDK，包含 `platforms;android-34` 与 `build-tools`（`ANDROID_HOME` / `ANDROID_SDK_ROOT`）

```bash
python build.py              # 构建并签名（首次自动生成 keystore.jks）
python build.py --install    # 构建后直接 adb install -r
python build.py --unsigned   # 只产出未签名 APK
python build.py --sdk /path/to/android-sdk
```

脚本会自动选取最新的 platform/build-tools；`d8` 优先使用新版 build-tools（旧版 R8 在 JDK 17+/21 上会抛 NPE）。工程路径含非 ASCII 字符时会自动镜像到临时目录再构建（aapt2 不吃中文路径）。

图标由 `make_icon.py` 生成（需要 Pillow）：

```bash
pip install pillow
python make_icon.py
```

## 使用前必读（两步系统授权）

1. **开启开发者模式**：设置 → 关于手机 → 连续点击 7 次「版本号」
2. **指定模拟位置应用**：设置 → 开发者选项 → **选择模拟位置信息应用** → 选中「虚拟定位」

首次点「开始模拟」还会请求**位置信息权限**（Android 14 起，定位类前台服务必须先获得该权限）。

> 国产 ROM 的该列表只列出声明了 `ACCESS_MOCK_LOCATION` 的应用，本项目的 manifest 已声明。

## 工作原理

使用 Android 原生的测试 provider 机制，不需要 root、不需要 Xposed：

```java
LocationManager.addTestProvider("gps", false, false, false, false, true, true, true, 3, 2);
LocationManager.addTestProvider("network", ...);
LocationManager.setTestProviderEnabled(name, true);

// 每 interval 毫秒
location.setTime(System.currentTimeMillis());
location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
LocationManager.setTestProviderLocation(name, location);

// 停止
LocationManager.setTestProviderEnabled(name, false);
LocationManager.removeTestProvider(name);
```

关键点：

- `time` / `elapsedRealtimeNanos` 每轮刷新，避免位置被系统判为过期
- 权限判定以 **AppOps**（`OPSTR_MOCK_LOCATION`）为准，`Settings.Secure.mock_location` 只是全局开关
- 前台服务状态用**心跳时间戳**兜底：进程被强杀时 `onDestroy` 不会执行，界面据此把「运行中」纠正为「未运行」

## 项目结构

```
virtual-location/
├─ app/
│  ├─ AndroidManifest.xml
│  ├─ java/com/tiny/mockgps/
│  │  ├─ MainActivity.java     界面、权限流程、坐标保存/导入
│  │  ├─ MockService.java      前台服务、定位注入循环、心跳
│  │  └─ Coords.java           WGS84 / GCJ-02 / BD-09 互转
│  └─ res/                     布局、色板、圆角 drawable、图标
├─ docs/screenshots/
├─ build.py                    无 Gradle 构建脚本
├─ make_icon.py                图标生成
└─ .github/workflows/build.yml 构建检查（产出未签名 APK）
```

## 常见问题

**开发者选项里看不到「虚拟定位」？**
确认已开启开发者模式；部分机型需要先安装一次并打开过应用。

**点了开始，目标应用位置没变？**
目标应用需重新打开以清掉定位缓存；且本方案只影响走系统 `LocationManager` 的应用，基站/Wi-Fi 定位的应用不受影响。

**状态显示「未运行」但通知还在？**
从通知点「停止」再重新开始一次，或直接在系统设置里停止该应用。

**后台被杀？**
给应用允许「自启动 / 后台运行」，并关闭针对它的省电限制（ColorOS、MIUI、OriginOS 常见）。

## 免责声明

本应用是一款位置调试工具，**仅用于应用开发、测试与功能验证**。

- 会改变设备上其他应用的定位结果
- 请勿用于考试、考勤、打卡、游戏外挂、位置欺诈等违反法律法规或第三方服务条款的场景
- 使用者应自行确保使用场景合法合规，因使用本应用产生的一切后果由使用者自行承担
- 本应用不联网、不收集、不上传任何个人信息

## 致谢

Piper-pi ＆ DEEPSEEK

## License

[MIT](LICENSE)


