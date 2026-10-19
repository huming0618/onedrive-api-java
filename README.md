# onedrive-api-java

基于 **MSAL4J** + **Microsoft Graph REST**（`java.net.http`）的 OneDrive Java 客户端与命令行工具。

支持：

- CLI：`auth` / `ls` / `ls-recursive` / `download` / `upload`
- 列出文件夹（自动跟随 `@odata.nextLink` 分页）
- 递归列出全部文件
- 上传（≤4MiB 简单上传；更大文件 upload session）
- 下载（可跳过同大小本地文件；断点友好）
- 持久化 MSAL 令牌缓存（脚本连续调用不必每次扫设备码）

认证方式（可配置）：

| 模式 | `onedrive.auth.mode` | 说明 |
|------|----------------------|------|
| 设备码（推荐 CLI） | `device_code` | 浏览器在另一台设备上登录 |
| 客户端凭据（守护进程） | `client_credentials` | 需 `clientSecret` + `userId`，应用权限 |
| 交互式浏览器 | `interactive` | 本机打开浏览器登录 |

包结构：`com.example.onedrive`（`config` / `auth` / `client` / `model` / `cli` / `util`）。

---

## 快速上手（5 步）

以 Windows + PowerShell、个人微软账号为例（Linux/macOS 把路径换成 `/` 即可）。

**第 1 步：准备环境**  
安装 JDK 17+ 和 Maven 3.8+，确认 `java -version`、`mvn -v` 能运行。

**第 2 步：注册应用拿 clientId**  
按下文「1.1 免费路径」注册，记下 **应用程序(客户端) ID**，并在「身份验证」里把 **允许公共客户端流** 设为 **是**。

**第 3 步：设置环境变量并编译**

```powershell
git clone https://github.com/huming0618/onedrive-api-java.git
cd onedrive-api-java
$env:ONEDRIVE_CLIENT_ID = "你的客户端ID"
$env:ONEDRIVE_TENANT_ID = "consumers"     # 个人账号；工作账号填租户ID或 common
$env:ONEDRIVE_AUTH_MODE = "device_code"
mvn -q package
```

想长期生效可用 `setx ONEDRIVE_CLIENT_ID "你的客户端ID"`（新开的终端才生效）。

**第 4 步：首次登录**

```powershell
java -jar .\target\onedrive-api-java-1.0.0.jar auth
```

终端会提示打开 `https://microsoft.com/devicelogin` 并输入一串代码，浏览器登录后命令返回 0。令牌会缓存，后续命令和脚本不必再登录。

**第 5 步：查看与下载**

```powershell
# 看根目录
java -jar .\target\onedrive-api-java-1.0.0.jar ls /
# 下载单个文件
java -jar .\target\onedrive-api-java-1.0.0.jar download "Documents/a.pdf" D:\tmp\a.pdf
# 整盘（或某个目录）批量拷贝，可中断后续传；结束后自动输出校验报告
.\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup
# 之后随时只做校验（不下载）
.\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup -VerifyOnly
```

若提示脚本无法运行（执行策略限制），在当前窗口先执行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

**在自己的脚本里调用时：** 结果读 stdout（建议加 `--json` 或 `--tsv`），错误读 stderr，并用 `$LASTEXITCODE`（Bash 用 `$?`）判断成败，含义见「退出码」。

### 常见报错

| 报错 | 原因与处理 |
|------|-----------|
| `AADSTS70002 ... must be marked as 'mobile'` | 应用注册 → 身份验证 → 允许公共客户端流设为「是」，并添加「移动和桌面应用程序」平台 |
| `Tenant does not have a SPO license` | 工作/学校租户没有 OneDrive for Business 许可；改用个人账号 + `consumers` |
| `User not found`（client_credentials） | `userId` 不是本租户里的用户；个人使用请改设备码模式 |
| `quotaLimitReached`（HTTP 507） | OneDrive 空间已满，只影响上传；清理空间后再上传 |
| `localhost/?error=invalid_request` | 交互式登录配置不对；改用 `device_code` |
| 每次都要求登录 | 检查 `~/.onedrive-api-java/token_cache.json` 是否可写，或 `ONEDRIVE_TOKEN_CACHE` 指向的路径 |
| `/usr/bin/env: "bash\r": 没有那个文件或目录` | `onedrive-sync.sh` 是 CRLF（Windows 换行）；执行 `sed -i 's/\r$//' scripts/onedrive-sync.sh` 后再用 bash 跑 |
| `set: pipefail` / `无效的选项名` | 用了 `sh` 而不是 `bash`；改为 `bash scripts/onedrive-sync.sh ...` |

---

## 1. Azure 应用注册（免费）

**费用说明：** 注册应用、拿到 `clientId` **本身不收费**。这里不是「买 API Key」，而是在 Microsoft Entra ID（原 Azure AD）里创建一个应用身份。用个人微软账号（Outlook / Hotmail / Xbox 等）或免费 Azure 账号即可。本项目推荐的 **设备码登录 + 委托权限 `Files.ReadWrite`** 只访问你自己登录的那份 OneDrive，正常个人读写一般不会产生 Graph 按次账单。只有开通付费 Azure 订阅、使用企业应用权限去管别人的网盘、或叠加其它付费 Azure 服务时，才可能产生费用。

### 1.1 免费路径（个人微软账号 + 设备码，推荐）

1. 用个人微软账号打开 [应用注册](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)（若提示创建免费 Azure 订阅，按向导开通「免费」即可，本教程不需要付费资源）。
2. 点击 **新注册**：
   - **名称**：例如 `onedrive-api-java-demo`
   - **受支持的帐户类型**：选「任何组织目录中的帐户和个人 Microsoft 帐户」（对应本项目可用 `tenantId=common`）；若只要自己公司目录，选单租户并记下租户 GUID。
   - **重定向 URI**：可先留空（设备码流不强制）；若稍后用交互式浏览器登录，平台选「移动和桌面应用程序」并加 `http://localhost`。
3. 注册完成后，在「概述」页复制：
   - **应用程序(客户端) ID** → 环境变量 `ONEDRIVE_CLIENT_ID`
   - **目录(租户) ID** → `ONEDRIVE_TENANT_ID`（多租户/个人演示可直接用 `common` 或 `consumers`）
4. **API 权限** → **添加权限** → **Microsoft Graph** → **委托的权限**，勾选：
   - `Files.ReadWrite`（必需）
   - `User.Read`、`offline_access`（推荐；`offline_access` 利于刷新令牌）
   个人账号一般点「添加」即可；公司租户若要求管理员同意，再点 **授予管理员同意**。
5. **身份验证** → **高级设置** → **允许公共客户端流** 设为 **是**（设备码登录必需）。可再添加平台「移动应用程序和桌面应用程序」。
6. **不需要**客户端密码（`clientSecret`）。把 `ONEDRIVE_AUTH_MODE=device_code`，运行 `auth` 后按 **stderr** 里的 `https://microsoft.com/devicelogin` 提示用浏览器登录即可。

### 1.2 其它注册要点（简表）

若你已按上面 1.1 做完，可跳过本小节。完整对照如下：

1. 打开 [Azure Portal → Microsoft Entra ID → 应用注册](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)，点击 **新注册**。
2. 名称随意；受支持的帐户类型按需要选择（单租户填目录，多租户/个人可用 `common`）。
3. **重定向 URI**（平台选「公共客户端/本机」或「移动和桌面应用程序」）：
   - 设备码：可不配重定向
   - 交互式：添加 `http://localhost`
4. 注册完成后记下：
   - **应用程序(客户端) ID** → `clientId`
   - **目录(租户) ID** → `tenantId`（多租户演示可用 `common`）

### 委托权限（device_code / interactive）

1. **API 权限** → **添加权限** → **Microsoft Graph** → **委托的权限**
2. 勾选：
   - `Files.ReadWrite`（读写用户 OneDrive）
   - `User.Read`（可选，便于确认登录用户）
   - `offline_access`（刷新令牌，建议打开）
3. 若租户要求管理员同意，点击 **为 [租户] 授予管理员同意**。
4. **身份验证** → 高级设置 → 允许公共客户端流：**是**（设备码需要）。

### 应用权限（client_credentials）

1. **API 权限** → **Microsoft Graph** → **应用程序权限**
2. 添加 `Files.ReadWrite.All`（或更细粒度的 Sites/Files 权限），并 **授予管理员同意**。
3. **证书和密码** → 新建客户端密码 → 记下密钥值 → `clientSecret`。
4. 配置 `onedrive.userId` 为要访问的用户 **UPN** 或 **对象 ID**（应用身份不能用 `/me`）。目标租户还需具备 SharePoint Online / OneDrive 许可。

---

## 2. 配置 clientId / tenantId

**不要**把密钥写进源码。任选其一：

### 方式 A：环境变量（推荐）

```bash
export ONEDRIVE_CLIENT_ID="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
export ONEDRIVE_TENANT_ID="consumers"       # 个人账号常用；也可用 common
export ONEDRIVE_AUTH_MODE="device_code"     # device_code | client_credentials | interactive
# 可选：自定义令牌缓存路径
# export ONEDRIVE_TOKEN_CACHE="$HOME/.onedrive-api-java/token_cache.json"
# client_credentials 时还需要：
# export ONEDRIVE_CLIENT_SECRET="..."
# export ONEDRIVE_USER_ID="user@contoso.com"
```

也兼容 `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_CLIENT_SECRET`。

### 方式 B：属性文件

```bash
cp src/main/resources/application.properties.example application.properties
# 编辑 application.properties（已被 .gitignore 忽略）
```

示例：

```properties
onedrive.clientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
onedrive.tenantId=common
onedrive.auth.mode=device_code
onedrive.scopes=Files.ReadWrite User.Read offline_access
```

环境变量优先级高于属性文件。

### 令牌缓存（脚本必读）

首次 `device_code` / `interactive` 登录成功后，MSAL 缓存写入：

- 默认：`~/.onedrive-api-java/token_cache.json`
- 覆盖：环境变量 `ONEDRIVE_TOKEN_CACHE`

之后每次 CLI 调用会先 **静默刷新**，不必重新扫码。删除该文件即可强制重新登录。

---

## 3. 构建

要求：**JDK 17+**、Maven 3.8+。

```bash
cd onedrive-api-java
mvn -q package
# 产物：target/onedrive-api-java-1.0.0.jar（shade 可执行包）
```

---

## 4. 命令行用法

```text
java -jar target/onedrive-api-java-1.0.0.jar [全局选项] <命令> [参数]
```

### 全局选项

| 选项 | 说明 |
|------|------|
| `--json` | stdout 每行一个 JSON 对象（NDJSON），便于脚本/`jq` 解析 |
| `--tsv` | stdout 制表符分隔：`path size id lastModified kind name`（路径可含空格，勿用空格分隔） |
| `--help` / `-h` | 帮助 |

**约定：** 业务结果写 **stdout**；设备码提示、进度、日志、错误写 **stderr**。

### 命令

| 命令 | 说明 |
|------|------|
| `auth` | 登录 / 刷新令牌，打印账号与缓存路径 |
| `ls [remotePath]` | 列出目录子项（默认 `/`） |
| `ls-recursive` / `tree` `[remotePath]` | 递归列出全部**文件**（含分页） |
| `download [--overwrite\|--skip-same-size] <remote> <local>` | 下载；默认同大小则跳过 |
| `upload <local> <remote>` | 上传到远端路径（含文件名） |

### 退出码

| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 1 | 用法 / 参数错误 |
| 2 | 认证或配置错误（缺 clientId、登录失败等） |
| 3 | Graph / API 错误（NotFound、quotaLimitReached 等） |
| 4 | 本地 I/O 错误 |

### 示例

```bash
# 首次登录（设备码出现在 stderr）
java -jar target/onedrive-api-java-1.0.0.jar auth

# 列根目录
java -jar target/onedrive-api-java-1.0.0.jar ls /

# 递归列出（脚本友好）
java -jar target/onedrive-api-java-1.0.0.jar --tsv ls-recursive /
java -jar target/onedrive-api-java-1.0.0.jar --json ls-recursive Documents

# 下载 / 上传
java -jar target/onedrive-api-java-1.0.0.jar download "Photos/a.jpg" ./a.jpg
java -jar target/onedrive-api-java-1.0.0.jar download --overwrite "Photos/a.jpg" ./a.jpg
java -jar target/onedrive-api-java-1.0.0.jar upload ./report.pdf Documents/report.pdf
```

Windows（PowerShell）同样可用：

```powershell
java -jar .\target\onedrive-api-java-1.0.0.jar auth
java -jar .\target\onedrive-api-java-1.0.0.jar --tsv ls-recursive /
```

---

## 5. 批量同步脚本（断点续传 + 校验报告）

将 OneDrive 上（某个目录下）的**全部文件**拷到本地，保留相对路径；失败不中断；进度写入状态目录，可再次运行续传。下载结束后会自动生成**校验报告**，对比本地与远端的文件数和总字节数。

状态目录：`<目标目录>/.onedrive-sync/`

| 文件 | 作用 |
|------|------|
| `manifest.tsv` | 远端文件清单（`--tsv ls-recursive`），校验时作为「远端」基准 |
| `done.txt` | 已成功（或已跳过完成）的远端路径 |
| `failed.txt` | 本轮下载失败的路径与错误信息 |
| `verify-report.txt` | 最近一次校验报告（含时间） |
| `missing.txt` | 清单里有、本地没有的文件：`路径<TAB>远端大小` |
| `size-mismatch.txt` | 本地存在但大小不一致：`路径<TAB>远端大小<TAB>本地大小` |
| `extra.txt` | 本地有、清单里没有的文件（仅供参考，不影响结果；已排除 `.onedrive-sync`） |

### Bash

需要 Bash 4+（macOS 自带 3.2，请 `brew install bash`）。路径可含空格和中文。

**务必用 `bash` 跑脚本，不要用 `sh`。** 脚本须为 Unix LF 换行。若从 Windows 拷到 Ubuntu 后出现 `/usr/bin/env: "bash\r": 没有那个文件或目录` 或 `set: pipefail` / `无效的选项名`，先转成 LF 再用 bash：

```bash
sed -i 's/\r$//' scripts/onedrive-sync.sh
bash scripts/onedrive-sync.sh /data/onedrive-backup
```

```bash
# 可选
export ONEDRIVE_JAR=/path/to/onedrive-api-java-1.0.0.jar
export ONEDRIVE_JAVA=java

# 同步整个网盘到本地目录（结束后自动校验）
bash scripts/onedrive-sync.sh /data/onedrive-backup

# 只同步 Documents，并强制刷新清单
ONEDRIVE_REFRESH_MANIFEST=1 bash scripts/onedrive-sync.sh /data/backup Documents

# 只校验，不下载（用已有 manifest.tsv，不需要 Java / 登录）
bash scripts/onedrive-sync.sh --verify-only /data/onedrive-backup
ONEDRIVE_VERIFY_ONLY=1 bash scripts/onedrive-sync.sh /data/onedrive-backup
```

进度示例（stderr）：`[123/4567] 2.7% Photos/a.jpg (102400 bytes)`

### PowerShell（Windows）

兼容 Windows PowerShell 5.1 与 PowerShell 7+。

```powershell
$env:ONEDRIVE_JAR = "C:\path\to\onedrive-api-java-1.0.0.jar"

.\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup
.\scripts\onedrive-sync.ps1 -Dest D:\Backup -RemoteRoot Documents -RefreshManifest

# 只校验，不下载
.\scripts\onedrive-sync.ps1 -Dest D:\OneDriveBackup -VerifyOnly
```

若提示脚本无法运行（执行策略限制），先在当前窗口执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`。

### 校验报告

校验逐个检查 `manifest.tsv` 里的文件在本地是否存在、实际大小是否与远端一致，并给出：

- 远端文件数与总大小（来自清单）
- 本地已存在的文件数与实际总大小
- 校验通过（存在且大小一致）的文件数与大小，以及按数量、按字节的完成百分比
- 缺失文件数、大小不一致文件数（前 10 条直接列出，完整列表见 `missing.txt` / `size-mismatch.txt`）
- 本地多出的文件（仅提示）
- 最后一行结论：`RESULT: PASS`（文件数和字节数都对上，无缺失、无大小不一致）或 `RESULT: FAIL`

报告同时打印到控制台（Bash 为 stderr）并保存到 `verify-report.txt`。示例：

```text
===== OneDrive sync verification report =====
Time:        2026-10-19 20:55:48 CST
Destination: /data/onedrive-backup
Manifest:    /data/onedrive-backup/.onedrive-sync/manifest.tsv

Remote (manifest): 4 files, 1.40 GB (1503002053 bytes)
Local present:     3 files, 2.10 KB (2153 bytes)
Verified OK:       2 files, 2.00 KB (2053 bytes)
Complete:          50.0% by count, 0.0% by bytes

Missing:           1 files, 1.40 GB (1500000000 bytes)  -> .../missing.txt
Size mismatch:     1 files  -> .../size-mismatch.txt
Extra local (info): 1 files, 6 B (6 bytes)  -> .../extra.txt

First missing files:
  - Photos/missing one.png (1500000000 bytes)

First size mismatches (remote -> local):
  - Photos/big.jpg (3000000 -> 100 bytes)

RESULT: FAIL
=============================================
```

大小单位按 1024 进位（1 GB = 1024³ 字节），括号内为精确字节数。远端清单是生成 `manifest.tsv` 时的快照；网盘内容之后有变化时，先用 `ONEDRIVE_REFRESH_MANIFEST=1` / `-RefreshManifest` 重新同步一次再校验。

### 脚本退出码

| 码 | 含义 |
|----|------|
| 0 | 全部成功：无下载失败，且校验 PASS |
| 1 | 用法错误、找不到 jar，或 `--verify-only` 时没有 `manifest.tsv` |
| 2 | 登录 / 认证失败 |
| 3 | 有文件下载失败（**优先于 5**：既有下载失败又校验 FAIL 时返回 3） |
| 5 | 校验 FAIL（有缺失或大小不一致） |

有失败时，修好问题后用同一命令再跑即可跳过 `done.txt` 中的路径，结束后会重新校验。

---

## 6. 在代码中使用

```java
AppConfig config = AppConfig.load();
MsalTokenProvider tokens = new MsalTokenProvider(config);
try (OneDriveClient client = new OneDriveClient(config, tokens)) {
    List<DriveItemInfo> items = client.listChildren("Documents");
    List<DriveItemInfo> allFiles = client.listFilesRecursive("/");
    client.uploadFile(Path.of("report.pdf"), "Documents/report.pdf");
    client.downloadFile("Documents/report.pdf", Path.of("/tmp/report.pdf"), false);
}
```

路径均相对于对应用户 OneDrive **根目录**（不要以盘符开头；前导 `/` 可选）。

---

## 7. 项目结构

```text
onedrive-api-java/
├── pom.xml
├── README.md
├── .gitignore
├── scripts/
│   ├── onedrive-sync.sh      # Bash 全量同步 + 断点续传 + 校验报告
│   └── onedrive-sync.ps1     # PowerShell 等价脚本
└── src/main/
    ├── java/com/example/onedrive/
    │   ├── Main.java                    # CLI 入口
    │   ├── auth/MsalTokenProvider.java  # MSAL + 文件令牌缓存
    │   ├── auth/FileTokenCacheAspect.java
    │   ├── client/OneDriveClient.java
    │   ├── config/AppConfig.java
    │   ├── model/DriveItemInfo.java
    │   ├── cli/ExitCodes.java
    │   └── util/GraphException.java
    └── resources/
        ├── application.properties.example
        └── simplelogger.properties
```

---

## 8. 说明与限制

- 使用官方 **MSAL4J** 获取令牌；Graph 调用走 REST（避免重量级 Graph SDK）。
- `client_credentials` 必须配置目标用户 `userId`，并授予应用级 Files 权限；租户还需 SPO/OneDrive 许可。
- 个人 OneDrive 容量满时上传会返回 `quotaLimitReached`（HTTP 507）。
- 简单上传上限约 4 MiB；更大文件自动走 `createUploadSession`。
- 本仓库不含真实密钥；请使用 env / 本地 `application.properties`。
- 同步脚本依赖本机已配置好的 `ONEDRIVE_CLIENT_ID` 等，且建议先跑一次 `auth`。
