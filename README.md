# UBAA (智慧北航 Remake)

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-blue.svg?style=flat&logo=kotlin)
![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.10.3-blueviolet.svg?style=flat&logo=jetpack-compose)
![Ktor](https://img.shields.io/badge/Ktor-3.4.1-orange.svg?style=flat&logo=ktor)
![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20Web-lightgrey.svg?style=flat)

**UBAA** 是一款基于 **Kotlin Multiplatform** 和 **Compose Multiplatform** 构建的现代化跨平台应用，专为北京航空航天大学（BUAA）学生打造。

### [立刻下载](https://github.com/BUAASubnet/UBAA/releases)
### [网页版](https://app.buaa.team)

它不仅仅是一个客户端，更是一个智能的**服务聚合网关**。通过专用的 Ktor 后端，它将复杂的校内系统（如 CAS 认证、教务系统、博雅系统）的数据进行标准化清洗与聚合，为 Android、iOS、Desktop 和 Web 端用户提供统一、流畅且美观的 Material Design 3 体验。

---

## ✨ 核心特性 (Features)

### 🖥️ 多端覆盖
*   **📱 Android / iOS**: 原生级性能的移动体验。
*   **💻 Desktop**: 支持 Windows, macOS, Linux 的桌面客户端。
*   **🌐 Web**: 基于 Wasm/JS 的现代网页应用，无需安装即可使用。

### 🎓 智慧教务
*   **🔐 统一认证**: 无缝集成 BUAA CAS 统一身份认证，支持验证码处理与服务端会话保持。
*   **📅 智能课表**: 实时同步学期课表，支持周次切换与详情查看。
*   **📝 考务助手**: 考试安排一键查询，不再错过重要考试。
*   **🏛️ 空闲教室**: 快速查找全校可用自习教室。
*   **🎓 博雅全能**: 博雅课程查询、选课、退课及**远程自主签到**。
*   **✅ 考勤签到**: 支持特定课程的二维码/位置签到功能。
*   **⚡ 自动评教**: 一键完成学期评教。

### 🎨 卓越体验
*   **Material Design 3**: 遵循最新设计规范，界面现代、整洁。
*   **深色模式**: 完美适配系统亮色/深色主题。
*   **更新提醒**: 内置版本检查，及时获取最新功能与修复。
*   **隐私管理**：服务端不存储任何用户信息，所有内容均由北航官方服务器提供。

---

## 🚀 快速开始 (Getting Started)

### 📋 前置条件
*   **JDK**: 17 或更高版本。
*   **IDE**: IntelliJ IDEA (推荐) 或 Android Studio。
*   **Xcode**: 仅 iOS 开发需要 (macOS)。

### 1️⃣ 克隆与配置
```bash
git clone https://github.com/your-repo/UBAA.git
cd UBAA
cp .env.sample .env
# 根据需要编辑 .env 文件配置端口等信息
```

### 2️⃣ 启动后端服务 (Server)
客户端强依赖后端 API，请**务必先启动服务端**。

```bash
./gradlew :server:run
```
> 服务端默认运行在 `http://0.0.0.0:5432`。

### 3️⃣ 启动客户端 (Client)

| 平台        | 命令                                                | 说明                |
| :---------- | :-------------------------------------------------- | :------------------ |
| **Android** | `./gradlew :androidApp:installDebug`                | 连接真机或模拟器    |
| **Desktop** | `./gradlew :composeApp:run`                         | 运行桌面客户端      |
| **Web**     | `./gradlew :composeApp:wasmJsBrowserDevelopmentRun` | 启动本地 Web 服务器 |
| **iOS**     | 打开 `iosApp/iosApp.xcworkspace`                    | 使用 Xcode 运行     |

---

## ⚙️ 配置与环境 (Configuration)

项目配置通过根目录下的 `.env` 文件管理。关键配置项如下：

| 配置项                     | 默认值                   | 说明                           |
| :------------------------- | :----------------------- | :----------------------------- |
| `API_ENDPOINT`             | `http://localhost:5432`  | 客户端连接的后端地址           |
| `SERVER_PORT`              | `5432`                   | 服务端监听端口                 |
| `SERVER_BIND_HOST`         | `0.0.0.0`                | 服务端绑定地址                 |
| `JWT_SECRET`               | *(需修改)*               | 用于签名 access token 的密钥   |
| `USE_VPN`                  | `false`                  | 是否通过 WebVPN 代理访问校内网 |
| `ACCESS_TOKEN_TTL_MINUTES` | `30`                     | access token 有效期            |
| `REFRESH_TOKEN_TTL_DAYS`   | `7`                      | refresh token 有效期           |
| `SESSION_TTL_DAYS`         | `7`                      | Redis 会话与 Cookie 有效期     |
| `REDIS_URI`                | `redis://localhost:6379` | Redis 会话持久化地址           |

---

## 📊 监控与运维 (Observability)

服务端内置了企业级的监控与日志能力，保障服务稳定运行。

*   **📈 指标监控 (Metrics)**:
    *   Endpoint: `GET /metrics`
    *   格式: Prometheus 文本格式
    *   内容: JVM 内存/GC、HTTP 请求吞吐/延迟、线程池状态，以及登录成功统计。
    *   常用 PromQL:
        *   接口调用次数:
            `sum by (route, method, status) (increase(ktor_http_server_requests_seconds_count[24h]))`
        *   接口平均耗时:
            `sum by (route, method, status) (increase(ktor_http_server_requests_seconds_sum[24h])) / sum by (route, method, status) (increase(ktor_http_server_requests_seconds_count[24h]))`
        *   登录成功累计次数:
            `sum by (mode) (ubaa_auth_login_success_total)`
        *   固定窗口登录人次:
            `ubaa_auth_login_events_window{window="24h"}`
        *   固定窗口唯一登录用户数:
            `ubaa_auth_login_unique_users_window{window="24h"}`
    *   登录统计说明:
        *   `ubaa_auth_login_success_total{mode="manual|preload_auto"}` 统计成功建立会话的登录次数。
        *   `ubaa_auth_login_events_window{window="1h|24h|7d|30d"}` 统计固定窗口内的成功登录人次。
        *   `ubaa_auth_login_unique_users_window{window="1h|24h|7d|30d"}` 使用 Redis HyperLogLog 做近似去重，适合监控看板，不保证绝对精确。

*   **📝 日志系统 (Logging)**:
    *   **控制台**: 实时输出 Info 级别以上日志。
    *   **文件归档**: 自动写入 `server/logs/server.log`。
        *   策略: 按天滚动，保留 30 天历史。
        *   内容: 包含完整的请求链路追踪 (Trace) 和异常堆栈。

---

## 🏗 技术栈与架构 (Architecture)

本项目采用 **Kotlin Multiplatform (KMP)** 分层架构：

### 📂 模块划分
*   **`composeApp` (UI 层)**
    *   基于 **Compose Multiplatform**。
    *   包含所有界面逻辑、ViewModel 和平台特定的入口代码。
*   **`shared` (领域层)**
    *   **KMP 共享模块**，被客户端和服务端同时引用。
    *   定义了所有 **Data Models (DTOs)** 和 **API Interfaces**，确保前后端契约绝对一致。
    *   包含通用的日期处理、加密算法等逻辑。
*   **`server` (后端层)**
    *   基于 **Ktor Server**。
    *   作为 API Gateway 和 Adapter，负责与复杂的校内旧系统交互（爬虫/模拟请求）。
    *   处理 JWT 鉴权、Session 管理和数据缓存。

### 🛠 关键技术
*   **Language**: Kotlin 2.0+
*   **UI**: Jetpack Compose / Compose Multiplatform
*   **Backend**: Ktor 3.x, Netty
*   **Build**: Gradle (Kotlin DSL), Version Catalog
*   **Libraries**: Koin (DI), Ktor Client, Coroutines, Serialization

---

## 🧪 开发指南 (Development)

### 运行测试
执行全量单元测试（包含 Shared 和 Server 逻辑）：
```bash
./gradlew test
```

### 代码覆盖率
生成 HTML 格式的测试覆盖率报告 (基于 Kover)：
```bash
./gradlew koverHtmlReport
```
*   报告路径: `build/reports/kover/html/index.html`

### 代码规范
执行 Lint 检查以确保代码风格一致：
```bash
./gradlew lint
```

---

## 📂 目录结构概览

```text
UBAA/
├── composeApp/          # 客户端主工程
│   ├── src/commonMain   # 核心 UI 代码 (Screens, Components)
│   ├── src/androidMain  # Android 入口
│   ├── src/iosMain      # iOS 入口
│   ├── src/jvmMain      # Desktop 入口
│   └── src/webMain      # Web 入口
├── server/              # 后端服务主工程
│   └── src/main/kotlin  # 路由、业务逻辑、爬虫实现
├── shared/              # 共享代码库 (KMP)
│   ├── src/commonMain   # DTOs, Enums, Utils
├── iosApp/              # iOS Xcode 工程配置
└── gradle/              # 构建配置与 Version Catalog
```
