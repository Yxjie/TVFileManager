package com.tvnas.filebrowser.smb

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.bugly.crashreport.BuglyLog
import com.tvnas.filebrowser.util.PasswordEncryptor

/**
 * 服务器配置存储管理器
 * 密码使用 Android Keystore AES/GCM 加密后存储
 */
class ServerStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 保存服务器配置（密码自动加密）
     */
    fun saveServer(server: ServerConfig) {
        val servers = getServers().toMutableList()
        val existingIndex = servers.indexOfFirst { it.id == server.id }

        if (existingIndex >= 0) {
            servers[existingIndex] = server
        } else {
            servers.add(server)
        }

        saveServerList(servers)
    }

    /**
     * 删除服务器配置
     */
    fun deleteServer(serverId: String) {
        val servers = getServers().toMutableList()
        servers.removeAll { it.id == serverId }
        saveServerList(servers)
    }

    /**
     * 获取所有服务器配置（密码自动解密）
     * 兼容旧版明文格式：自动迁移为加密格式
     */
    fun getServers(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()

        // 优先尝试新格式（加密密码）
        // 用 getParameterized 而非匿名 object : TypeToken<...>(){}：后者依赖
        // 类文件的泛型 Signature 属性，release 下可能被 R8 剥离，导致运行期
        // 类型退化成 raw List、解析静默失败（表现为「服务器配置不存在」）
        try {
            val type = TypeToken.getParameterized(List::class.java, StoredServerConfig::class.java).type
            val stored: List<StoredServerConfig> = gson.fromJson(json, type)
            if (stored.isNotEmpty() && stored[0].encryptedPassword != null) {
                return stored.map { it.toServerConfig() }
            }
        } catch (e: Exception) {
            // 新格式解析失败，继续尝试旧格式；上报避免静默失效难以定位
            BuglyLog.e("ServerStorage", "新格式解析失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Fallback：尝试旧格式（明文密码，字段名为 password）
        return try {
            val type = TypeToken.getParameterized(List::class.java, ServerConfig::class.java).type
            val oldServers: List<ServerConfig> = gson.fromJson(json, type)
            if (oldServers.isNotEmpty()) {
                // 自动迁移为加密格式
                saveServerList(oldServers)
            }
            oldServers
        } catch (e: Exception) {
            BuglyLog.e("ServerStorage", "旧格式解析失败: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * 根据 ID 获取服务器配置
     */
    fun getServerById(serverId: String): ServerConfig? {
        return getServers().find { it.id == serverId }
    }

    /**
     * 清空所有配置
     */
    fun clearAll() {
        prefs.edit().remove(KEY_SERVERS).apply()
    }

    private fun saveServerList(servers: List<ServerConfig>) {
        val stored = servers.map { StoredServerConfig.fromServerConfig(it) }
        val json = gson.toJson(stored)
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }

    /**
     * 内部存储模型：密码字段为加密后的 Base64 字符串
     */
    private data class StoredServerConfig(
        val id: String,
        val name: String,
        val serverAddress: String,
        val port: Int,
        val shareName: String,
        val username: String,
        val encryptedPassword: String?,
        val domain: String
    ) {
        fun toServerConfig(): ServerConfig {
            return ServerConfig(
                id = id,
                name = name,
                serverAddress = serverAddress,
                port = port,
                shareName = shareName,
                username = username,
                password = PasswordEncryptor.decrypt(encryptedPassword) ?: "",
                domain = domain
            )
        }

        companion object {
            fun fromServerConfig(config: ServerConfig): StoredServerConfig {
                return StoredServerConfig(
                    id = config.id,
                    name = config.name,
                    serverAddress = config.serverAddress,
                    port = config.port,
                    shareName = config.shareName,
                    username = config.username,
                    encryptedPassword = PasswordEncryptor.encrypt(config.password),
                    domain = config.domain
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "tv_file_manager_prefs"
        private const val KEY_SERVERS = "saved_servers"
    }
}
