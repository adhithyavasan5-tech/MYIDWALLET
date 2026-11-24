package com.myid.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val frontFile: String,
    val backFile: String?,
    val isFavourite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val documentNumber: String? = null,
    val holderName: String? = null,
    val dateOfBirth: String? = null,
    val expiryDate: String? = null
)
