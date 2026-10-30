# YunX HTTP

基于 [YunX-Desktop](https://github.com/tidain/YunX-Desktop) 的本机网盘解析服务，供 C++ 等程序通过 HTTP 调用。

**本 fork 已取消原有桌面模式。程序后台运行，只有发起登录时才弹出 Kotlin 登录窗口。**

- 支持分享解析、浏览目录、获取下载地址及必要请求头。
- 支持个人网盘目录浏览、新建目录、重命名、移动、删除、个人文件取链，以及创建分享和分享转存。
- 复用原有网盘登录、账号加密保存和会话处理。
- 提供自动生成的 C++17 / WinHTTP 头文件 SDK。
- GitHub Actions 构建 Windows x64 便携包和 C++ SDK，并验证实际 HTTP 调用。

支持平台：夸克、UC、迅雷、百度、139、123、115、光鸭、蓝奏云、蓝奏优享版。平台能力和下载限制以上游实际响应为准。

## 使用

从 [最新 Release](https://github.com/mcnyyds/YunX-Desktop/releases/latest) 下载便携包，解压后在 PowerShell 启动：

```powershell
$env:YUNX_HTTP_TOKEN = 'replace-with-your-own-token'
.\YunX-Desktop.exe
```

默认地址为 `http://127.0.0.1:17890/api/v1`。调用时携带 `Authorization: Bearer <令牌>`。可通过 `YUNX_HTTP_PORT` 指定端口。

默认数据保存到 `%USERPROFILE%/.yunx-http`，与原桌面版独立。登录需要交互式 Windows 用户会话。

## C++

最小示例为 [sdk/cpp/example.cpp](sdk/cpp/example.cpp)，SDK 为 [yunx_client.hpp](sdk/cpp/include/yunx_client.hpp)。

```powershell
.\sdk\cpp\build.ps1
.\build\cpp\example.exe
```

要求 C++17，链接系统 WinHTTP，JSON 使用 nlohmann/json。构建脚本自动准备 JSON 头文件；Actions 的 SDK 包包含所需头文件。

完整接口、登录流程及构建说明见 [HTTP-API.md](docs/HTTP-API.md)。

## 开发

```powershell
.\run.ps1 run       # 启动 HTTP 服务，先设置 YUNX_HTTP_TOKEN
.\run.ps1 build     # 编译 Kotlin，并生成 C++ SDK
.\run.ps1 package   # 打包，包含 Java 运行时和登录浏览器
```

沿用仓库 Gradle / PowerShell 构建流程；JDK 17 缺失时由现有脚本准备。协议定义在 `api/contract.json`，生成文件通过 `api/generate.ps1` 更新。

GitHub Actions 工作流为 [.github/workflows/http-service.yml](.github/workflows/http-service.yml)，支持 main 推送、PR 和手动运行。输出 `YunX-HTTP-windows-x64.zip` 和 `YunX-HTTP-cpp-sdk.zip`。

推送到 `main` 或在 `main` 手动运行后，测试与打包全部通过才会自动发布 GitHub Release；PR 只构建。发布标签为 `http-v<version.txt版本号>-build.<Actions流水号>`，并更新为最新 Release。每个 Release 附带服务包与 C++ SDK 包，沿用 `version.txt` 作为程序版本来源。

## 协议

本项目保留上游版权，采用 [AGPL-3.0](LICENSE) 许可证。
