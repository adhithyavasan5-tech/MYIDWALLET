package com.myid.wallet.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY isFavourite DESC, createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: DocumentEntity)

    @Query("UPDATE documents SET isFavourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: String, favourite: Boolean)

    @Query("UPDATE documents SET documentNumber = :number, holderName = :name, dateOfBirth = :dob, expiryDate = :expiry WHERE id = :id")
    suspend fun updateMetadata(id: String, number: String?, name: String?, dob: String?, expiry: String?)

    @Delete
    suspend fun delete(document: DocumentEntity)
}
