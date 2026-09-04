package yokai.domain.activity

import kotlinx.coroutines.flow.Flow
import yokai.domain.activity.model.ReadingActivity

interface ReadingActivityRepository {
    fun subscribeAll(): Flow<List<ReadingActivity>>

    suspend fun getAll(): List<ReadingActivity>

    suspend fun getSince(startDate: Long): List<ReadingActivity>

    suspend fun insert(activity: ReadingActivity)

    suspend fun deleteAll()
}
