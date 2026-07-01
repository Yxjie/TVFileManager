# TVFileManager - 电视端 SMB 文件管理器

一款专为 Android TV 设计的 SMB/NAS 文件浏览器，支持浏览远程文件、下载安装 APK。

## 功能特性

- **SMB 连接** - 支持 SMB2/SMB3 协议，兼容主流 NAS 设备
- **文件浏览** - 支持目录导航、文件排序（目录优先）
- **APK 安装** - 自动下载 APK 到本地缓存，调用系统安装器，安装后自动清理
- **遥控器适配** - 完全适配电视遥控器操作（方向键 + OK 键）
- **文件详情** - 长按查看文件大小、修改时间等信息
- **进度显示** - 下载进度实时展示

## 技术栈

- **Kotlin** + Coroutines
- **smbj** - 纯 Java SMB2/3 客户端库
- **AndroidX Leanback** - 电视端 UI 框架
- **Material Components** - UI 组件
- **FileProvider** - 安全分享文件给安装器

## 操作方式

| 按键 | 功能 |
|------|------|
| ↑↓ | 上下导航文件列表 |
| OK/Enter | 进入目录 / 下载并安装 APK |
| ← / Back | 返回上级目录 |
| MENU | 刷新当前目录 |
| 长按 OK | 查看文件详情 |

## 构建方法

```bash
# 克隆项目后执行
./gradlew assembleDebug

# APK 输出路径
app/build/outputs/apk/debug/app-debug.apk
```

## 安装到电视

```bash
adb connect <TV_IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

- `INTERNET` - 网络连接（访问 SMB 服务器）
- `REQUEST_INSTALL_PACKAGES` - 安装 APK 文件
- `WRITE_EXTERNAL_STORAGE` - Android 8 以下写缓存

## 项目结构

```
app/src/main/java/com/tvnas/filebrowser/
├── smb/
│   └── SmbConnectionManager.kt   # SMB 连接管理
├── install/
│   └── ApkInstaller.kt           # APK 安装工具
└── ui/
    ├── ConnectActivity.kt        # 连接页面
    ├── BrowserActivity.kt        # 文件浏览器
    └── FileAdapter.kt            # 文件列表适配器
```
