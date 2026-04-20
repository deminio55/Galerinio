package com.example.galerinio.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.galerinio.data.local.dao.AlbumDao
import com.example.galerinio.data.local.dao.CloudAccountDao
import com.example.galerinio.data.local.dao.MediaDao
import com.example.galerinio.data.local.dao.SyncLogDao
import com.example.galerinio.data.local.dao.TrashDao
import com.example.galerinio.data.local.entity.AlbumEntity
import com.example.galerinio.data.local.entity.CloudAccountEntity
import com.example.galerinio.data.local.entity.MediaEntity
import com.example.galerinio.data.local.entity.SyncLogEntity
import com.example.galerinio.data.local.entity.TrashEntity

@Database(
    entities = [MediaEntity::class, AlbumEntity::class, TrashEntity::class, CloudAccountEntity::class, SyncLogEntity::class],
    version = 5,
    exportSchema = false
)
abstract class GalerioDatabase : RoomDatabase() {
    
    abstract fun mediaDao(): MediaDao
    abstract fun albumDao(): AlbumDao
    abstract fun trashDao(): TrashDao
    abstract fun cloudAccountDao(): CloudAccountDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile
        private var INSTANCE: GalerioDatabase? = null
        
        fun getInstance(context: Context): GalerioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GalerioDatabase::class.java,
                    "galerio_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

