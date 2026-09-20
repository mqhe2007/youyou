# 柚柚相册（youyou）

> 轻松管理人生影相

柚柚相册是一个自部署的**多用户照片管理应用**，由 Android 客户端与用户自部署服务端组成。服务端是远程媒体与元数据的事实来源，客户端承载本地索引、缓存与入口；时间线由二者合并呈现。原始文件永远只存一份。

## 核心理念

- **服务端数据优先** — 服务端是远程媒体与元数据的事实来源；客户端 Room 承载本地媒体索引、远程投影缓存与待上传队列。时间线是「本机索引 + 服务端投影」的**合并网格**，不是服务端清单的镜像
- **单一媒体文件源** — 服务端对挂载目录原位索引，不复制原文件；上传文件只落盘一份（区别于 immich 的双份空间问题）
- **多用户隔离** — 每个用户绑定各自的媒体库目录，一个目录只能被一个用户绑定，媒体/标签/收藏完全隔离
- **客户端极简** — 只做扫码登录、时间线、标签、收藏、设置，不做低价值功能
- **隐私自主** — 自部署、不追踪不分析；服务端离线时客户端仍可浏览缓存

## 功能

- **扫码登录** — 管理员为每个用户生成专属二维码，扫码即绑定该用户的媒体库
- **时间线** — 服务端与本机媒体合并展示，缩略图用手机/云朵标注仅本机/仅服务端，已同步不打标，按需筛选与选择同步，年月刻度快速导航
- **备份双向** — 选择单张或多张「仅本机」照片上传备份；将「仅服务端」照片下载到手机
- **标签** — 按用户隔离的标签创建、打标与浏览
- **收藏** — 客户端与服务端均有独立收藏浏览页，收藏跨端同步
- **轻量服务端** — Rust 单服务 + SQLite，嵌入式管理页：媒体库目录刷新与管理、用户二维码、运行日志、备份

明确不做：逻辑相册、搜索、AI 识别、照片地图、去重、分享。

## 平台支持

- 客户端：Android 12（API 31）及以上
- 服务端：Docker 自部署（多架构镜像）

## 部署

面向使用者：拉现成镜像即可，不需要源码也不需要构建工具链。完整步骤见官网
[快速开始](https://youyou.mengqinghe.com/quickstart)，条款见
[隐私政策](https://youyou.mengqinghe.com/privacy) 与
[服务条款](https://youyou.mengqinghe.com/terms)。

```bash
curl -O https://raw.githubusercontent.com/mqhe2007/youyou/main/deploy/docker-compose.yml
docker compose up -d
```

## 开发

环境：JDK 17、Android SDK（platform 37，minSdk 31）、Rust 1.95+、Node 24、Docker。

```bash
make server-check     # 格式 + 编译 + clippy（先构建内嵌管理页）
make server-test      # 服务端测试
make contract-check   # OpenAPI 契约
make e2e              # 端到端，真起服务端进程
make benchmark        # 十万条媒体规模基线，超阈值即失败
make client-test      # Android 单元测试
make e2e-android      # Android instrumented 测试，自动起模拟器
make acceptance       # 以上全部
```

客户端构建：`cd apps/android && ./gradlew assembleDebug`。签名 Release 需要仓库根
目录下的 `release-signing/keystore.properties`（不入库）；缺失时 Release 打包直接
失败，以免误发未签名包。正式包由 GitHub Actions 在打 tag 时构建并发布到本仓库
Releases，`workflow_dispatch` 可走同样的签名流程但不发布。

本地起服务端：`cargo run --manifest-path apps/server/Cargo.toml`，管理页在
`http://127.0.0.1:8989/admin`。

## 工程文档

产品需求、体验设计、设计系统、架构与数据模型、技术栈、媒体时间规则、删除与回收
规则、规模验收基准等工程文档不在仓库内维护。项目当前按单人开发组织，这些口径记录
在项目内部知识库里；对外公开的只有官网的部署、隐私与条款三页。

## 许可证

本项目以 **AGPL-3.0-only** 授权，条款全文见 [LICENSE](LICENSE)。

- 你可以自由使用、修改、分发，包括商用
- 但一旦分发修改版本，或把它作为网络服务对外提供，就必须以同一许可证公开完整源码
  —— 这是 AGPL 相对 GPL 的主要差异，目的正是防止他人拿它做闭源托管服务
- 项目方保留以其他许可条款单独授权的权利（双许可）
