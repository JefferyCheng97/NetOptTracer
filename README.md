# CellularMonitor

一款开源 Android App，用来观察当前手机的**蜂窝网络细节**：4G/5G 信号、双卡状态、邻区、小区标识；配合运营商工参 TXT，能把周边基站画在离线地图上并按方位角显示扇区。

![Android](https://img.shields.io/badge/platform-Android-3ddc84)
![minSdk](https://img.shields.io/badge/minSdk-29-blue)
![Kotlin](https://img.shields.io/badge/lang-Kotlin-7f52ff)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285f4)

---

## 功能一览

- **双卡监测**：两张 SIM 同时采样，左右滑动看每张卡的信号
- **4G 指标**：RSRP / SINR / RSRQ / RSSI / CQI / ECI / PCI / TAC / EARFCN / 频段
- **5G 指标**：SS-RSRP / SS-SINR / SS-RSRQ / NCI / PCI / TAC / NR-ARFCN / 频段
- **NSA / SA 组网自动识别**，NSA 下自动降级显示锚点信息
- **邻区列表**：LTE / NR 邻区 PCI、RSRP、频段
- **小区明细**（导入工参后可用）：宏站 / 室分识别，基站中文名、小区名
- **地图页**（导入工参 + 高德离线包）：
  - 当前定位（跟随手机朝向的红色导航箭头）
  - 工参里所有带坐标的基站叠加，按运营商 / 覆盖类型上色
  - 按方位角画扇区（可选，默认关闭）
  - 点基站看详情，可一键跳高德 App 驾车导航
  - 搜索小区名 / CGI，选中后地图飞过去并弹信息窗
- **网速测试**：走中科大 LibreSpeed，测下载 / 上传峰值，2 轮取较快一次
- **卡片自定义排序**：主页各卡片支持拖拽调整顺序，长按 ≡ 拖动
- **工参导入**：直接在 App 里选 TXT，不用改代码；支持 UTF-8 / GBK；支持中国移动、中国电信

## 截图

（放几张跑起来的截图，展示主页信号卡、地图基站扇区、导航跳转等——先占位）

## 编译

### 环境

- Android Studio Ladybug 或以上
- JDK 11+
- Android SDK 36（`compileSdk`），最低支持 Android 10（`minSdk = 29`）

### 步骤

1. **克隆代码**
   ```bash
   git clone https://github.com/你的用户名/CellularMonitor.git
   cd CellularMonitor
   ```

2. **申请高德地图 API Key**

   地图页依赖高德 SDK，需要**自己的 API Key**才能显示地图。

   1. 去 [高德开放平台](https://lbs.amap.com/) 注册开发者账号
   2. 控制台 → 应用管理 → 我的应用 → 创建新应用
   3. 添加 Key，选 **Android 平台**，填：
      - **PackageName**：`com.jeffery.cellularmonitor`
      - **发布版安全码 SHA1**：你的 release keystore 的 SHA1
      - **调试版安全码 SHA1**：你的 debug keystore 的 SHA1（本项目 release 默认用 debug 签名，可以只填这一个）

   取 debug keystore SHA1：
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA1
   ```

3. **配置 `local.properties`**

   复制样本文件：
   ```bash
   cp local.properties.example local.properties
   ```

   编辑 `local.properties`，把 SDK 路径和高德 Key 填上：
   ```properties
   sdk.dir=/path/to/your/android/sdk
   AMAP_API_KEY=你申请到的高德key
   ```

   `local.properties` 已在 `.gitignore`，不会被提交。

4. **构建**

   ```bash
   ./gradlew assembleRelease
   ```

   产物在 `app/build/outputs/apk/release/app-release.apk`，大约 58 MB（大部分是高德 SDK 的原生库）。

   > release 构建**使用 debug keystore 签名**（`app/build.gradle.kts` 里 `signingConfig = signingConfigs.getByName("debug")`），方便个人测试和 apk 直接分发。上架应用市场请改成正式 keystore。

## 权限

首次启动会向用户请求：

| 权限 | 用途 |
|------|------|
| `READ_PHONE_STATE` | 读 SIM 卡、卡槽、注册状态、`CellInfo` |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Android 10+ 起，读 `getAllCellInfo()` 强制要求；地图定位也用它 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 测速要发真实流量；地图瓦片首次下载 |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 高德 SDK 优化定位与网络切换 |

App **不上传任何本机数据**：
- 测速上传的是内存里生成的随机字节，跟设备无关
- 工参数据存在 App 私有目录，不联网

## 工参 TXT 格式

导入的 TXT 是运营商网管系统直接导出的 Tab 分隔文件，按表头名取列（列顺序无所谓），必需列：

| 列名 | 说明 |
|------|------|
| 小区号 | 4G ECI / 5G NCI 的纯数字（不带 460-00- 前缀） |
| 覆盖类型 | 例如 "宏站"、"室分" |
| CGI | 形如 `460-00-1006136-40`，用于判归属运营商 |
| 基站中文名 | 展示用 |
| 小区名 | 展示用 |
| 经度、纬度 | 可选，地图打点用（WGS-84，会自动转 GCJ-02） |
| 方向角 | 可选，扇区绘制用（0° 正北，顺时针） |

支持中国移动（CGI 前缀 `460-00`）和中国电信（`460-11`）。同一小区号在文件里因多个方向角出现多次时，解析器会**合并方位角**，其他字段沿用首次。

## 技术栈

- **UI**：Jetpack Compose + Material 3
- **异步**：Kotlin Coroutines + Flow
- **数据采集**：`TelephonyManager` + `TelephonyCallback`（API 31+）/ `PhoneStateListener`（API 29/30）
- **持久化**：`SharedPreferences`（卡片顺序）+ 私有目录文件（工参 TXT）
- **地图**：高德 3D 地图 SDK 10.0.600（离线地图 + 定位 + Marker/Polygon）
- **构建**：Kotlin DSL Gradle

## 已知限制

- **只对中国移动和电信有效**：工参目前只覆盖这两家；联通 / 广电的卡不会显示覆盖类型和小区明细
- **`MODIFY_PHONE_STATE` 权限不可用**：第三方 App 无法切换网络制式（4G ↔ 5G），只能被动读取
- **室内定位精度**：受 GPS 信号影响，可能偏离 20–50 米，等 GPS 完全锁星后会自动修正
- **16 KB page size 兼容性**：高德 10.0.600 的 native 库未对齐 16 KB 页边界，暂不影响常规设备使用，Google Play 上架需等高德更新

## License

MIT（或你想用的其他 License；如果不确定就写 `TBD`）

## 致谢

- [高德开放平台](https://lbs.amap.com/)：地图与离线包
- [中科大 LibreSpeed 测速节点](https://test.ustc.edu.cn/)：网速测试
