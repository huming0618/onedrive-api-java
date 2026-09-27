# onedrive-api-java

基于 **MSAL4J** + **Microsoft Graph REST**（`java.net.http`）的精简 OneDrive Java 客户端。

支持：

- 列出文件夹内容 `listChildren(path)`
- 上传文件 `uploadFile(localPath, remotePath)`（≤4MiB 简单上传；更大文件使用 upload session）
- 下载文件 `downloadFile(remotePath, localPath)`

认证方式（可配置）：

| 模式 | `onedrive.auth.mode` | 说明 |
|------|----------------------|------|
| 设备码（推荐 CLI） | `device_code` | 浏览器在另一台设备上登录 |
| 客户端凭据（守护进程） | `client_credentials` | 需 `clientSecret` + `userId`，应用权限 |
| 交互式浏览器 | `interactive` | 本机打开浏览器登录 |

包结构：`com.example.onedrive`（`config` / `auth` / `client` / `model`）。

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
   - **目录(租户) ID** → `ONEDRIVE_TENANT_ID`（多租户/个人演示可直接用 `common`）
4. **API 权限** → **添加权限** → **Microsoft Graph** → **委托的权限**，勾选：
   - `Files.ReadWrite`（必需）
   - `User.Read`、`offline_access`（可选）
   个人账号一般点「添加」即可；公司租户若要求管理员同意，再点 **授予管理员同意**。
5. **身份验证** → **高级设置** → **允许公共客户端流** 设为 **是**（设备码登录必需）。
6. **不需要**客户端密码（`clientSecret`）。把 `ONEDRIVE_AUTH_MODE=device_code`，运行程序后按终端里的 `https://microsoft.com/devicelogin` 提示用浏览器登录即可。

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
   - `offline_access`（刷新令牌，可选）
3. 若租户要求管理员同意，点击 **为 [租户] 授予管理员同意**。
4. **身份验证** → 高级设置 → 允许公共客户端流：**是**（设备码需要）。

### 应用权限（client_credentials）

1. **API 权限** → **Microsoft Graph** → **应用程序权限**
2. 添加 `Files.ReadWrite.All`（或更细粒度的 Sites/Files 权限），并 **授予管理员同意**。
3. **证书和密码** → 新建客户端密码 → 记下密钥值 → `clientSecret`。
4. 配置 `onedrive.userId` 为要访问的用户 **UPN** 或 **对象 ID**（应用身份不能用 `/me`）。

---

## 2. 配置 clientId / tenantId

**不要**把密钥写进源码。任选其一：

### 方式 A：环境变量（推荐）

```bash
export ONEDRIVE_CLIENT_ID="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
export ONEDRIVE_TENANT_ID="common"          # 或你的租户 GUID
export ONEDRIVE_AUTH_MODE="device_code"     # device_code | client_credentials | interactive
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

---

## 3. 构建与运行

要求：**JDK 17+**、Maven 3.8+。

```bash
cd /workspace/onedrive-api-java

# 下载依赖并编译打包（含可执行 fat-jar）
mvn -q package

# 运行演示：登录 → 列出根目录 → 上传 demo-upload.txt → 下载为 demo-download.txt
mvn -q exec:java

# 或使用 shade jar
java -jar target/onedrive-api-java-1.0.0.jar
```

设备码模式下，终端会打印类似：

```text
To sign in, use a web browser to open the page https://microsoft.com/devicelogin
and enter the code XXXXXXXX to authenticate.
```

按提示在浏览器完成登录后，演示会继续执行。

---

## 4. 在代码中使用

```java
AppConfig config = AppConfig.load();
MsalTokenProvider tokens = new MsalTokenProvider(config);
try (OneDriveClient client = new OneDriveClient(config, tokens)) {
    List<DriveItemInfo> items = client.listChildren("Documents");
    client.uploadFile(Path.of("report.pdf"), "Documents/report.pdf");
    client.downloadFile("Documents/report.pdf", Path.of("/tmp/report.pdf"));
}
```

路径均相对于对应用户 OneDrive **根目录**（不要以盘符开头；前导 `/` 可选）。

---

## 5. 项目结构

```text
onedrive-api-java/
├── pom.xml
├── README.md
├── .gitignore
└── src/main/
    ├── java/com/example/onedrive/
    │   ├── Main.java                 # CLI 演示
    │   ├── auth/MsalTokenProvider.java
    │   ├── client/OneDriveClient.java
    │   ├── config/AppConfig.java
    │   └── model/DriveItemInfo.java
    └── resources/application.properties.example
```

---

## 6. 说明与限制

- 使用官方 **MSAL4J** 获取令牌；Graph 调用走 REST（避免引入重量级 Graph SDK / Kiota，便于精简依赖）。
- `client_credentials` 必须配置目标用户 `userId`，并授予应用级 Files 权限。
- 简单上传上限约 4 MiB；更大文件自动走 `createUploadSession`。
- 本仓库不含真实密钥；请使用 env / 本地 `application.properties`。
