package com.tvnas.filebrowser.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tvnas.filebrowser.smb.ServerConfig

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val serverAddress: String,
    val port: Int,
    val shareName: String,
    val username: String,
    val encryptedPassword: String?,
    val domain: String,
    val lastAccessed: Long = System.currentTimeMillis()
) {
    fun toDomainModel(password: String): ServerConfig {
        return ServerConfig(
            id = id,
            name = name,
            serverAddress = serverAddress,
            port = port,
            shareName = shareName,
            username = username,
            password = password,
            domain = domain
        )
    }

    companion object {
        fun fromDomainModel(config: ServerConfig, encryptedPassword: String?): ServerEntity {
            return ServerEntity(
                id = config.id,
                name = config.name,
                serverAddress = config.serverAddress,
                port = config.port,
                shareName = config.shareName,
                username = config.username,
                encryptedPassword = encryptedPassword,
                domain = config.domain
            )
        }
    }
}
