package com.tvnas.filebrowser.data

import android.content.Context
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.util.PasswordEncryptor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.bugly.crashreport.BuglyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    @ApplicationContext private val context: Context
) {
    private var migrationDone = false

    /**
     * 获取所有服务器配置（Flow 自动更新）
     */
    fun getServers(): Flow<List<ServerConfig>> {
        return serverDao.getAllServers().map { entities ->
            entities.map { it.toDomainModel(PasswordEncryptor.decrypt(it.encryptedPassword) ?: "") }
        }
    }

    /**
     * 在首次访问数据库前调用，完成从 SharedPreferences 的一次性迁移
     */
    suspend fun migrateIfNeeded() {
        if (migrationDone) return
        migrationDone = true
        val count = serverDao.getCount()
        if (count > 0) return // 数据库已有数据，无需迁移
        migrateFromOldStorage()
    }

    private suspend fun migrateFromOldStorage() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("tv_file_manager_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("saved_servers", null) ?: return@withContext
        val gson = Gson()

        val oldServers = try {
            val type = TypeToken.getParameterized(List::class.java, StoredServerConfig::class.java).type
            val stored: List<StoredServerConfig> = gson.fromJson(json, type)
            if (stored.isNotEmpty() && stored.any { it.encryptedPassword != null }) {
                stored.map { it.toServerConfig() }
            } else {
                val oldType = TypeToken.getParameterized(List::class.java, ServerConfig::class.java).type
                gson.fromJson<List<ServerConfig>>(json, oldType)
            }
        } catch (e: Exception) {
            emptyList<ServerConfig>()
        }

        if (oldServers.isNotEmpty()) {
            oldServers.forEach { config ->
                val entity = ServerEntity.fromDomainModel(config, PasswordEncryptor.encrypt(config.password))
                serverDao.insertServer(entity)
            }
            prefs.edit().remove("saved_servers").apply()
        }
    }

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
    }

    suspend fun getServerById(id: String): ServerConfig? = withContext(Dispatchers.IO) {
        migrateIfNeeded()
        serverDao.getServerById(id)?.let {
            it.toDomainModel(PasswordEncryptor.decrypt(it.encryptedPassword) ?: "")
        }
    }

    suspend fun saveServer(server: ServerConfig) = withContext(Dispatchers.IO) {
        migrateIfNeeded()
        val entity = ServerEntity.fromDomainModel(server, PasswordEncryptor.encrypt(server.password))
        serverDao.insertServer(entity)
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        serverDao.deleteServer(id)
    }
}
