package com.tvnas.filebrowser.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ServerEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        const val DATABASE_NAME = "tv_file_manager_db"
    }
}
