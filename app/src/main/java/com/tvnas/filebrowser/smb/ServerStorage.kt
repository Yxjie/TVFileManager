package com.tvnas.filebrowser.smb

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 服务器配置存储管理器
 */
class ServerStorage(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    /**
     * 保存服务器配置
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
     * 获取所有服务器配置
     */
    fun getServers(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
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
        val json = gson.toJson(servers)
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }
    
    companion object {
        private const val PREFS_NAME = "tv_file_manager_prefs"
        private const val KEY_SERVERS = "saved_servers"
    }
}
