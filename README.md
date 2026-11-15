# Luna TV

Luna TV 是一个面向 Android TV 与手机/平板的轻量电视桌面，基于
[HaloTV](https://github.com/Null9797/halo-tv) / [aerial-tv](https://github.com/pengchujin/aerial-tv)
衍生开发，并加入 Luna TV 品牌、手机遥控、投屏状态与 GitHub 更新检查。

- 应用 ID：`app.luna.tv`
- 最低系统：Android 5.0（API 21）
- 目标系统：Android 14（API 34）
- 支持 ABI：`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`

## 下载与安装

正式 APK 发布在 [Releases](https://github.com/gio0150/LunaTV/releases) 页面，文件名格式为：

```text
LunaTV-<version>-standard.apk
```

这是新的包名，和旧版 `app.halotv.remote.test` 不兼容。首次安装前需要卸载旧版，旧版应用数据会丢失。

## 官网

官网源码在 [`website/`](website/)，入口为 [`website/index.html`](website/index.html)。页面使用本地
`assets/` 中的图片、动态壁纸和背景音乐，不依赖外部图片服务。

推送到 `master` 后，GitHub Actions 会自动部署到 GitHub Pages。

## 从源码构建

需要 JDK 17 与 Android SDK（compileSdk 36）。

```bash
./gradlew assembleStandardRelease \
  -PlunaBrand \
  -PupdateRepo=gio0150/LunaTV \
  -PversionName=1.0.0 \
  -PversionCode=305
```

产物位于：

```text
app/build/outputs/apk/standard/release/app-standard-release.apk
```

`-PlunaBrand` 会启用：

- `applicationId = app.luna.tv`
- `versionName` 后缀 `-luna`
- `BuildConfig.LUNA_BRAND = true`
- 更新检查使用 `BuildConfig.UPDATE_REPO`
- 关闭旧 Halo 公告源

`-PupdateRepo` 必须写成真实的 `owner/repo`；更新检查会请求：

```text
https://api.github.com/repos/<owner>/<repo>/releases/latest
```

并只接受该仓库 Release 中名为 `LunaTV-<version>-standard.apk` 的资产。

## 自动发布（GitHub Actions）

`.github/workflows/release.yml` 在推送 `v*` 标签时构建并创建 Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

工作流会：

1. 解码仓库 Secret 中的签名 keystore；
2. 生成 `local/independent-signing.properties`；
3. 构建 `assembleStandardRelease -PlunaBrand`；
4. 将 APK 重命名为 `LunaTV-<version>-standard.apk`；
5. 上传到对应的 GitHub Release。

需要在仓库 Settings → Secrets and variables → Actions 中配置：

| Secret | 含义 |
| --- | --- |
| `LUNA_KEYSTORE_BASE64` | 签名 keystore 的 Base64 |
| `LUNA_STORE_PASSWORD` | keystore 密码 |
| `LUNA_KEY_ALIAS` | key alias |
| `LUNA_KEY_PASSWORD` | key 密码 |

签名文件绝不能提交到仓库。每次发布必须使用同一个 keystore，否则后续 APK 无法覆盖安装。

## 更新检查

应用内更新逻辑位于 `app/src/main/java/app/orchard/tv/update/Updates.kt`。它读取当前仓库的
`releases/latest`，校验资产名、下载地址与签名后，交给系统安装器。首次安装新包名后，
后续版本即可从 GitHub Release 检查并更新。

## 目录

- `app/`：电视桌面主模块
- `lite/`：轻量模块
- `branding/`：品牌源文件
- `gradle/`：Gradle wrapper 与版本目录
- `.github/workflows/`：自动构建与发布

## 许可与来源

代码使用 MIT License，详见 [LICENSE](LICENSE)。原始版权归 `pengchujin`；HaloTV 由
`Null_` 整理发布；Luna TV 由 `Gio(gio0515@hotmail.com)` 整理发布，不代表上游、Apple 或电视厂商背书。

壁纸、在线视频、字体、图标及第三方依赖分别遵循其各自许可；开源代码许可不等同于媒体内容授权。
