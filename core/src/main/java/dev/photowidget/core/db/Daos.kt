package dev.photowidget.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetMode
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetDao {

    @Transaction
    @Query("SELECT * FROM widgets ORDER BY createdAt ASC, widgetId ASC")
    fun observeAll(): Flow<List<WidgetWithSources>>

    @Transaction
    @Query("SELECT * FROM widgets WHERE widgetId = :widgetId")
    fun observe(widgetId: Int): Flow<WidgetWithSources?>

    @Transaction
    @Query("SELECT * FROM widgets WHERE widgetId = :widgetId")
    suspend fun get(widgetId: Int): WidgetWithSources?

    @Query("SELECT * FROM widgets WHERE widgetId = :widgetId")
    suspend fun getEntity(widgetId: Int): WidgetEntity?

    @Transaction
    @Query("SELECT * FROM widgets ORDER BY createdAt ASC, widgetId ASC")
    suspend fun getAll(): List<WidgetWithSources>

    @Query("SELECT COUNT(*) FROM widgets")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM widgets WHERE widgetId = :widgetId")
    suspend fun exists(widgetId: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(widget: WidgetEntity)

    @Query(
        "UPDATE widgets SET name = :name, mode = :mode, intervalSeconds = :intervalSeconds, " +
            "appearance = :appearance, tapAction = :tapAction, transitions = :transitions, " +
            "cooldownPercent = :cooldownPercent, collageCells = :collageCells, " +
            "singlePhotoUri = :singlePhotoUri, updatedAt = :updatedAt WHERE widgetId = :widgetId",
    )
    suspend fun updateEditable(
        widgetId: Int,
        name: String,
        mode: WidgetMode,
        intervalSeconds: Long,
        appearance: WidgetAppearance,
        tapAction: TapAction,
        transitions: Boolean,
        cooldownPercent: Int,
        collageCells: Int,
        singlePhotoUri: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE widgets SET sequence = :sequence, cycleId = :cycleId, lastPhotoKey = :lastPhotoKey, " +
            "lastDailyEpochDay = :lastDailyEpochDay, nextUpdateAt = :nextUpdateAt, " +
            "updatedAt = :updatedAt WHERE widgetId = :widgetId",
    )
    suspend fun updateRotationState(
        widgetId: Int,
        sequence: Long,
        cycleId: Long,
        lastPhotoKey: String?,
        lastDailyEpochDay: Long,
        nextUpdateAt: Long,
        updatedAt: Long,
    )

    @Query("UPDATE widgets SET nextUpdateAt = :nextUpdateAt WHERE widgetId = :widgetId")
    suspend fun setNextUpdateAt(widgetId: Int, nextUpdateAt: Long)

    @Query("UPDATE widgets SET singlePhotoUri = :uri WHERE widgetId = :widgetId")
    suspend fun setSinglePhoto(widgetId: Int, uri: String?)

    @Query("UPDATE widgets SET cycleId = 1, sequence = 0, lastPhotoKey = NULL, lastDailyEpochDay = 0 WHERE widgetId = :widgetId")
    suspend fun resetRotation(widgetId: Int)

    @Query("UPDATE widgets SET cycleId = 1, sequence = 0, lastPhotoKey = NULL, lastDailyEpochDay = 0")
    suspend fun resetAllRotation()

    @Query("DELETE FROM widgets WHERE widgetId = :widgetId")
    suspend fun delete(widgetId: Int)

    @Query("SELECT * FROM widgets WHERE nextUpdateAt > 0 AND nextUpdateAt <= :now AND mode != 'SINGLE'")
    suspend fun dueBefore(now: Long): List<WidgetEntity>
}

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources WHERE widgetId = :widgetId ORDER BY addedAt ASC, id ASC")
    suspend fun getForWidget(widgetId: Int): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE widgetId = :widgetId ORDER BY addedAt ASC, id ASC")
    fun observeForWidget(widgetId: Int): Flow<List<SourceEntity>>

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Insert
    suspend fun insertAll(sources: List<SourceEntity>)

    @Query("DELETE FROM sources WHERE widgetId = :widgetId")
    suspend fun deleteForWidget(widgetId: Int)

    @Query("DELETE FROM sources WHERE id = :id AND widgetId = :widgetId")
    suspend fun deleteById(widgetId: Int, id: Long)

    @Query("SELECT COUNT(*) FROM sources WHERE widgetId = :widgetId")
    suspend fun count(widgetId: Int): Int
}

@Dao
interface PhotoStateDao {

    @Query("SELECT * FROM photo_state WHERE widgetId = :widgetId")
    suspend fun getForWidget(widgetId: Int): List<PhotoStateEntity>

    @Query("SELECT * FROM photo_state WHERE widgetId = :widgetId")
    fun observeForWidget(widgetId: Int): Flow<List<PhotoStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<PhotoStateEntity>)

    @Query("UPDATE photo_state SET shownInCycle = 0 WHERE widgetId = :widgetId")
    suspend fun clearCycle(widgetId: Int)

    @Query("DELETE FROM photo_state WHERE widgetId = :widgetId")
    suspend fun clear(widgetId: Int)

    @Query("DELETE FROM photo_state WHERE widgetId = :widgetId AND photoKey NOT IN (:keys)")
    suspend fun prune(widgetId: Int, keys: List<String>)

    @Query("DELETE FROM photo_state")
    suspend fun clearAll()
}

@Dao
interface HistoryDao {

    @Insert
    suspend fun insertAll(items: List<HistoryEntity>)

    @Query("SELECT * FROM history WHERE widgetId = :widgetId ORDER BY shownAt DESC, id DESC LIMIT :limit")
    fun observeRecent(widgetId: Int, limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE widgetId = :widgetId ORDER BY shownAt DESC, id DESC LIMIT :limit")
    suspend fun recent(widgetId: Int, limit: Int): List<HistoryEntity>

    // `photoKey != NULL` is NULL (never true) in SQL, so the null check has to be explicit;
    // otherwise "previous" finds nothing until a photo has been recorded once.
    @Query(
        "SELECT * FROM history WHERE widgetId = :widgetId AND (:currentKey IS NULL OR photoKey != :currentKey) " +
            "ORDER BY shownAt DESC, id DESC LIMIT 1",
    )
    suspend fun previousDistinct(widgetId: Int, currentKey: String?): HistoryEntity?

    @Query("SELECT MAX(shownAt) FROM history WHERE widgetId = :widgetId AND shownAt < :before")
    suspend fun previousGroupTime(widgetId: Int, before: Long): Long?

    @Query("SELECT * FROM history WHERE widgetId = :widgetId AND shownAt = :shownAt ORDER BY id ASC")
    suspend fun groupAt(widgetId: Int, shownAt: Long): List<HistoryEntity>

    @Query("DELETE FROM history WHERE widgetId = :widgetId")
    suspend fun clear(widgetId: Int)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query(
        "DELETE FROM history WHERE widgetId = :widgetId AND id NOT IN " +
            "(SELECT id FROM history WHERE widgetId = :widgetId ORDER BY shownAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(widgetId: Int, keep: Int)

    @Query("SELECT COUNT(*) FROM history WHERE widgetId = :widgetId")
    suspend fun count(widgetId: Int): Int
}
