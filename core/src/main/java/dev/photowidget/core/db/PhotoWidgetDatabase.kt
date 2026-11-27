package dev.photowidget.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WidgetEntity::class,
        SourceEntity::class,
        PhotoStateEntity::class,
        HistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PhotoWidgetDatabase : RoomDatabase() {

    abstract fun widgetDao(): WidgetDao
    abstract fun sourceDao(): SourceDao
    abstract fun photoStateDao(): PhotoStateDao
    abstract fun historyDao(): HistoryDao

    companion object {
        private const val NAME = "photo_widget.db"

        @Volatile
        private var instance: PhotoWidgetDatabase? = null

        fun get(context: Context): PhotoWidgetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoWidgetDatabase::class.java,
                    NAME,
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
    }
}
