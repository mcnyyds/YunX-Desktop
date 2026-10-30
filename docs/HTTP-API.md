# YunX HTTP 服务与 C++ SDK

本 fork 仅提供本机 HTTP 服务。启动时没有桌面主窗口和闪屏；请求登录时才打开 Kotlin 登录窗口。需要 Windows 10/11 x64 的交互式用户会话，不能把网页登录窗口运行在 Windows 服务的 Session 0 中。

## 启动

在 PowerShell 中设置服务令牌，然后启动便携包：

```powershell
$env:YUNX_HTTP_TOKEN = 'replace-with-your-own-token'
$env:YUNX_HTTP_PORT = '17890' # 可省略，默认 17890
.\YunX-Desktop.exe
```

开发运行仍使用 `./run.ps1 run`。服务启动即监听 `127.0.0.1`，所有接口（包括健康检查）均要求 `Authorization: Bearer <令牌>`。C++ 程序启动服务时，可通过子进程环境传入相同配置。

默认数据目录为 `%USERPROFILE%/.yunx-http`，独立于原桌面版；可通过 `YUNX_DESKTOP_DATA_DIR` 指定。凭证使用原有加密存储，不自动导入原桌面版数据。每个数据目录只能启动一个服务实例。账号列表保存在本机；登录任务、分享会话与清理标识只在进程内保存，重启后重新打开分享。退出服务可由启动它的宿主结束进程。

## C++ 接入

要求 C++17、Windows SDK / WinHTTP 和 nlohmann/json 3.11.3。SDK 是单个 `yunx_client.hpp`，HTTP 使用系统 WinHTTP，响应类型为 `yunx::json`，请求使用生成的结构体。

```powershell
# 从仓库根目录构建最小示例，自动准备 JSON 头文件
.\sdk\cpp\build.ps1
$env:YUNX_HTTP_TOKEN = 'replace-with-your-own-token'
.\build\cpp\example.exe
```

示例只检查默认端口的健康状态。SDK 本身通过构造函数指定端口和超时：

```cpp
yunx::Client client(17890, token, 120000);
auto task = client.start_login({"quark"});
auto state = client.get_login(task.at("id").get<std::string>());
```

查询登录任务直到 `succeeded`、`cancelled` 或 `failed`；`succeeded` 的 `account.id` 是后续解析用的 `accountId`。同步 HTTP 调用建议放在调用方的工作线程。

```cpp
auto share = client.open_share({link, password, account_id});
auto files = share.at("files");
auto result = client.get_download_link(
    share.at("id").get<std::string>(),
    {files.at(0).at("id").get<std::string>(), true});
```

调用方应选择非目录文件。下载时原样使用 `result.headers` 中的请求头；不要更换账号 Cookie。`isHls=true` 表示结果是 HLS 流，需要支持 HLS 的下载或播放实现。URL 受上游有效期约束，失效后重新取链。

`cleanupId` 非空时，在下载完成或放弃使用该链接后调用 `client.cleanup(share_id, {cleanup_id})`。正在下载时不能清理，因为部分平台直链依赖临时转存文件继续存在。服务重启不会自动删除未完成的临时转存。

清理沿用上游仓库的尽力而为行为，`cleaned=true` 表示已执行清理调用，不保证云端删除已完成；残留文件可在网盘中手动清理。

MSVC 编译需链接 `winhttp.lib`（头文件自带链接指令）；MinGW 需要 `-lwinhttp`。CI 的 SDK 压缩包已包含 `include/nlohmann/json.hpp`，加入 `include` 目录即可使用。

## 接口

基础路径：`http://127.0.0.1:17890/api/v1`。请求和响应均使用 UTF-8 JSON；POST 请求传 JSON 对象。可选字符串可省略或传空串，不接受 `null`。平台 ID 使用小写。

| 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/health` | 无 | `status, version, apiVersion` |
| GET | `/platforms` | 无 | `platforms: [{id, login, share, guestDownload}]` |
| POST | `/auth/logins` | `platform`, 可选 `newAccount=false` | 登录任务 |
| GET | `/auth/logins/{id}` | 无 | 登录任务 |
| DELETE | `/auth/logins/{id}` | 无 | 取消后的任务；已结束任务保持原结果 |
| GET | `/accounts` | 无 | `accounts: [{id, platform, nickname, remark, isPrimary}]` |
| DELETE | `/accounts/{id}` | 无 | `removed: true` |
| POST | `/shares` | `link`, 可选 `password, accountId` | `id, platform, title, rootDirectoryId, files` |
| POST | `/shares/{id}/list` | `directoryId`（允许空串） | `files` |
| POST | `/shares/{id}/download-link` | `fileId`, 可选 `preferNoSave=false` | `url, headers, filename, size, isHls, cleanupId` |
| POST | `/shares/{id}/cleanup` | `cleanupId` | `cleaned: true` |
| POST | `/drives/list` | `accountId`，可选 `directoryId` | `rootDirectoryId, directoryId, files` |
| POST | `/drives/folders` | `accountId, name`，可选 `parentId` | `directoryId, status` |
| POST | `/drives/rename` | `accountId, fileId, name` | `fileId, status` |
| POST | `/drives/move` | `accountId, fileIds`，可选 `targetDirectoryId` | `results` |
| POST | `/drives/delete` | `accountId, fileIds` | `results` |
| POST | `/drives/download-link` | `accountId, fileId` | `url, headers, filename, size, isHls` |
| POST | `/drives/create-share` | `accountId, fileIds`，可选 `title, expirationDays, password` | `shareUrl, password, expirationDays, warning` |
| POST | `/shares/{id}/transfer` | `accountId, fileIds`，可选 `targetDirectoryId` | `targetDirectoryId, results` |

文件项包含 `id, name, size, isDirectory, parentId`。先列目录，再用返回的文件 ID 取链；`rootDirectoryId` 应原样使用，不同平台的根目录标识可能为空串或 `0`。

登录任务结构：

```json
{
  "id": "login-id",
  "platform": "quark",
  "status": "succeeded",
  "account": {"id": "quark", "platform": "quark", "nickname": "昵称", "remark": "", "isPrimary": true},
  "message": ""
}
```

`pending` 时 `account=null`。全服务同时只显示一个登录任务，重复创建返回 HTTP 409。`newAccount=true` 添加账号；默认更新当前主账号。输入错误可在窗口内继续尝试，关闭窗口表示取消。

不传 `accountId` 表示明确使用游客模式，不自动选主账号。`guestDownload` 表示实现了游客取链路径，不保证任意文件可下载，仍有平台大小、权限等限制。当前支持夸克、UC、迅雷、百度、139、123、115、光鸭、蓝奏云、蓝奏优享版；GitHub 和视频解析不属于本接口。

删除账号只移除本地保存凭证，不等于向网盘平台撤销登录。已返回给调用方的下载信息不会被撤回。

## 个人网盘操作

个人网盘接口要求显式指定已登录的 `accountId`。`directoryId`、`parentId` 和 `targetDirectoryId` 省略或为空串时表示该账号网盘的根目录；`/drives/list` 返回实际的 `rootDirectoryId`。目录查询自动跟随上游分页。

个人网盘文件项使用 `fileId, name, isDirectory, size, parentId`，与分享列表的 `id` 字段区分。先列出所在目录，再用返回的 `fileId` 操作文件。打开目录时，把目录项的 `fileId` 作为下一次查询的 `directoryId`。百度的文件 ID 与操作路径、蓝奏系列的目录前缀均由服务端适配，不需 C++ 自行转换。

文件元数据按账号隔离并保存在服务进程中；重启后需要重新列目录。重命名、移动或删除后需刷新所在目录；操作目录时，该账号已有目录快照会失效，需要重新浏览。`/drives/folders` 返回的 `directoryId` 可直接用于列目录。

```cpp
auto list = client.drive_list({account_id});
auto folder = client.create_folder({account_id, "", "新目录"});
auto result = client.move({account_id, {file_id}, folder.at("directoryId").get<std::string>()});
```

`fileIds` 为非空字符串数组，不能包含空串或重复 ID。文件名必须是单个名称，不能是 `.`、`..`，不能包含 `/` 或 `\`。调用个人文件取链接口后，按原样使用返回的下载请求头；目录不能直接取下载链接。上传、复制和回收站管理不在本版范围内。

## 创建分享

```cpp
auto share = client.create_share({account_id, {file_id}, "资料", 7, "abcd"});
```

`expirationDays` 是整数，`0` 表示永久，省略或 `-1` 表示默认策略：支持自定义有效期的平台使用永久，蓝奏云使用平台已有分享设置。其余天数必须出现在该平台 `allowedExpirationDays` 中。服务端负责转换平台原生参数，例如夸克 7 天对应 `expired_type=3`，115 永久对应 `share_duration=-1`，123 永久使用其既有日期哨兵。

普通平台支持 0、1、7、30 天；115 支持 0、1、3、5、7、15 天。蓝奏云一次只能分享一个文件或目录，提取码和有效期使用平台现有设置；蓝奏优享版目前不支持创建分享。139、115 和蓝奏云不支持本接口自定义提取码，不会静默忽略传入的密码。百度必须有四位提取码，省略时由服务生成并通过响应返回。夸克、UC、迅雷和百度的自定义提取码限四位字母数字。

`warning` 非空表示分享已经创建，但某项后续设置未确认生效；应保留返回的链接。响应的 `expirationDays=null` 表示无法确认有效期，不能按请求值假定已生效。

## 分享转存及批量结果

先使用 `/shares` 打开分享并浏览要转存的条目，再调用：

```cpp
auto result = client.transfer_share(share_id, {account_id, {shared_file_id}, target_directory_id});
```

`accountId` 是转存目标账号，必须与分享属于同一平台；可以与打开分享时的账号不同。Kotlin 会为目标账号重新建立分享凭证并获取所选条目元数据。目录使用平台原生转存能力，保留现有空间检查。蓝奏云目前不支持分享转存。

批量移动、删除、转存返回 HTTP 200 和逐项 `results`，其中既可能有成功项，也可能有失败项。移动、删除项使用 `fileId`；转存项使用 `sourceFileId`，并提供 `savedFileId`（没有可靠返回值时为 `null`）。

```json
{
  "targetDirectoryId": "destination",
  "results": [
    {"sourceFileId":"one","status":"completed","savedFileId":"saved-one"},
    {"sourceFileId":"two","status":"submitted","savedFileId":null,"taskId":"platform-job","message":"仍在处理"},
    {"sourceFileId":"three","status":"failed","savedFileId":null,"error":{"code":"UPSTREAM_ERROR","message":"平台返回原因"}}
  ]
}
```

`completed` 表示平台操作成功返回；`submitted` 表示异步请求已接收但尚未取得完成结果，可能附带平台任务 ID；通过刷新目标目录确认最终结果。本版不增加通用后台任务系统。光鸭和蓝奏优享版转存不返回新文件 ID，115 的文件名匹配也不足以可靠区分同名旧文件，因此这些平台的 `savedFileId` 保持 `null`。

`failed` 表示该项调用未成功确认，错误信息在 `error` 中。网络错误、HTTP 超时或轮询结束都不保证云端没有执行；写操作不自动重试，重试前先检查目标目录。整个请求的参数、账号、平台或批量空间检查失败时，仍使用普通 HTTP 错误响应。

`/platforms` 在原有字段之外新增 `listFiles, createFolder, rename, move, delete, personalDownload, createShare, transferShare, batchShare, moveDirectory, allowedExpirationDays, customSharePassword`。客户端可按这些字段显示可用操作。

## 错误

错误响应格式固定：

```json
{"error":{"code":"INVALID_LINK","message":"Cannot identify a supported cloud share link"}}
```

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_LINK`, `UNSUPPORTED_PLATFORM`, `IS_DIRECTORY`, `NOT_DIRECTORY`, `PLATFORM_MISMATCH` | 参数或操作不正确 |
| 401 | `UNAUTHORIZED` | 服务令牌不正确 |
| 401 | `LOGIN_REQUIRED` | 该平台游客无法取链，需要指定账号 |
| 401 | `LOGIN_EXPIRED` | 上游明确报告登录态过期（当前识别夸克错误码 31001） |
| 404 | `NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `LOGIN_NOT_FOUND`, `SHARE_NOT_FOUND`, `FILE_NOT_FOUND`, `CLEANUP_NOT_FOUND` | 对应资源不存在 |
| 409 | `LOGIN_IN_PROGRESS` | 已有登录窗口 |
| 422 | `UNSUPPORTED_OPERATION` | 平台未实现该操作 |
| 502 | `UPSTREAM_ERROR` | 上游错误；查看 message 获取平台返回原因 |

SDK 将 HTTP 业务错误抛为 `yunx::ApiError`，包含 `status`、`code` 和 `what()`；网络和 JSON 解码错误抛为标准异常。未能可靠识别的上游提取码、过期和权限错误统一保留为 `UPSTREAM_ERROR`，不会猜测细分原因。

## 构建与验证

```powershell
.\api\generate.ps1             # 生成 C++ 头文件和 Kotlin 请求模型/路由
.\run.ps1 build                # 编译 Kotlin，自动生成协议代码
.\sdk\cpp\build.ps1 -Tests    # 编译最小示例和集成测试客户端
.\run.ps1 package              # 沿用现有便携包流程
.\ci\http-smoke.ps1            # 测试打包后的真实服务
```

协议源头为 `api/contract.json`，HTTP 传输模板为 `api/yunx_client.hpp.in`，生成文件不能手改。返回 JSON 字段的定义见本文。

GitHub Actions 在 Windows 上运行现有 Kotlin 测试及新增 HTTP 集成测试，编译 C++ SDK，再对便携包运行 C++ 冒烟测试；上传服务包和 SDK 包。CI 使用本地上游 HTTP fixture 验证真实解析代码，不需要网盘凭证。真实扫码、验证码、各平台在线解析及会员限制仍需人工验收。

项目代码继续遵循仓库 AGPL-3.0 许可证；随 SDK 分发的 nlohmann/json 头文件保留其 MIT 许可证声明。
