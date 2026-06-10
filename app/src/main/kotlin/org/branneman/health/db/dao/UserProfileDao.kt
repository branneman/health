package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.entities.UserProfileEntity

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile")
    suspend fun get(): UserProfileEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM user_profile LIMIT 1)")
    fun existsFlow(): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: UserProfileEntity)

    @Query("DELETE FROM user_profile WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}
