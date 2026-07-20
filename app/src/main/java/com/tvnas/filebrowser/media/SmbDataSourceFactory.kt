package com.tvnas.filebrowser.media

import androidx.media3.datasource.DataSource

/**
 * SMB DataSource 工厂 — ExoPlayer 通过工厂模式创建 DataSource 实例
 */
class SmbDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource()
}
