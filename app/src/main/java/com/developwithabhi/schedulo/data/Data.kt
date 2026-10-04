package com.developwithabhi.schedulo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object Status { const val PENDING = "PENDING"; const val SENT = "SENT"; const val FAILED = "FAILED" }
object Repeat { const val NONE = "NONE"; const val DAILY = "DAILY"; const val WEEKLY = "WEEKLY"; const val MONTHLY = "MONTHLY" }

@Entity(tableName = "messages")
data class ScheduledMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,          // digits only, with country code (e.g. 919876543210)
    val text: String,
    val app: String,            // com.whatsapp or com.whatsapp.w4b
    val timeMillis: Long,
    val repeat: String = Repeat.NONE,
    val askBeforeSend: Boolean = false,
    val status: String = Status.PENDING,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY status = 'PENDING' DESC, timeMillis ASC")
    fun observeAll(): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): ScheduledMessage?

    @Query("SELECT * FROM messages WHERE status = 'PENDING'")
    suspend fun pending(): List<ScheduledMessage>

    @Insert suspend fun insert(m: ScheduledMessage): Long
    @Update suspend fun update(m: ScheduledMessage)
    @Delete suspend fun delete(m: ScheduledMessage)
}

@Database(entities = [ScheduledMessage::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MessageDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "schedulo.db")
                .build().also { instance = it }
        }
    }
}
