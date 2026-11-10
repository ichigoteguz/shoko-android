package dev.shoko.remote.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workspace")
data class WorkspaceEntity(@PrimaryKey val id: Int = 1, val payload: String)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val payload: String,
    val isDefault: Boolean = false,
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspace WHERE id = 1") suspend fun load(): WorkspaceEntity?
    @Upsert suspend fun save(workspace: WorkspaceEntity)
    @Query("SELECT * FROM templates ORDER BY name COLLATE NOCASE") fun templates(): Flow<List<TemplateEntity>>
    @Upsert suspend fun saveTemplate(template: TemplateEntity)
    @Query("DELETE FROM templates WHERE id = :id") suspend fun deleteTemplate(id: String)
    @Query("UPDATE templates SET isDefault = 0") suspend fun clearDefault()
    @Query("UPDATE templates SET isDefault = 1 WHERE id = :id") suspend fun markDefault(id: String)
    @Transaction suspend fun setDefault(id: String) { clearDefault(); markDefault(id) }
    @Query("DELETE FROM templates") suspend fun resetTemplates()
}

@Database(entities = [WorkspaceEntity::class, TemplateEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workspace(): WorkspaceDao
    companion object {
        fun open(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "shoko-remote.db").build()
    }
}
