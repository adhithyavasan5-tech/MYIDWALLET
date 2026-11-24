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
    private val crypto: EncryptionManager
) {
    val documents = dao.observeAll()

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
}
