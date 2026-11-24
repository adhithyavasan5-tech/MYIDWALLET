package com.myid.wallet.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE id = 1")
    fun observe(): Flow<AccountEntity?>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account WHERE id = 1")
    suspend fun get(): AccountEntity?

    @Query("DELETE FROM account WHERE id = 1")
    suspend fun delete()
}
