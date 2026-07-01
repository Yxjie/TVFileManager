package com.tvnas.filebrowser.smb

import java.io.Serializable

/**
 * SMB 服务器配置
 */
data class ServerConfig(
    val id: String = System.currentTimeMillis().toString(),
    val name: String = "",
    val serverAddress: String,
    val port: Int = 445,
    val shareName: String,
    val username: String,
    val password: String,
    val domain: String = ""
) : Serializable {
    
    /**
     * 获取显示名称（优先使用自定义名称，否则用服务器地址）
     */
    fun getDisplayName(): String {
        return name.ifBlank { "$serverAddress:$port" }
    }
    
    /**
     * 获取完整地址
     */
    fun getFullAddress(): String {
        return "smb://$serverAddress:$port/$shareName"
    }
}
