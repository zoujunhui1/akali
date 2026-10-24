---
name: akali
description: akali 项目（自用 Android 英语听力 app，播放 ELLLO 课程；含 Go 后端和 Mac 工具）的业务方案、技术方案、架构方案和任务清单。在 akali 仓库里做任何事之前先读：写代码、讨论功能、建项目、加课程、后端/部署、Mac 工具、排任务时都要用。
---

# akali

自己用的 Android 英语听力 app：课程数据由云上后端提供，手机同步后离线反复听，路上也能听。只有一个用户（Zhenghui），不上架，真机是一加 15T。

## 仓库结构（三个部分分开放）

| 目录 | 是什么 |
|---|---|
| `android/` | 手机 app（Kotlin + Compose，整个 Android 工程在这里；构建都在这个目录里跑） |
| `server/` | 云上后端（Go + Gin + MySQL），部署在 Google Cloud |
| `mac/` | Mac 本机工具：akali studio 页面（抓 ELLLO、Whisper 识别、读飞书、上传后端） |
| `skills/akali/` | 本 skill 和方案文档 |

## 按需读取

| 要做的事 | 读哪个文件 |
|---|---|
| 问“要做什么 / 功能范围 / 为什么这样” | `references/业务方案.md` |
| 写代码 / 选型 / 解析 ELLLO / 播放、下载、存储设计 | `references/技术方案.md` |
| 后端 / 数据流 / 部署 / Mac 工具 / 三个部分怎么配合 | `references/架构方案.md` |
| 问“下一步做什么 / 做到哪了” | `references/任务清单.md` |

## 已定的核心决定（详情见 references）

- 所有课程音频**完整下载**到 app 私有目录，不做边播边缓存（路上没网也要能听）。
- 添加课程**在 app 里粘贴链接**，app 自己解析；不用电脑脚本、不用 Syncthing、不用云服务器。
- 文稿没有时间戳，V1 只显示整段，可隐藏/显示；逐句同步和单句循环放后续。
- 不做生词、学习记录、账号。
- 技术栈：Kotlin + Compose，Media3，Room，OkHttp + Jsoup，MVVM，不用 Hilt。

## 工作约定

- **先讨论，后动手**。方案里没定的事不要自己拍板写进代码，先问；用户明确说“先别开始”就不要运行或修改任何东西。
- **用户不写代码，也不操作 Android Studio**。建项目、写代码、构建、安装到手机，全部由 Claude 用命令行完成（Gradle、adb）；用户只做必须在手机上做的事（开 USB 调试、授权、实机试用并反馈）。需要用户动手时，给出一步一步的具体操作。
- 这个项目不需要教学式讲解；汇报用大白话，说清做了什么、怎么验证、有什么要用户确认。
- 用户在意 token，写完文件后一两句话说清是什么、在哪，不要把内容再念一遍。
- 方案有变动时，同步更新 `references/` 里对应文件，保持这里是唯一的事实来源。
- 仓库只放代码和文档；课程内容（mp3、transcript）、口令（`mac/.env`、`server/.env`、`android/local.properties`）、官方美术图都不进 git。
- 动 Google Cloud 控制台的安全类设置（SSH 密钥、防火墙）会被系统拦截，给用户步骤让他自己点。
