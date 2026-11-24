package com.myid.wallet.data

import android.content.Context
import android.net.Uri
import com.myid.wallet.security.EncryptionManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class WalletRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val accountDao: AccountDao,
    private val crypto: EncryptionManager
) {
    val documents = dao.observeAll()
    val account = accountDao.observe()

    suspend fun saveDocument(
        existing: DocumentEntity?,
        title: String,
        category: String,
        frontBytes: ByteArray,
        backBytes: ByteArray?,
        removeBack: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val id = existing?.id ?: UUID.randomUUID().toString()
        val directory = File(context.filesDir, "vault").apply { mkdirs() }

        fun writeSide(side: String, bytes: ByteArray): String {
            val fileName = "${id}_${side}_${System.currentTimeMillis()}.enc"
            File(directory, fileName).writeBytes(crypto.encrypt(bytes))
            return fileName
        }

        val frontName = writeSide("front", frontBytes)
        val backName = when {
            backBytes != null -> writeSide("back", backBytes)
            removeBack -> null
            else -> existing?.backFile
        }

        existing?.let {
            if (it.frontFile.isNotBlank()) File(directory, it.frontFile).delete()
        }
        backBytes?.let {
            existing?.backFile?.let { old -> File(directory, old).delete() }
        }
        if (removeBack) existing?.backFile?.let { File(directory, it).delete() }

        val entity = (existing ?: DocumentEntity(
            id = id,
            title = title.ifBlank { category },
            category = category,
            frontFile = frontName,
            backFile = backName
        )).copy(
            title = title.ifBlank { category },
            category = category,
            frontFile = frontName,
            backFile = backName
        )
        dao.upsert(entity)
        entity
    }

    suspend fun importDocument(
        uri: Uri,
        title: String,
        category: String,
        isBack: Boolean,
        existing: DocumentEntity? = null
    ) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read the selected document.")
        require(bytes.isNotEmpty()) { "Selected file is empty." }
        require(bytes.size <= 20 * 1024 * 1024) { "Please choose an image smaller than 20 MB." }

        val id = existing?.id ?: UUID.randomUUID().toString()
        val directory = File(context.filesDir, "vault").apply { mkdirs() }
        val sideName = if (isBack) "back" else "front"
        val fileName = "${id}_$sideName.enc"
        File(directory, fileName).writeBytes(crypto.encrypt(bytes))

        val current = existing
        val entity = if (current == null) {
            DocumentEntity(
                id = id,
                title = title.ifBlank { category },
                category = category,
                frontFile = if (isBack) "" else fileName,
                backFile = if (isBack) fileName else null
            )
        } else {
            current.copy(
                title = title.ifBlank { current.title },
                backFile = if (isBack) fileName else current.backFile,
                frontFile = if (isBack) current.frontFile else fileName
            )
        }
        dao.upsert(entity)
        entity
    }

    suspend fun readEncryptedImage(fileName: String): ByteArray =
        withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "vault"), fileName)
            require(file.exists()) { "Document image is missing." }
            crypto.decrypt(file.readBytes())
        }

    suspend fun favourite(document: DocumentEntity) {
        dao.setFavourite(document.id, !document.isFavourite)
    }

    suspend fun delete(document: DocumentEntity) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "vault")
        listOfNotNull(document.frontFile.takeIf { it.isNotBlank() }, document.backFile)
            .forEach { File(directory, it).delete() }
        dao.delete(document)
    }

    suspend fun removeBack(document: DocumentEntity) = withContext(Dispatchers.IO) {
        document.backFile?.let { File(File(context.filesDir, "vault"), it).delete() }
        dao.upsert(document.copy(backFile = null))
    }

    suspend fun updateMetadata(document: DocumentEntity, number: String?, name: String?, dob: String?, expiry: String?) =
        withContext(Dispatchers.IO) {
            dao.updateMetadata(document.id, number, name, dob, expiry)
        }

    suspend fun saveAccount(existing: AccountEntity?, name: String, age: Int, mobile: String?, profileBytes: ByteArray?, removeProfile: Boolean = false) =
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "vault").apply { mkdirs() }
            val profileName = profileBytes?.let {
                val fileName = "profile_${System.currentTimeMillis()}.enc"
                File(directory, fileName).writeBytes(crypto.encrypt(it))
                fileName
            } ?: if (removeProfile) null else existing?.profileFile
            if (removeProfile) existing?.profileFile?.let { File(directory, it).delete() }
            profileBytes?.let {
                existing?.profileFile?.let { old -> File(directory, old).delete() }
            }
            accountDao.upsert(AccountEntity(name = name, age = age, mobile = mobile?.ifBlank { null }, profileFile = profileName))
        }

    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        accountDao.get()?.profileFile?.let {
            File(File(context.filesDir, "vault"), it).delete()
        }
        accountDao.delete()
    }

}
