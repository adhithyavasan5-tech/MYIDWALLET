package com.myid.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val age: Int,
    val mobile: String?,
    val profileFile: String?
)
