# SpeakPath AI

SpeakPath AI 是面向英语备考的 AI 学习应用，包含诊断测评、能力报告、学习计划、每日练习和口语纠错。前端使用 Expo、React Native 与 TypeScript，后端使用 Java 21、Spring Boot 和 PostgreSQL。Redis 用于限流，音频通过 S3 兼容接口存储。文本模型与语音厂商由后端网关接入。

源码位于 [speakpath-ai](speakpath-ai)。`apps/mobile` 包含 Web 和移动端页面，`packages` 包含共享模块，`backend` 包含应用服务和 Flyway 迁移。

## 一键部署 Web 和后端

同一份 Bash 脚本会启动 Web、后端、PostgreSQL、Redis 和本地 S3Mock，并等待服务就绪。Docker 在容器中提供 Node、Java 和 Maven。

| 系统 | 准备 |
| --- | --- |
| Windows | 安装 WSL 2（例如 Ubuntu）和 Docker Desktop，开启 Linux 容器及该发行版的 WSL 集成。在 WSL 的 Bash 中运行脚本。建议把工作副本放在 WSL 文件系统。 |
| macOS | 启动 Docker Desktop，或使用 Docker CLI、Compose 插件和 Colima。使用 Colima 时运行 `colima start`，如有多个 Docker 环境可用 `DOCKER_CONTEXT=colima bash deploy.sh`。 |
| Linux | 安装并启动 Docker Engine 和 Docker Compose v2 插件。运行脚本的账号需要具有使用 Docker 的权限。 |

首次构建需要访问 AWS 公共镜像库、Maven Central、npm registry 和 Ubuntu 软件源。部署脚本发布的端口默认只绑定到本机。运行下列命令后打开 [Web 应用](http://127.0.0.1:8081)。

```bash
bash deploy.sh
```

脚本首次运行会参考 [配置模板](speakpath-ai/.env.example) 创建 `speakpath-ai/.env`，为 PostgreSQL、Redis、对象存储和 JWT 生成随机值。重复执行会继续使用本地配置和数据卷。没填 AI 凭据时，可先使用种子配置中的模拟模型和模拟语音验证基本流程。

### 配置真实 AI 服务

可以先运行 `bash deploy.sh --prepare`，再编辑 `speakpath-ai/.env`。把实际值写入本地文件或部署环境的密钥配置。

| 功能 | 配置项 |
| --- | --- |
| DeepSeek 文本模型 | `DEEPSEEK_API_KEY`、`LLM_PROVIDER=deepseek`、`LLM_MODEL=deepseek-flash`。自定义兼容接入地址时填写 `LLM_BASE_URL`。 |
| 百炼语音识别 | `SPEECH_ASR_PROVIDER=dashscope`、`ASR_DASHSCOPE_API_KEY`、`ASR_DASHSCOPE_BASE_URL`。 |
| 百炼语音合成 | `SPEECH_TTS_PROVIDER=dashscope`、`TTS_DASHSCOPE_API_KEY`、`TTS_DASHSCOPE_BASE_URL`。 |
| 百炼实时语音会话配置 | `SPEECH_RT_PROVIDER=dashscope`、`RT_DASHSCOPE_API_KEY`、`RT_DASHSCOPE_BASE_URL`。当前适配器提供会话连接信息。 |
| 智能科教口语评测 | `SPEECH_PRON_PROVIDER=dashscope`、`PRON_DASHSCOPE_APP_ID`（项目 AppKey）、`PRON_DASHSCOPE_APP_SECRET`。Web 跟读还需要经授权的 SDK，见下文。 |
| 可选的管理员账号 | 同时填写 `ADMIN_EMAIL`、`ADMIN_PASSWORD`。首次启动会创建该账号。复用已有数据库时使用该账号实际的密码，或配置一个尚未注册的邮箱。 |

北京地域的专属语音地址通常为 `https://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api/v1`，实时地址为 `wss://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime`。Key、工作空间与地域需相匹配。使用其他文本供应商时，可按模板设置其 Key，并配套填写 `LLM_PROVIDER`、`LLM_MODEL` 和 `LLM_BASE_URL`。将 `LLM_PROVIDER` 留空可使用数据库模型路由。

如使用 Web 跟读，将经授权的供应商 `engine.js` 放在 `speakpath-ai/apps/mobile/public/sdk/engine.js`。它会在本地构建时打包进 Web 镜像。修改配置或补齐 SDK 后，再运行 `bash deploy.sh`。模型长期密钥和 AppSecret 仅传给后端。

### 日常操作

在仓库根目录运行部署脚本。在 `speakpath-ai` 目录运行下面的 Compose 命令。若启动时显式选了 Docker context，查看状态和停服时也使用同一个 context。

```bash
cd speakpath-ai
docker compose ps
docker compose logs -f backend
docker compose down
```

本地默认使用 Web `8081`、后端 `8080`、数据库 `5432`、Redis `6379` 和对象存储 `9000`。如果 Web 端口被占用，可在根目录执行 `WEB_PORT=8082 bash deploy.sh`。S3Mock 适用于本地和测试。对外部署时需要 TLS、受控的 S3 兼容存储和备份，并按实际域名设置浏览器可访问的 `APP_STORAGE_PUBLIC_ENDPOINT`。已有 PostgreSQL 数据卷的密码通过数据库内的角色管理更新，首次初始化变量只在空卷上生效。

### 前端和原生开发

本机开发时，可在 `speakpath-ai` 目录用 `pnpm install --frozen-lockfile` 和 `pnpm web` 启动 Expo；如果已部署容器版 Web，先释放端口或为开发前端选择其他端口。移动端开发使用 Expo 原生构建。iOS 本地编译需要 macOS、Xcode 和 CocoaPods，Android 需要 JDK 与 Android SDK。发音模块还需要供应商授权的原生 SDK，具体位置见 [原生模块说明](speakpath-ai/apps/mobile/modules/pronunciation/README.md)。真机使用可访问的 `EXPO_PUBLIC_API_URL` 和音频存储地址。

## Git 维护

提交源代码、配置模板和部署脚本。`speakpath-ai/.env`、私有证书、原始 Word 规格、供应商 SDK 包、旧部署模板和构建产物留在本地。运行 `git status --short` 与 `git diff --cached --stat` 核对暂存范围后再发布。已在其他代码库、聊天或共享文件中暴露的旧凭据，应在各厂商控制台撤销或轮换。
