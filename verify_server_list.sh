#!/bin/bash

echo "=== 服务器列表管理功能验证 ==="
echo ""

# 1. 检查数据模型
echo "1. 检查 ServerConfig 数据模型..."
if [ -f "app/src/main/java/com/tvnas/filebrowser/smb/ServerConfig.kt" ]; then
    echo "   ✓ ServerConfig.kt 存在"
    if grep -q "data class ServerConfig" app/src/main/java/com/tvnas/filebrowser/smb/ServerConfig.kt; then
        echo "   ✓ 包含 data class 定义"
    fi
else
    echo "   ✗ ServerConfig.kt 缺失"
fi
echo ""

# 2. 检查存储管理器
echo "2. 检查 ServerStorage 存储管理器..."
if [ -f "app/src/main/java/com/tvnas/filebrowser/smb/ServerStorage.kt" ]; then
    echo "   ✓ ServerStorage.kt 存在"
    if grep -q "fun saveServer" app/src/main/java/com/tvnas/filebrowser/smb/ServerStorage.kt && \
       grep -q "fun deleteServer" app/src/main/java/com/tvnas/filebrowser/smb/ServerStorage.kt && \
       grep -q "fun loadServers" app/src/main/java/com/tvnas/filebrowser/smb/ServerStorage.kt; then
        echo "   ✓ 包含 save/delete/load 方法"
    fi
else
    echo "   ✗ ServerStorage.kt 缺失"
fi
echo ""

# 3. 检查服务器列表页面
echo "3. 检查 ServerListActivity..."
if [ -f "app/src/main/java/com/tvnas/filebrowser/ui/ServerListActivity.kt" ]; then
    echo "   ✓ ServerListActivity.kt 存在"
    if grep -q "class ServerListActivity" app/src/main/java/com/tvnas/filebrowser/ui/ServerListActivity.kt; then
        echo "   ✓ Activity 类定义正确"
    fi
else
    echo "   ✗ ServerListActivity.kt 缺失"
fi
echo ""

# 4. 检查适配器
echo "4. 检查 ServerListAdapter..."
if [ -f "app/src/main/java/com/tvnas/filebrowser/ui/ServerListAdapter.kt" ]; then
    echo "   ✓ ServerListAdapter.kt 存在"
    if grep -q "class ServerListAdapter" app/src/main/java/com/tvnas/filebrowser/ui/ServerListAdapter.kt; then
        echo "   ✓ Adapter 类定义正确"
    fi
else
    echo "   ✗ ServerListAdapter.kt 缺失"
fi
echo ""

# 5. 检查布局文件
echo "5. 检查布局文件..."
if [ -f "app/src/main/res/layout/activity_server_list.xml" ]; then
    echo "   ✓ activity_server_list.xml 存在"
else
    echo "   ✗ activity_server_list.xml 缺失"
fi

if [ -f "app/src/main/res/layout/item_server.xml" ]; then
    echo "   ✓ item_server.xml 存在"
else
    echo "   ✗ item_server.xml 缺失"
fi
echo ""

# 6. 检查 ConnectActivity 更新
echo "6. 检查 ConnectActivity 是否支持编辑模式..."
if grep -q "editingServerId" app/src/main/java/com/tvnas/filebrowser/ui/ConnectActivity.kt; then
    echo "   ✓ 支持编辑模式"
fi
if grep -q "btnDelete" app/src/main/java/com/tvnas/filebrowser/ui/ConnectActivity.kt; then
    echo "   ✓ 包含删除按钮逻辑"
fi
echo ""

# 7. 检查布局文件更新
echo "7. 检查 activity_connect.xml 更新..."
if grep -q "btnDelete" app/src/main/res/layout/activity_connect.xml; then
    echo "   ✓ 包含删除按钮"
fi
echo ""

# 8. 检查 AndroidManifest.xml
echo "8. 检查 AndroidManifest.xml..."
if grep -q "ServerListActivity" app/src/main/AndroidManifest.xml; then
    echo "   ✓ ServerListActivity 已注册"
fi
if grep -q "android.intent.action.MAIN" app/src/main/AndroidManifest.xml | head -1 | grep -q "ServerListActivity"; then
    echo "   ✓ ServerListActivity 设置为启动页"
fi
echo ""

# 9. 检查 build.gradle.kts
echo "9. 检查 build.gradle.kts..."
if grep -q "gson" app/build.gradle.kts; then
    echo "   ✓ Gson 依赖已添加"
fi
echo ""

# 10. 检查 BrowserActivity 是否需要更新
echo "10. 检查 BrowserActivity..."
if grep -q "serverId" app/src/main/java/com/tvnas/filebrowser/ui/BrowserActivity.kt; then
    echo "   ✓ 支持通过 serverId 加载配置"
else
    echo "   ⚠ BrowserActivity 可能需要支持 serverId 参数"
fi
echo ""

echo "=== 验证完成 ==="
