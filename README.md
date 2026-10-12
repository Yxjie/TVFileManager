# TVFileManager - 电视端 SMB 文件管理器

一款专为 Android TV 设计的 SMB/NAS 文件浏览器，支持浏览远程文件、图片查看、视频播放、下载安装 APK。

## 功能特性

- **SMB 连接** - 支持 SMB2/SMB3 协议，兼容主流 NAS 设备
- **文件浏览** - 支持目录导航、文件排序（目录优先）、客户端分页加载（每页 60 条）
- **图片查看** - 全屏大图、左右键切换、自动轮播（5 秒间隔）
- **视频播放** - ExoPlayer 流式播放、断点续播、定时保存进度、自动连播
- **APK 安装** - 自动下载 APK 到本地缓存，调用系统安装器，安装后自动清理
- **服务器管理** - Room 数据库持久化，支持从旧版 SharedPreferences 自动迁移
- **遥控器适配** - 完全适配电视遥控器操作（方向键 + OK 键 + MENU 键）

## 技术栈

- **Kotlin** + Coroutines + Flow
- **Hilt** - 依赖注入
- **Room** - 本地数据库（服务器配置存储）
- **MVVM** - 架构模式（ViewModel + StateFlow/SharedFlow）
- **smbj** - 纯 Java SMB2/3 客户端库
- **ExoPlayer (Media3)** - 视频播放
- **Material Components** - UI 组件
- **FileProvider** - 安全分享文件给安装器

## 操作方式

| 按键 | 功能 |
|------|------|
| ↑↓ | 上下导航文件列表 |
| OK/Enter | 进入目录 / 下载并安装 APK / 播放视频暂停恢复 |
| ← / Back | 返回上级目录 / 视频 seek |
| → | 视频 seek |
| MENU | 刷新当前目录 / 图片轮播开关 |
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
├── data/                          # 数据层
│   ├── AppDatabase.kt            # Room 数据库定义
│   ├── ServerDao.kt              # 服务器数据访问对象
│   ├── ServerEntity.kt           # 服务器实体（含密码加密）
│   └── ServerRepository.kt       # 数据仓库（含 SharedPreferences 迁移）
├── di/                            # Hilt 依赖注入模块
│   ├── AppModule.kt              # 应用级依赖（SmbConnectionManager、ThumbnailLoader）
│   └── DatabaseModule.kt         # 数据库依赖
├── smb/                           # SMB 协议相关
│   ├── SmbConnectionManager.kt   # SMB 连接管理（单例共享）
│   ├── SmbFile.kt                # SMB 文件模型
│   └── ServerConfig.kt           # 服务器配置数据类
├── media/                         # 媒体播放相关
│   ├── SmbDataSourceFactory.kt   # ExoPlayer SMB 数据源
│   ├── MimeTypeUtil.kt           # MIME 类型工具
│   └── PlayPositionStore.kt      # 播放位置持久化
├── install/
│   └── ApkInstaller.kt           # APK 安装工具
├── util/                          # 工具类
│   ├── BitmapSampler.kt          # 图片采样率计算
│   ├── CacheManager.kt           # 缓存管理
│   ├── FullscreenHelper.kt       # 全屏辅助
│   └── PasswordEncryptor.kt      # 密码加密
└── ui/                            # 表现层
    ├── ConnectActivity.kt        # 添加/编辑服务器页面
    ├── BrowserActivity.kt        # 文件浏览器（含分页加载）
    ├── ImageViewerActivity.kt    # 图片查看器（轮播）
    ├── VideoPlayerActivity.kt    # 视频播放器（断点续播）
    ├── ServerListActivity.kt     # 服务器列表页面
    ├── FileAdapter.kt            # 文件列表适配器
    ├── ServerListAdapter.kt      # 服务器列表适配器
    ├── ThumbnailLoader.kt        # 缩略图加载器
    └── viewmodel/                 # ViewModel 层
        ├── ConnectViewModel.kt
        ├── BrowserViewModel.kt   # 含分页逻辑（PAGE_SIZE = 60）
        ├── ImageViewerViewModel.kt
        ├── VideoPlayerViewModel.kt
        └── ServerListViewModel.kt
```

## 数据存储

- **服务器配置** - Room 数据库，密码 AES 加密存储
- **播放进度** - SharedPreferences，定时保存（每 10 秒）
- **文件缓存** - 外部缓存目录，APK 安装后 30 秒自动清理

## 版本记录

### v1.0.1
- 架构重构：从 Activity 直接管理迁移至 MVVM + Hilt + Room
- 新增客户端分页加载（每页 60 条，提前 8 条预加载）
- 服务器配置迁移至 Room 数据库（支持从旧版 SharedPreferences 自动迁移）
- 修复多项已知问题
