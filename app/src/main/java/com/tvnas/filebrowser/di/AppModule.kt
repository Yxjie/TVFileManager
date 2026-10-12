package com.tvnas.filebrowser.di

import android.content.Context
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.ui.ThumbnailLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSmbConnectionManager(): SmbConnectionManager {
        return SmbConnectionManager.shared
    }

    @Provides
    @Singleton
    fun provideThumbnailLoader(
        @ApplicationContext context: Context,
        smbManager: SmbConnectionManager
    ): ThumbnailLoader {
        return ThumbnailLoader(context, smbManager)
    }
}
