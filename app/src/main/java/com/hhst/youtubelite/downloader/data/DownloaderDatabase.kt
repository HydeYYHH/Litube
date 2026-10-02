package com.hhst.youtubelite.downloader.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BatchEntity::class,
        SubmissionEntity::class,
        TaskEntity::class,
        ItemEntity::class,
        AssetEntity::class,
        ComponentEntity::class,
        ChunkEntity::class,
        PublishEntity::class,
        ScheduleEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class DownloaderDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadsDao
}
