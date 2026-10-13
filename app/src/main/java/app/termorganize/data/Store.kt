// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.termorganize.core.model.AppJson
import app.termorganize.logic.degree.UserData
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.widget.WidgetScheduleData
import java.io.File

@Entity(tableName = "user_document")
data class UserRow(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface UserDao {
    @Query("SELECT * FROM user_document WHERE id = 1")
    suspend fun get(): UserRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UserRow)
}

@Database(entities = [UserRow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        fun create(context: Context): AppDatabase {
            val name = System.getProperty("termorganize.dbname") ?: "term-organize.db"
            return Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        }
    }
}

object WidgetFiles {
    private const val NAME = "widget-schedule.json"

    fun save(context: Context, data: WidgetScheduleData?) {
        val file = File(context.filesDir, NAME)
        if (data == null) {
            file.delete()
            return
        }
        file.writeText(AppJson.user.encodeToString(WidgetScheduleData.serializer(), data))
    }

    fun load(context: Context): WidgetScheduleData? = runCatching {
        val file = File(context.filesDir, NAME)
        if (!file.exists()) null else AppJson.user.decodeFromString(WidgetScheduleData.serializer(), file.readText())
    }.getOrNull()
}

object UserBackup {
    fun encode(data: UserData) = UserJson.encode(data)
    fun decode(text: String) = UserJson.decode(text)
}
