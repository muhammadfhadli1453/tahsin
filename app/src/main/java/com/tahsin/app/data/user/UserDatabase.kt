package com.tahsin.app.data.user

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Everything the reciter generates: sessions, mistakes, streaks, bookmarks and
 * memorisation strength. Kept separate from the prepackaged Quran text so that
 * user data survives a Quran-database rebuild and can be migrated normally.
 */
@Database(
    entities = [
        SessionEntity::class,
        SessionMistakeEntity::class,
        BookmarkEntity::class,
        DailyProgressEntity::class,
        MemorizationEntity::class,
        LastPositionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        private const val DB_NAME = "tahsin_user.db"

        fun create(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, DB_NAME).build()
    }
}
