package com.myid.wallet

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myid.wallet.data.AccountEntity
import com.myid.wallet.data.AppDatabase
import com.myid.wallet.data.DocumentEntity
import com.myid.wallet.data.WalletRepository
import com.myid.wallet.security.EncryptionManager
import com.myid.wallet.security.PinManager
import com.myid.wallet.ui.theme.MyIDTheme
import com.myid.wallet.util.CardImageProcessor
import com.myid.wallet.util.CropCorners
import com.myid.wallet.util.OcrExtractor
import com.myid.wallet.util.PdfExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.hypot

private val CropCornersSaver = listSaver<CropCorners, Float>(
    save = {
        listOf(
            it.topLeft.x, it.topLeft.y, it.topRight.x, it.topRight.y,
            it.bottomRight.x, it.bottomRight.y, it.bottomLeft.x, it.bottomLeft.y
        )
    },
    restore = {
        CropCorners(
            android.graphics.PointF(it[0], it[1]),
            android.graphics.PointF(it[2], it[3]),
            android.graphics.PointF(it[4], it[5]),
            android.graphics.PointF(it[6], it[7])
        )
    }
)

private fun decodeSampledBitmap(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

@Composable
private fun rememberDecodedBitmap(bytes: ByteArray?, maxSide: Int): Bitmap? {
    var bitmap by remember(bytes, maxSide) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bytes, maxSide) {
        bitmap = bytes?.let { source ->
            withContext(Dispatchers.IO) { decodeSampledBitmap(source, maxSide) }
        }
    }
    val currentBitmap = bitmap
    DisposableEffect(currentBitmap) {
        onDispose { currentBitmap?.recycle() }
    }
    return bitmap
}

internal class WalletSessionViewModel : ViewModel() {
    val unlocked = mutableStateOf(false)
    val nav = mutableStateOf(0)
    val searchQuery = mutableStateOf("")
    val selected = mutableStateOf<DocumentEntity?>(null)
    val editing = mutableStateOf<DocumentEntity?>(null)
    val showAdd = mutableStateOf(false)
    val quickShow = mutableStateOf<DocumentEntity?>(null)
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: WalletRepository
    private lateinit var pinManager: PinManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        repository = WalletRepository(this, db.documentDao(), db.accountDao(), EncryptionManager())
        pinManager = PinManager(this)
        setContent {
            MyIDTheme {
                WalletRoot(repository, pinManager,
                    onShare = { doc, bytes -> sharePdf(doc, bytes) },
                    onSave = { doc, bytes -> savePdf(doc, bytes) })
            }
        }
    }

    private fun sharePdf(doc: DocumentEntity, sides: List<ByteArray>) {
        try {
            val file = PdfExporter.createPdf(this, sides, doc.title)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share ${doc.title}"))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not share PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun savePdf(doc: DocumentEntity, sides: List<ByteArray>) {
        try {
            val file = PdfExporter.createPdf(this, sides, doc.title)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads file.")
            contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
            file.delete()
            Toast.makeText(this, "PDF saved to Downloads", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not save PDF", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletRoot(
    repository: WalletRepository,
    pinManager: PinManager,
    onShare: (DocumentEntity, List<ByteArray>) -> Unit,
    onSave: (DocumentEntity, List<ByteArray>) -> Unit
) {
    val session: WalletSessionViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tabStateHolder = rememberSaveableStateHolder()
    val documents by repository.documents.collectAsState(initial = emptyList())
    val account by repository.account.collectAsState(initial = null)
    var unlocked by session.unlocked
    var setupMode by remember { mutableStateOf(!pinManager.isConfigured()) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var themeMode by remember {
        mutableStateOf(
            context.getSharedPreferences("wallet_settings", android.content.Context.MODE_PRIVATE)
                .getString("theme_mode", "system") ?: "system"
        )
    }
    val dark = themeMode == "dark" || (themeMode == "system" && androidx.compose.foundation.isSystemInDarkTheme())
    var nav by session.nav
    var searchQuery by session.searchQuery
    var selected by session.selected
    var editing by session.editing
    var showAdd by session.showAdd
    var quickShow by session.quickShow

    MyIDTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!unlocked) {
                PinScreen(setupMode, pin, confirmPin, pinError,
                    onPin = { pin = it.take(12) },
                    onConfirm = { confirmPin = it.take(12) },
                    onSubmit = {
                        if (setupMode) {
                            if (pin != confirmPin) pinError = "PINs do not match."
                            else if (!pinManager.create(pin)) pinError = "Use at least 8 digits."
                            else { unlocked = true; pinError = null }
                        } else if (pinManager.verify(pin)) {
                            unlocked = true; pinError = null
                        } else pinError = "Incorrect PIN."
                        pin = ""; confirmPin = ""
                    })
            } else when {
                quickShow != null -> QuickShowScreen(quickShow!!, repository) { quickShow = null }
                selected != null -> CardDetailScreen(
                    selected!!, repository,
                    onBack = { selected = null },
                        onQuickShow = { quickShow = it },
                    onEdit = { editing = it; showAdd = true },
                    onDelete = { doc ->
                        scope.launch { repository.delete(doc); selected = null }
                    },
                    onSave = { bytes -> onSave(selected!!, bytes) },
                    onShare = { bytes -> onShare(selected!!, bytes) })
                showAdd || editing != null -> AddDocumentScreen(
                    existing = editing,
                    repository = repository,
                    onCancel = { showAdd = false; editing = null },
                    onSaved = { showAdd = false; editing = null; nav = 0 })
                else -> {
                    BackHandler(enabled = nav != 0) { nav = 0 }
                    Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(selected = nav == 0, onClick = { nav = 0 },
                                icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                            NavigationBarItem(selected = nav == 1, onClick = { nav = 1 },
                                icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                            NavigationBarItem(selected = nav == 2, onClick = { nav = 2 },
                                icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Favourites") })
                            NavigationBarItem(selected = nav == 3, onClick = { nav = 3 },
                                icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
                            NavigationBarItem(selected = nav == 4, onClick = { nav = 4 },
                                icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                        }
                    }) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (nav) {
                            0 -> tabStateHolder.SaveableStateProvider("home") {
                                HomeScreen(documents, repository, onOpen = { selected = it },
                                    onAdd = { editing = null; showAdd = true }, dark = dark,
                                    onDark = { isDark ->
                                        val mode = if (isDark) "dark" else "light"
                                        context.getSharedPreferences("wallet_settings", android.content.Context.MODE_PRIVATE)
                                            .edit().putString("theme_mode", mode).apply()
                                        themeMode = mode
                                    },
                                    onToggleFavourite = { document -> scope.launch { repository.favourite(document) } })
                            }
                            1 -> tabStateHolder.SaveableStateProvider("search") {
                                SearchScreen(
                                    documents = documents,
                                    repository = repository,
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    onOpen = { selected = it },
                                    onToggleFavourite = { document -> scope.launch { repository.favourite(document) } }
                                )
                            }
                            2 -> tabStateHolder.SaveableStateProvider("favourites") {
                                FavouritesScreen(
                                    documents = documents.filter { it.isFavourite },
                                    repository = repository,
                                    onOpen = { selected = it },
                                    onToggleFavourite = { document -> scope.launch { repository.favourite(document) } }
                                )
                            }
                            3 -> tabStateHolder.SaveableStateProvider("profile") {
                                AccountScreen(account, repository)
                            }
                            4 -> tabStateHolder.SaveableStateProvider("settings") {
                                SettingsScreen(
                                    themeMode = themeMode,
                                    onThemeModeChange = { mode ->
                                        context.getSharedPreferences("wallet_settings", android.content.Context.MODE_PRIVATE)
                                            .edit().putString("theme_mode", mode).apply()
                                        themeMode = mode
                                    },
                                    pinManager = pinManager
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

                    @Composable
                    private fun PinScreen(
                        setup: Boolean,
                        pin: String,
                        confirmPin: String,
                        error: String?,
                        onPin: (String) -> Unit,
                        onConfirm: (String) -> Unit,
                        onSubmit: () -> Unit
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("MyID Wallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF102A43))
                            Spacer(Modifier.height(8.dp))
                            Text(if (setup) "Create your wallet PIN" else "Enter your PIN")
                            Spacer(Modifier.height(24.dp))
                            OutlinedTextField(pin, onPin, label = { Text("PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth())
                            if (setup) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(confirmPin, onConfirm, label = { Text("Confirm PIN") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    modifier = Modifier.fillMaxWidth())
                            }
                            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text(if (setup) "Create Wallet" else "Unlock Wallet")
                            }
                        }
                    }

                    @Composable
                    private fun HomeScreen(
                        documents: List<DocumentEntity>,
                        repository: WalletRepository,
                        onOpen: (DocumentEntity) -> Unit,
                        onAdd: () -> Unit,
                        dark: Boolean,
                        onDark: (Boolean) -> Unit,
                        onToggleFavourite: (DocumentEntity) -> Unit
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            Spacer(Modifier.height(18.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("MyID Wallet", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    Text("Private digital wallet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onDark(!dark) }) { Text(if (dark) "Light" else "Dark") }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add New Document")
                            }
                            Spacer(Modifier.height(18.dp))
                            if (documents.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Add, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(64.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("Your wallet is empty", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Add your first identity card", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(12.dp))
                                        OutlinedButton(onClick = onAdd) { Text("Add a document") }
                                    }
                                }

                            } else {
                                Text("${documents.size} saved ${if (documents.size == 1) "card" else "cards"}",
                                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                    items(documents, key = { it.id }) { doc ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable { onOpen(doc) },
                                            shape = RoundedCornerShape(18.dp),
                                            elevation = CardDefaults.cardElevation(2.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().height(112.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(Modifier.width(112.dp).fillMaxHeight()) {
                                                    EncryptedImage(repository, doc.frontFile)
                                                }
                                                Column(
                                                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Text(
                                                        text = doc.title.ifBlank { doc.category },
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (doc.title.isNotBlank() && doc.title != doc.category) {
                                                        Spacer(Modifier.height(4.dp))
                                                        Text(
                                                            doc.category,
                                                            fontSize = 13.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                IconButton(onClick = { onToggleFavourite(doc) }) {
                                                    Icon(
                                                        if (doc.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = if (doc.isFavourite) "Remove favourite" else "Add favourite",
                                                        tint = if (doc.isFavourite) Color(0xFFDC2626)
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = "Open ${doc.title.ifBlank { doc.category }}",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                    modifier = Modifier.padding(end = 12.dp).size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                                @Composable
                                private fun SearchScreen(
                                    documents: List<DocumentEntity>,
                                    repository: WalletRepository,
                                    query: String,
                                    onQueryChange: (String) -> Unit,
                                    onOpen: (DocumentEntity) -> Unit,
                                    onToggleFavourite: (DocumentEntity) -> Unit
                                ) {
                                    val matches = remember(documents, query) {
                                        val term = query.trim()
                                        if (term.isEmpty()) emptyList() else documents.filter {
                                            it.title.contains(term, ignoreCase = true) ||
                                                it.category.contains(term, ignoreCase = true)
                                        }
                                    }
                                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                                        Spacer(Modifier.height(18.dp))
                                        Text("Search", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(14.dp))
                                        OutlinedTextField(
                                            value = query,
                                            onValueChange = onQueryChange,
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            label = { Text("Document name or type") },
                                            trailingIcon = {
                                                if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) {
                                                    Text("Clear")
                                                }
                                            }
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        when {
                                            query.isBlank() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                Text("Search your saved documents by name or type.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            matches.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                Text("No documents match “$query”.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            else -> DocumentResults(
                                                documents = matches,
                                                repository = repository,
                                                onOpen = onOpen,
                                                onToggleFavourite = onToggleFavourite
                                            )
                                        }
                                    }
                                }

                                @Composable
                                private fun FavouritesScreen(
                                    documents: List<DocumentEntity>,
                                    repository: WalletRepository,
                                    onOpen: (DocumentEntity) -> Unit,
                                    onToggleFavourite: (DocumentEntity) -> Unit
                                ) {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                                        Spacer(Modifier.height(18.dp))
                                        Text("Favourites", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(14.dp))
                                        if (documents.isEmpty()) {
                                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                Text("Documents you favourite will appear here.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        } else {
                                            DocumentResults(
                                                documents = documents,
                                                repository = repository,
                                                onOpen = onOpen,
                                                onToggleFavourite = onToggleFavourite
                                            )
                                        }
                                    }
                                }

                                @Composable
                                private fun DocumentResults(
                                    documents: List<DocumentEntity>,
                                    repository: WalletRepository,
                                    onOpen: (DocumentEntity) -> Unit,
                                    onToggleFavourite: (DocumentEntity) -> Unit
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                    ) {
                                        items(documents, key = { it.id }) { doc ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().clickable { onOpen(doc) },
                                                shape = RoundedCornerShape(18.dp),
                                                elevation = CardDefaults.cardElevation(2.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().height(112.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(Modifier.width(112.dp).fillMaxHeight()) {
                                                        EncryptedImage(repository, doc.frontFile)
                                                    }
                                                    Column(
                                                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(doc.title.ifBlank { doc.category }, fontWeight = FontWeight.SemiBold)
                                                        if (doc.title.isNotBlank() && doc.title != doc.category) {
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(doc.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                    IconButton(onClick = { onToggleFavourite(doc) }) {
                                                        Icon(
                                                            if (doc.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                            contentDescription = if (doc.isFavourite) "Remove favourite" else "Add favourite",
                                                            tint = if (doc.isFavourite) Color(0xFFDC2626)
                                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Icon(
                                                        Icons.Default.ChevronRight,
                                                        contentDescription = "Open ${doc.title.ifBlank { doc.category }}",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                        modifier = Modifier.padding(end = 12.dp).size(22.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                @Composable
                                private fun EncryptedImage(repository: WalletRepository, fileName: String) {
                                    var bitmap by remember(fileName) { mutableStateOf<Bitmap?>(null) }
                                    var unavailable by remember(fileName) { mutableStateOf(false) }
                                    LaunchedEffect(fileName) {
                                        if (fileName.isNotBlank()) {
                                            try {
                                                bitmap = withContext(Dispatchers.IO) {
                                                    val bytes = repository.readEncryptedImage(fileName)
                                                    decodeSampledBitmap(bytes, 1200)
                                                }
                                                unavailable = bitmap == null
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                unavailable = true
                                            }
                                        }
                                    }
                                    val currentBitmap = bitmap
                                    DisposableEffect(currentBitmap) {
                                        onDispose { currentBitmap?.recycle() }
                                    }
                                    if (bitmap != null) {
                                        Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    } else if (unavailable) {
                                        Text("Image unavailable", Modifier.fillMaxSize().wrapContentSize(Alignment.Center))
                                    }
                                }

                                        @OptIn(ExperimentalMaterial3Api::class)
                                        @Composable
                                        private fun AddDocumentScreen(
                                            existing: DocumentEntity?,
                                            repository: WalletRepository,
                                            onCancel: () -> Unit,
                                            onSaved: () -> Unit
                                        ) {
                                            val context = LocalContext.current
                                            val scope = rememberCoroutineScope()
                                            val categories = listOf("Aadhaar Card", "PAN Card", "Driving Licence", "Voter ID", "Passport", "Other Documents")
                                            var category by remember(existing) { mutableStateOf(existing?.category ?: categories.first()) }
                                            var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
                                            var expanded by remember { mutableStateOf(false) }
                                            var frontBytes by remember(existing) { mutableStateOf<ByteArray?>(null) }
                                            var backBytes by remember(existing) { mutableStateOf<ByteArray?>(null) }
                                            var removeBack by remember(existing) { mutableStateOf(false) }
                                            var error by remember { mutableStateOf<String?>(null) }
                                            var showOcrReview by remember { mutableStateOf(false) }
                                            var ocrNumber by remember { mutableStateOf(existing?.documentNumber ?: "") }
                                            var ocrName by remember { mutableStateOf(existing?.holderName ?: "") }
                                            var ocrDob by remember { mutableStateOf(existing?.dateOfBirth ?: "") }
                                            var ocrExpiry by remember { mutableStateOf(existing?.expiryDate ?: "") }
                                            var cropSide by rememberSaveable { mutableStateOf<String?>(null) }
                                            var cropUriText by rememberSaveable { mutableStateOf<String?>(null) }
                                            var cropRequestId by rememberSaveable { mutableStateOf(0) }
                                            var cameraUriText by rememberSaveable { mutableStateOf<String?>(null) }
                                            var cameraTempName by rememberSaveable { mutableStateOf<String?>(null) }
                                            var cropRotation by rememberSaveable { mutableStateOf(0) }
                                            var cropCorners by rememberSaveable(stateSaver = CropCornersSaver) {
                                                mutableStateOf(CropCorners())
                                            }
                                            var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
                                            var cropResultBytes by remember { mutableStateOf<ByteArray?>(null) }
                                            var cropResultPreview by remember { mutableStateOf<Bitmap?>(null) }
                                            var cropProcessing by remember { mutableStateOf(false) }

                                            fun clearCropSource() {
                                                cropRequestId++
                                                cropBitmap?.recycle()
                                                cropBitmap = null
                                                cropUriText = null
                                                cropSide = null
                                                cropRotation = 0
                                                cropProcessing = false
                                                error = null
                                                cropResultBytes = null
                                                cropResultPreview?.recycle()
                                                cropResultPreview = null
                                                cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                cameraTempName = null
                                                cameraUriText = null
                                            }

                                            fun startCrop(uri: Uri, side: String, capturedCameraFile: String? = null) {
                                                cropBitmap?.recycle()
                                                cropBitmap = null
                                                cropResultBytes = null
                                                cropResultPreview?.recycle()
                                                cropResultPreview = null
                                                cropRequestId++
                                                cropUriText = uri.toString()
                                                cropSide = side
                                                cropProcessing = false
                                                error = null
                                                cropRotation = 0
                                                cropCorners = CropCorners()
                                                cameraTempName = capturedCameraFile
                                                cameraUriText = if (capturedCameraFile != null) uri.toString() else null
                                            }

                                            LaunchedEffect(cropUriText, cropRotation, cropRequestId) {
                                                val source = cropUriText?.let(Uri::parse) ?: return@LaunchedEffect
                                                val requestId = cropRequestId
                                                var decodeError: String? = null
                                                val decoded = try {
                                                    withContext(Dispatchers.IO) {
                                                        CardImageProcessor.decode(
                                                            context,
                                                            source,
                                                            if (cameraTempName != null) "camera" else "gallery"
                                                        )
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    decodeError = when {
                                                        e is SecurityException ->
                                                            "Access to this image was denied. Please select it again."
                                                        e.message == "The selected image is too large to open safely." ->
                                                            e.message
                                                        e.message == "The image provider could not open this image." ->
                                                            e.message
                                                        e.message == "The selected file is not a supported or readable image." ->
                                                            e.message
                                                        else -> "This image could not be opened. Please choose another image."
                                                    }
                                                    null
                                                }
                                                if (requestId != cropRequestId) {
                                                    decoded?.recycle()
                                                    return@LaunchedEffect
                                                }
                                                if (decoded == null) {
                                                    error = decodeError ?: "The selected file is not a supported or readable image."
                                                } else {
                                                    error = null
                                                    val rotated = CardImageProcessor.rotateBitmap(decoded, cropRotation)
                                                    cropBitmap?.takeIf { it !== rotated }?.recycle()
                                                    cropBitmap = rotated
                                                }
                                            }

                                            BackHandler(enabled = cropUriText != null) {
                                                clearCropSource()
                                            }
                                            BackHandler(enabled = cropUriText == null) {
                                                onCancel()
                                            }

                                            LaunchedEffect(existing?.id) {
                                                existing?.let { doc ->
                                                    withContext(Dispatchers.IO) {
                                                        val front = if (doc.frontFile.isNotBlank()) repository.readEncryptedImage(doc.frontFile) else null
                                                        val back = doc.backFile?.let { repository.readEncryptedImage(it) }
                                                        withContext(Dispatchers.Main) {
                                                            frontBytes = front
                                                            backBytes = back
                                                        }
                                                    }
                                                    }
                                                }

                                                    @Composable
                                                    fun ImageSideSection(
                                                        title: String,
                                                        hasOptionalBack: Boolean,
                                                        bytes: ByteArray?,
                                                        onGallery: () -> Unit,
                                                        onCamera: () -> Unit,
                                                        onRemove: () -> Unit
                                                    ) {
                                                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                                            Column(Modifier.padding(14.dp)) {
                                                                Text(title, fontWeight = FontWeight.Bold)
                                                                Spacer(Modifier.height(10.dp))
                                                                if (bytes != null) {
                                                                    val bitmap = rememberDecodedBitmap(bytes, 1600)
                                                                    bitmap?.let {
                                                                        Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(180.dp),
                                                                            contentScale = ContentScale.Fit)
                                                                        TextButton(onClick = onRemove) { Icon(Icons.Default.Delete, null); Text("Remove image") }
                                                                    }
                                                                } else {
                                                                    Row(Modifier.fillMaxWidth()) {
                                                                        OutlinedButton(onClick = onGallery, Modifier.weight(1f).height(52.dp)) {
                                                                            Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Gallery")
                                                                        }
                                                                        Spacer(Modifier.width(10.dp))
                                                                        OutlinedButton(onClick = onCamera, Modifier.weight(1f).height(52.dp)) {
                                                                            Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Camera")
                                                                        }
                                                                    }
                                                                }
                                                                if (bytes != null) {
                                                                    Row(Modifier.fillMaxWidth()) {
                                                                        TextButton(onClick = onGallery, Modifier.weight(1f)) { Text("Replace gallery") }
                                                                        TextButton(onClick = onCamera, Modifier.weight(1f)) { Text("Retake") }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    @Composable
                                                    fun CropEditorScreen(
                                                        bitmap: Bitmap,
                                                        corners: CropCorners,
                                                        onCornersChange: (CropCorners) -> Unit,
                                                        onCancel: () -> Unit,
                                                        onApply: () -> Unit,
                                                        onRotate: (Int) -> Unit,
                                                        onReset: () -> Unit,
                                                        onGallery: () -> Unit,
                                                        onCamera: () -> Unit
                                                    ) {
                                                        var zoom by rememberSaveable { mutableStateOf(1f) }
                                                        var panX by rememberSaveable { mutableStateOf(0f) }
                                                        var panY by rememberSaveable { mutableStateOf(0f) }
                                                        var previewResult by remember { mutableStateOf<Bitmap?>(null) }
                                                        var previewing by remember { mutableStateOf(false) }
                                                        val latestCorners by rememberUpdatedState(corners)
                                                        val latestPanX by rememberUpdatedState(panX)
                                                        val latestPanY by rememberUpdatedState(panY)
                                                        val latestZoom by rememberUpdatedState(zoom)
                                                        val density = androidx.compose.ui.platform.LocalDensity.current
                                                        val handleHitRadius = with(density) { 28.dp.toPx() }

                                                        DisposableEffect(previewResult) {
                                                            onDispose { previewResult?.recycle() }
                                                        }
                                                        Column(
                                                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
                                                        ) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                TextButton(onClick = onCancel) {
                                                                    Icon(Icons.Default.ArrowBack, null)
                                                                    Text("Cancel")
                                                                }
                                                                Spacer(Modifier.weight(1f))
                                                                Text("Adjust card edges", fontWeight = FontWeight.Bold)
                                                            }
                                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                                TextButton(onClick = { onRotate(-90) }, Modifier.weight(1f)) { Text("Rotate left") }
                                                                TextButton(onClick = { onRotate(90) }, Modifier.weight(1f)) { Text("Rotate right") }
                                                            }
                                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                                TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }, Modifier.weight(1f)) {
                                                                    Text("Zoom +")
                                                                }
                                                                TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(.5f) }, Modifier.weight(1f)) {
                                                                    Text("Zoom -")
                                                                }
                                                                TextButton(onClick = {
                                                                    onReset()
                                                                    zoom = 1f
                                                                    panX = 0f
                                                                    panY = 0f
                                                                }, Modifier.weight(1f)) { Text("Reset") }
                                                            }
                                                            Canvas(
                                                                Modifier.fillMaxWidth().weight(1f).pointerInput(bitmap) {
                                                                    var activeHandle: Int? = null
                                                                    detectDragGestures(
                                                                        onDragStart = { position ->
                                                                            val matrix = CardImageProcessor.imageToViewMatrix(
                                                                                bitmap.width, bitmap.height, size.width.toFloat(),
                                                                                size.height.toFloat(), latestZoom,
                                                                                android.graphics.PointF(latestPanX, latestPanY)
                                                                            )
                                                                            val points = listOf(
                                                                                latestCorners.topLeft, latestCorners.topRight,
                                                                                latestCorners.bottomRight, latestCorners.bottomLeft
                                                                            )
                                                                            val viewPoints = FloatArray(8)
                                                                            points.forEachIndexed { index, point ->
                                                                                viewPoints[index * 2] = point.x * bitmap.width
                                                                                viewPoints[index * 2 + 1] = point.y * bitmap.height
                                                                            }
                                                                            matrix.mapPoints(viewPoints)
                                                                            activeHandle = (0..3).minByOrNull { index ->
                                                                                hypot(
                                                                                    position.x - viewPoints[index * 2],
                                                                                    position.y - viewPoints[index * 2 + 1]
                                                                                )
                                                                            }?.takeIf { index ->
                                                                                hypot(
                                                                                    position.x - viewPoints[index * 2],
                                                                                    position.y - viewPoints[index * 2 + 1]
                                                                                ) <= handleHitRadius
                                                                            }
                                                                        },
                                                                        onDragEnd = { activeHandle = null },
                                                                        onDragCancel = { activeHandle = null }
                                                                    ) { change, drag ->
                                                                        change.consume()
                                                                        val handle = activeHandle
                                                                        if (handle == null) {
                                                                            panX += drag.x
                                                                            panY += drag.y
                                                                        } else {
                                                                            val matrix = CardImageProcessor.imageToViewMatrix(
                                                                                bitmap.width, bitmap.height, size.width.toFloat(),
                                                                                size.height.toFloat(), latestZoom,
                                                                                android.graphics.PointF(latestPanX, latestPanY)
                                                                            )
                                                                            val imagePoint = CardImageProcessor.viewToImagePoint(
                                                                                matrix, change.position.x, change.position.y
                                                                            ) ?: return@detectDragGestures
                                                                            CardImageProcessor.moveCorner(
                                                                                latestCorners, handle,
                                                                                imagePoint.x / bitmap.width,
                                                                                imagePoint.y / bitmap.height,
                                                                                bitmap.width, bitmap.height
                                                                            )?.let(onCornersChange)
                                                                        }
                                                                    }
                                                                }
                                                            ) {
                                                                drawRect(Color.Black)
                                                                val matrix = CardImageProcessor.imageToViewMatrix(
                                                                    bitmap.width, bitmap.height, size.width, size.height,
                                                                    zoom, android.graphics.PointF(panX, panY)
                                                                )
                                                                drawContext.canvas.nativeCanvas.drawBitmap(
                                                                    bitmap, matrix,
                                                                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                                                                )
                                                                val mapped = FloatArray(8)
                                                                listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
                                                                    .forEachIndexed { index, point ->
                                                                        mapped[index * 2] = point.x * bitmap.width
                                                                        mapped[index * 2 + 1] = point.y * bitmap.height
                                                                    }
                                                                matrix.mapPoints(mapped)
                                                                val points = (0..3).map { index ->
                                                                    Offset(mapped[index * 2], mapped[index * 2 + 1])
                                                                }
                                                                val boundary = Path().apply {
                                                                    moveTo(points[0].x, points[0].y)
                                                                    points.drop(1).forEach { lineTo(it.x, it.y) }
                                                                    close()
                                                                }
                                                                drawPath(boundary, Color(0xFF3B82F6).copy(alpha = .18f))
                                                                drawPath(boundary, Color(0xFF3B82F6), style = Stroke(width = 3.dp.toPx()))
                                                                points.forEach { point ->
                                                                    drawCircle(Color.White, 28.dp.toPx(), point)
                                                                    drawCircle(Color(0xFF3B82F6), 18.dp.toPx(), point)
                                                                }
                                                            }
                                                            Text(
                                                                "Drag each corner handle. Drag elsewhere to pan; use zoom controls for precision.",
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                                OutlinedButton(
                                                                    onClick = {
                                                                        previewing = true
                                                                        scope.launch {
                                                                            try {
                                                                                val candidate = withContext(Dispatchers.Default) {
                                                                                    CardImageProcessor.warp(bitmap, corners)
                                                                                }
                                                                                previewResult?.recycle()
                                                                                previewResult = candidate
                                                                            } catch (e: CancellationException) {
                                                                                throw e
                                                                            } catch (e: Exception) {
                                                                                error = e.message ?: "Could not preview the crop."
                                                                            } finally {
                                                                                previewing = false
                                                                            }
                                                                        }
                                                                    },
                                                                    modifier = Modifier.weight(1f),
                                                                    enabled = !previewing
                                                                ) { Text(if (previewing) "Preparing…" else "Preview Result") }
                                                                Button(
                                                                    onClick = onApply,
                                                                    modifier = Modifier.weight(1f),
                                                                    enabled = !cropProcessing && CardImageProcessor.isValid(
                                                                        corners, bitmap.width, bitmap.height
                                                                    )
                                                                ) { Text(if (cropProcessing) "Applying…" else "Apply Crop") }
                                                            }
                                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                                TextButton(onClick = onCamera) { Text("Retake") }
                                                                TextButton(onClick = onGallery) { Text("Choose another image") }
                                                            }
                                                        }
                                                        if (previewResult != null) {
                                                            AlertDialog(
                                                                onDismissRequest = {
                                                                    previewResult?.recycle()
                                                                    previewResult = null
                                                                },
                                                                title = { Text("Crop preview") },
                                                                text = {
                                                                    Image(
                                                                        previewResult!!.asImageBitmap(),
                                                                        "Perspective crop preview",
                                                                        Modifier.fillMaxWidth().height(240.dp),
                                                                        contentScale = ContentScale.Fit
                                                                    )
                                                                },
                                                                confirmButton = {
                                                                    TextButton(onClick = {
                                                                        previewResult?.recycle()
                                                                        previewResult = null
                                                                    }) { Text("Continue editing") }
                                                                }
                                                            )
                                                        }
                                                    }

                                            var pendingSide by rememberSaveable { mutableStateOf("front") }
                                            val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                                                val uri = cameraUriText?.let(Uri::parse)
                                                val cameraFile = cameraTempName?.let { File(context.cacheDir, it) }
                                                Log.i(
                                                    "WalletImageFlow",
                                                    "Camera callback success=$success outputExists=${cameraFile?.isFile == true} " +
                                                        "outputHasData=${(cameraFile?.length() ?: 0L) > 0L}"
                                                )
                                                if (success && uri != null && cameraFile != null &&
                                                    cameraFile.isFile && cameraFile.length() > 0L
                                                ) {
                                                    cropBitmap?.recycle()
                                                    cropBitmap = null
                                                    cropResultPreview?.recycle()
                                                    cropResultPreview = null
                                                    cropResultBytes = null
                                                    cropRequestId++
                                                    cropUriText = uri.toString()
                                                    cropSide = pendingSide
                                                    error = null
                                                    cropRotation = 0
                                                    cropCorners = CropCorners()
                                                } else {
                                                    cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                    cameraTempName = null
                                                    cameraUriText = null
                                                    if (success) error = "The camera did not create an image. Please try again."
                                                }
                                            }
                                            val frontGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                                if (uri != null) {
                                                    cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                    cameraTempName = null
                                                    cameraUriText = null
                                                    startCrop(uri, "front")
                                                }
                                            }
                                            val backGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                                if (uri != null) {
                                                    cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                    cameraTempName = null
                                                    cameraUriText = null
                                                    startCrop(uri, "back")
                                                }
                                            }

                                            fun startCamera(side: String) {
                                                cropRequestId++
                                                cropBitmap?.recycle()
                                                cropBitmap = null
                                                cropProcessing = false
                                                cropResultBytes = null
                                                cropResultPreview?.recycle()
                                                cropResultPreview = null
                                                cropUriText = null
                                                cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                pendingSide = side
                                                try {
                                                    val file = File.createTempFile("camera_", ".jpg", context.cacheDir)
                                                    val uri = FileProvider.getUriForFile(
                                                        context, "${context.packageName}.fileprovider", file
                                                    )
                                                    cameraTempName = file.name
                                                    cameraUriText = uri.toString()
                                                    cameraLauncher.launch(uri)
                                                } catch (e: Exception) {
                                                    cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                    cameraTempName = null
                                                    cameraUriText = null
                                                    Log.w(
                                                        "WalletImageFlow",
                                                        "Camera launch failed: ${e.javaClass.simpleName}: could not create or launch camera output."
                                                    )
                                                    error = "Could not open the camera. Please try again."
                                                }
                                            }

                                            fun applyCrop() {
                                                val sourceUri = cropUriText?.let(Uri::parse) ?: return
                                                if (cropSide == null) return
                                                val requestId = cropRequestId
                                                val rotation = cropRotation
                                                val corners = cropCorners
                                                val sourceType = if (cameraTempName != null) "camera" else "gallery"
                                                cropProcessing = true
                                                error = null
                                                scope.launch {
                                                    try {
                                                        val result = withContext(Dispatchers.IO) {
                                                            val original = CardImageProcessor.decodeFull(
                                                                context,
                                                                sourceUri,
                                                                sourceType
                                                            )
                                                                ?: throw IllegalStateException("Could not read the original image.")
                                                            val oriented = CardImageProcessor.rotateBitmap(original, rotation)
                                                            try {
                                                                require(CardImageProcessor.isValid(corners, oriented.width, oriented.height)) {
                                                                    "Crop corners are too close together or form an invalid shape."
                                                                }
                                                                val corrected = CardImageProcessor.warp(oriented, corners)
                                                                try {
                                                                    val stream = ByteArrayOutputStream()
                                                                    check(corrected.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                                                                        "Could not encode the cropped image."
                                                                    }
                                                                    val scale = minOf(
                                                                        1f,
                                                                        1400f / maxOf(corrected.width, corrected.height)
                                                                    )
                                                                    val preview = if (scale < 1f) {
                                                                        Bitmap.createScaledBitmap(
                                                                            corrected,
                                                                            (corrected.width * scale).toInt().coerceAtLeast(1),
                                                                            (corrected.height * scale).toInt().coerceAtLeast(1),
                                                                            true
                                                                        )
                                                                    } else corrected.copy(Bitmap.Config.ARGB_8888, false)
                                                                        ?: throw IllegalStateException("Could not create crop preview.")
                                                                    stream.toByteArray() to preview
                                                                } finally {
                                                                    corrected.recycle()
                                                                }
                                                            } finally {
                                                                oriented.recycle()
                                                            }
                                                        }
                                                        if (requestId != cropRequestId) {
                                                            result.second.recycle()
                                                            return@launch
                                                        }
                                                        cropResultBytes = result.first
                                                        cropResultPreview = result.second
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        if (requestId == cropRequestId) error = when (e.message) {
                                                            "The selected image is too large to open safely.",
                                                            "The image provider could not open this image.",
                                                            "The selected file is not a supported or readable image.",
                                                            "Could not read the original image.",
                                                            "Crop corners are too close together or form an invalid shape.",
                                                            "Could not compute the perspective crop.",
                                                            "Could not encode the cropped image.",
                                                            "Could not create crop preview." -> e.message
                                                            else -> "Could not process this image. Please try another image."
                                                        }
                                                    } finally {
                                                        if (requestId == cropRequestId) cropProcessing = false
                                                    }
                                                }
                                            }

                                            if (cropResultBytes != null && cropResultPreview != null && cropSide != null) {
                                                Column(
                                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text("Preview cropped image", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                                    Spacer(Modifier.height(16.dp))
                                                    Image(
                                                        cropResultPreview!!.asImageBitmap(),
                                                        "Perspective-corrected image",
                                                        Modifier.fillMaxWidth().heightIn(max = 480.dp),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                    Spacer(Modifier.height(16.dp))
                                                    Button(
                                                        onClick = {
                                                            val bytes = cropResultBytes
                                                            val preview = cropResultPreview
                                                            if (cropSide == "front") frontBytes = bytes else {
                                                                backBytes = bytes
                                                                removeBack = false
                                                            }
                                                            cropResultBytes = null
                                                            cropResultPreview = null
                                                            cropBitmap?.recycle()
                                                            cropBitmap = null
                                                            cropUriText = null
                                                            cropSide = null
                                                            cropRotation = 0
                                                            cameraTempName?.let { File(context.cacheDir, it).delete() }
                                                            cameraTempName = null
                                                            cameraUriText = null
                                                            if (preview != null) {
                                                                scope.launch {
                                                                    try {
                                                                        val result = OcrExtractor.extract(preview)
                                                                        ocrNumber = result.documentNumber ?: ""
                                                                        ocrName = result.holderName ?: ""
                                                                        ocrDob = result.dateOfBirth ?: ""
                                                                        ocrExpiry = result.expiryDate ?: ""
                                                                        showOcrReview = true
                                                                    } catch (e: CancellationException) {
                                                                        throw e
                                                                    } catch (e: Exception) {
                                                                        error = e.message ?: "Could not read document text."
                                                                    } finally {
                                                                        preview.recycle()
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth().height(54.dp)
                                                    ) { Text("Confirm Image") }
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                        TextButton(onClick = { startCamera(cropSide!!) }) { Text("Retake") }
                                                        TextButton(onClick = {
                                                            val side = cropSide!!
                                                            cropResultBytes = null
                                                            cropResultPreview?.recycle()
                                                            cropResultPreview = null
                                                            if (side == "front") {
                                                                frontGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                            } else {
                                                                backGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                            }
                                                        }) { Text("Choose another image") }
                                                        TextButton(onClick = ::clearCropSource) { Text("Cancel") }
                                                    }
                                                }
                                                return
                                            }

                                            if (cropUriText != null && cropSide != null && cropBitmap == null) {
                                                Column(
                                                    Modifier.fillMaxSize().padding(24.dp),
                                                    verticalArrangement = Arrangement.Center,
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    if (error == null) {
                                                        CircularProgressIndicator()
                                                        Spacer(Modifier.height(16.dp))
                                                        Text("Loading image…")
                                                    } else {
                                                        Text(error!!, color = MaterialTheme.colorScheme.error)
                                                        Spacer(Modifier.height(16.dp))
                                                        Button(onClick = {
                                                            error = null
                                                            cropRequestId++
                                                        }) { Text("Retry") }
                                                        TextButton(onClick = {
                                                            val side = cropSide
                                                            if (side == "front") {
                                                                frontGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                            } else {
                                                                backGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                            }
                                                        }) { Text("Choose another image") }
                                                        TextButton(onClick = ::clearCropSource) { Text("Cancel") }
                                                    }
                                                }
                                                return
                                            }

                                            if (cropBitmap != null && cropSide != null) {
                                                CropEditorScreen(
                                                    bitmap = cropBitmap!!,
                                                    corners = cropCorners,
                                                    onCornersChange = { cropCorners = it },
                                                    onCancel = ::clearCropSource,
                                                    onApply = ::applyCrop,
                                                    onRotate = { degrees ->
                                                        cropCorners = CardImageProcessor.rotateCorners(cropCorners, degrees)
                                                        cropRotation = (cropRotation + degrees + 360) % 360
                                                    },
                                                    onReset = {
                                                        cropCorners = CropCorners()
                                                        cropRotation = 0
                                                    },
                                                    onGallery = {
                                                        if (cropSide == "front") {
                                                            frontGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                        } else {
                                                            backGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                        }
                                                    },
                                                    onCamera = { startCamera(cropSide!!) }
                                                )
                                                return
                                            }

                                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                                TextButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
                                                Text(if (existing == null) "Add a card" else "Edit card", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(16.dp))
                                                OutlinedTextField(title, { title = it }, label = { Text("Card title (optional)") }, modifier = Modifier.fillMaxWidth())
                                                Spacer(Modifier.height(12.dp))
                                                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                                                    OutlinedTextField(category, {}, readOnly = true, label = { Text("Document type") },
                                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                                        modifier = Modifier.menuAnchor().fillMaxWidth())
                                                    ExposedDropdownMenu(expanded, { expanded = false }) {
                                                        categories.forEach { item ->
                                                            DropdownMenuItem(text = { Text(item) }, onClick = { category = item; expanded = false })
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(18.dp))
                                                ImageSideSection("Front image", true, frontBytes,
                                                    onGallery = { frontGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                                    onCamera = { startCamera("front") },
                                                    onRemove = { frontBytes = null })
                                                Spacer(Modifier.height(14.dp))
                                                ImageSideSection("Back image", backBytes != null || existing?.backFile != null, backBytes,
                                                    onGallery = { backGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                                    onCamera = { startCamera("back") },
                                                    onRemove = { backBytes = null; removeBack = true })
                                                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                                                Spacer(Modifier.height(18.dp))
                                                Button(onClick = {
                                                    if (frontBytes == null) {
                                                        error = "A document type and front image are required."
                                                    } else {
                                                        scope.launch {
                                                            try {
                                                                val saved = repository.saveDocument(existing, title, category, frontBytes!!,
                                                                    backBytes, removeBack)
                                                                repository.updateMetadata(saved,
                                                                    ocrNumber.ifBlank { null },
                                                                    ocrName.ifBlank { null },
                                                                    ocrDob.ifBlank { null },
                                                                    ocrExpiry.ifBlank { null })
                                                                onSaved()
                                                            } catch (e: CancellationException) {
                                                                throw e
                                                            } catch (e: Exception) {
                                                                error = e.message ?: "Could not save document."
                                                            }
                                                        }
                                                    }
                                                }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                                    Text("Save Document")
                                                }
                                            }
                                            if (showOcrReview) {
                                                AlertDialog(
                                                    onDismissRequest = { showOcrReview = false },
                                                    title = { Text("Review detected details") },
                                                    text = {
                                                        Column(Modifier.verticalScroll(rememberScrollState())) {
                                                            Text("OCR runs only on this device. Review and correct every field before saving.")
                                                            Spacer(Modifier.height(8.dp))
                                                            OutlinedTextField(ocrName, { ocrName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                                                            Spacer(Modifier.height(8.dp))
                                                            OutlinedTextField(ocrDob, { ocrDob = it }, label = { Text("Date of birth") }, modifier = Modifier.fillMaxWidth())
                                                            Spacer(Modifier.height(8.dp))
                                                            OutlinedTextField(ocrExpiry, { ocrExpiry = it }, label = { Text("Expiry date") }, modifier = Modifier.fillMaxWidth())
                                                            Spacer(Modifier.height(8.dp))
                                                            OutlinedTextField(ocrNumber, { ocrNumber = it }, label = { Text("Document number") }, modifier = Modifier.fillMaxWidth())
                                                        }
                                                    },
                                                    confirmButton = { TextButton(onClick = { showOcrReview = false }) { Text("Approve") } },
                                                    dismissButton = { TextButton(onClick = { showOcrReview = false }) { Text("Keep unfilled") } }
                                                )
                                            }
                                        }
                                                    @Composable
                                                    private fun CardDetailScreen(
                                                                        document: DocumentEntity,
                                                                        repository: WalletRepository,
                                                                        onBack: () -> Unit,
                                                                        onQuickShow: (DocumentEntity) -> Unit,
                                                                        onEdit: (DocumentEntity) -> Unit,
                                                                        onDelete: (DocumentEntity) -> Unit,
                                                                        onSave: (List<ByteArray>) -> Unit,
                                                                        onShare: (List<ByteArray>) -> Unit
                                                                    ) {
                                                                        var isBack by remember(document.id) { mutableStateOf(false) }
                                                                        var front by remember(document.id) { mutableStateOf<ByteArray?>(null) }
                                                                        var back by remember(document.id) { mutableStateOf<ByteArray?>(null) }
                                                                        var error by remember { mutableStateOf<String?>(null) }
                                                                        var deleteConfirm by remember { mutableStateOf(false) }
                                                                        val rotation by animateFloatAsState(if (isBack) 180f else 0f, label = "cardFlip")

                                                                        BackHandler(onBack = onBack)

                                                                        LaunchedEffect(document.id) {
                                                                            try {
                                                                                val images = withContext(Dispatchers.IO) {
                                                                                    val f = if (document.frontFile.isNotBlank()) {
                                                                                        repository.readEncryptedImage(document.frontFile)
                                                                                    } else null
                                                                                    val b = document.backFile?.let {
                                                                                        repository.readEncryptedImage(it)
                                                                                    }
                                                                                    f to b
                                                                                }
                                                                                front = images.first
                                                                                back = images.second
                                                                            } catch (e: CancellationException) {
                                                                                throw e
                                                                            } catch (e: Exception) {
                                                                                error = e.message ?: "Could not load this document."
                                                                            }
                                                                        }

                                                                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                                                            TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
                                                                            Text(document.category, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                                                            Spacer(Modifier.height(18.dp))
                                                                            Card(
                                                                                modifier = Modifier.fillMaxWidth().height(260.dp).graphicsLayer {
                                                                                    rotationY = rotation
                                                                                    cameraDistance = 12 * density
                                                                                }.clickable {
                                                                                    if (back != null) isBack = !isBack
                                                                                },
                                                                                shape = RoundedCornerShape(24.dp),
                                                                                elevation = CardDefaults.cardElevation(8.dp)
                                                                            ) {
                                                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                                    val bytes = if (isBack) back else front
                                                                                    if (bytes != null) {
                                                                                        val bitmap = rememberDecodedBitmap(bytes, 2000)
                                                                                        if (bitmap != null) {
                                                                                            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                                                                                                if (isBack) rotationY = 180f
                                                                                            }, contentScale = ContentScale.Fit)
                                                                                        }
                                                                                    } else {
                                                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                                                            Text("Back side not added", fontWeight = FontWeight.SemiBold)
                                                                                            Spacer(Modifier.height(8.dp))
                                                                                            OutlinedButton(onClick = { onEdit(document) }) { Text("Add Back Side") }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                            Spacer(Modifier.height(10.dp))
                                                                            Text(if (isBack) "BACK • Tap to flip" else "FRONT • Tap to flip",
                                                                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                            Spacer(Modifier.height(18.dp))
                                                                            OutlinedButton(onClick = { onQuickShow(document) }, Modifier.fillMaxWidth().height(52.dp)) {
                                                                                Text("QUICK SHOW")
                                                                            }
                                                                            Spacer(Modifier.height(8.dp))
                                                                            Row(Modifier.fillMaxWidth()) {
                                                                                OutlinedButton(onClick = {
                                                                                    val sides = listOfNotNull(if (isBack) back else front)
                                                                                    if (sides.isEmpty()) error = "No image available." else onSave(sides)
                                                                                }, Modifier.weight(1f).height(52.dp)) {
                                                                                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(5.dp)); Text("SAVE")
                                                                                }
                                                                                Spacer(Modifier.width(8.dp))
                                                                                OutlinedButton(onClick = {
                                                                                    val sides = listOfNotNull(front, back)
                                                                                    if (sides.isEmpty()) error = "No images available." else onShare(sides)
                                                                                }, Modifier.weight(1f).height(52.dp)) {
                                                                                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(5.dp)); Text("SHARE")
                                                                                }
                                                                            }
                                                                            Spacer(Modifier.height(8.dp))
                                                                            Row(Modifier.fillMaxWidth()) {
                                                                                OutlinedButton(onClick = { onEdit(document) }, Modifier.weight(1f).height(52.dp)) {
                                                                                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(5.dp)); Text("EDIT")
                                                                                }
                                                                                Spacer(Modifier.width(8.dp))
                                                                                OutlinedButton(onClick = { deleteConfirm = true }, Modifier.weight(1f).height(52.dp),
                                                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))) {
                                                                                    Icon(Icons.Default.Delete, null); Spacer(Modifier.width(5.dp)); Text("DELETE")
                                                                                }
                                                                            }
                                                                            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                                                                        }

                                                                        if (deleteConfirm) {
                                                                            AlertDialog(
                                                                                onDismissRequest = { deleteConfirm = false },
                                                                                title = { Text("Delete this card?") },
                                                                                text = { Text("This permanently removes the card and both encrypted image files. Identity documents will not affect your account.") },
                                                                                confirmButton = { TextButton(onClick = { deleteConfirm = false; onDelete(document) }) { Text("Delete", color = Color(0xFFDC2626)) } },
                                                                                dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } }
                                                                            )
                                                                        }
                                                                    }
                                                                        @Composable
                                                                        private fun AccountScreen(account: AccountEntity?, repository: WalletRepository) {
                                                                            val context = LocalContext.current
                                                                            val scope = rememberCoroutineScope()
                                                                            var editing by rememberSaveable { mutableStateOf(false) }
                                                                            var deleteConfirm by rememberSaveable { mutableStateOf(false) }
                                                                            var name by rememberSaveable(account) { mutableStateOf(account?.name ?: "") }
                                                                            var age by rememberSaveable(account) { mutableStateOf(account?.age?.toString() ?: "") }
                                                                            var mobile by rememberSaveable(account) { mutableStateOf(account?.mobile ?: "") }
                                                                            var profileBytes by rememberSaveable(account) { mutableStateOf<ByteArray?>(null) }
                                                                            var removeProfile by rememberSaveable(account) { mutableStateOf(false) }
                                                                            var error by rememberSaveable { mutableStateOf<String?>(null) }

                                                                            LaunchedEffect(account?.profileFile) {
                                                                                try {
                                                                                    profileBytes = account?.profileFile?.let { file ->
                                                                                        withContext(Dispatchers.IO) {
                                                                                            repository.readEncryptedImage(file)
                                                                                        }
                                                                                    }
                                                                                } catch (e: CancellationException) {
                                                                                    throw e
                                                                                } catch (e: Exception) {
                                                                                    error = e.message ?: "Could not load profile image."
                                                                                }
                                                                            }

                                                                            val profileGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                                                                if (uri != null) {
                                                                                    scope.launch {
                                                                                        var bitmap: Bitmap? = null
                                                                                        var scaled: Bitmap? = null
                                                                                        try {
                                                                                            val sourceBitmap = withContext(Dispatchers.IO) {
                                                                                                CardImageProcessor.decode(context, uri, "profile-gallery")
                                                                                            } ?: throw IllegalStateException("Could not read profile image.")
                                                                                            bitmap = sourceBitmap
                                                                                            val scale = minOf(
                                                                                                1f,
                                                                                                800f / maxOf(sourceBitmap.width, sourceBitmap.height)
                                                                                            )
                                                                                            val profileBitmap = if (scale < 1f) {
                                                                                                Bitmap.createScaledBitmap(
                                                                                                    sourceBitmap,
                                                                                                    (sourceBitmap.width * scale).toInt().coerceAtLeast(1),
                                                                                                    (sourceBitmap.height * scale).toInt().coerceAtLeast(1),
                                                                                                    true
                                                                                                )
                                                                                            } else sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                                                                                ?: throw IllegalStateException("Could not resize profile image.")
                                                                                            scaled = profileBitmap
                                                                                            val stream = ByteArrayOutputStream()
                                                                                            withContext(Dispatchers.IO) {
                                                                                                check(profileBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                                                                                                    "Could not encode profile image."
                                                                                                }
                                                                                            }
                                                                                            profileBytes = stream.toByteArray()
                                                                                            removeProfile = false
                                                                                        } catch (e: CancellationException) {
                                                                                            throw e
                                                                                        } catch (e: Exception) {
                                                                                            error = if (e is SecurityException) {
                                                                                                "Access to this image was denied. Please select it again."
                                                                                            } else {
                                                                                                "This profile image could not be opened. Please choose another image."
                                                                                            }
                                                                                        } finally {
                                                                                            scaled?.recycle()
                                                                                            bitmap?.recycle()
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }

                                                                            if (editing) {
                                                                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                                                                    TextButton(onClick = { editing = false; error = null }) {
                                                                                        Icon(Icons.Default.ArrowBack, null); Text("Back")
                                                                                    }
                                                                                    Text("Edit Profile", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                                                                    Spacer(Modifier.height(16.dp))
                                                                                    ProfileAvatar(profileBytes)
                                                                                    Spacer(Modifier.height(10.dp))
                                                                                    Row(Modifier.fillMaxWidth()) {
                                                                                        TextButton(onClick = {
                                                                                            profileGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                                                        }, Modifier.weight(1f)) { Text("Choose image") }
                                                                                        if (profileBytes != null) TextButton(onClick = {
                                                                                            profileBytes = null; removeProfile = true
                                                                                        }, Modifier.weight(1f)) { Text("Remove", color = Color(0xFFDC2626)) }
                                                                                    }
                                                                                    OutlinedTextField(name, { name = it }, label = { Text("Name *") }, modifier = Modifier.fillMaxWidth())
                                                                                    Spacer(Modifier.height(10.dp))
                                                                                    OutlinedTextField(age, { age = it.filter { char -> char.isDigit() }.take(3) },
                                                                                        label = { Text("Age *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                                                        modifier = Modifier.fillMaxWidth())
                                                                                    Spacer(Modifier.height(10.dp))
                                                                                    OutlinedTextField(mobile, { mobile = it }, label = { Text("Mobile number (optional)") },
                                                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                                                                        modifier = Modifier.fillMaxWidth())
                                                                                    error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                                                                                    Spacer(Modifier.height(18.dp))
                                                                                    Button(onClick = {
                                                                                        val ageValue = age.toIntOrNull()
                                                                                        if (name.isBlank()) error = "Name is required."
                                                                                        else if (ageValue == null || ageValue !in 1..150) error = "Enter a valid age."
                                                                                        else scope.launch {
                                                                                            try {
                                                                                                repository.saveAccount(account, name.trim(), ageValue, mobile.trim(), profileBytes, removeProfile)
                                                                                                editing = false
                                                                                                error = null
                                                                                            } catch (e: CancellationException) {
                                                                                                throw e
                                                                                            } catch (e: Exception) {
                                                                                                error = e.message ?: "Could not save profile."
                                                                                            }
                                                                                        }
                                                                                    }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Save Profile") }
                                                                                }
                                                                            } else {
                                                                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                                                                    Spacer(Modifier.height(18.dp))
                                                                                    Text("Profile", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                                                                    Spacer(Modifier.height(24.dp))
                                                                                    ProfileAvatar(profileBytes)
                                                                                    Spacer(Modifier.height(16.dp))
                                                                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                                                                        Text(account?.name ?: "No profile set", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                                                                        account?.let {
                                                                                            Text("Age ${it.age}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                            it.mobile?.takeIf(String::isNotBlank)?.let { number ->
                                                                                                Spacer(Modifier.height(4.dp)); Text(number, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                    Spacer(Modifier.height(24.dp))
                                                                                    Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                                                                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit Profile")
                                                                                    }
                                                                                    Spacer(Modifier.height(10.dp))
                                                                                    OutlinedButton(onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth().height(52.dp),
                                                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))) {
                                                                                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete Account")
                                                                                    }
                                                                                }
                                                                            }

                                                                            if (deleteConfirm) {
                                                                                AlertDialog(
                                                                                    onDismissRequest = { deleteConfirm = false },
                                                                                    title = { Text("Delete account profile?") },
                                                                                    text = { Text("This deletes only your name, age, mobile number, and profile image. Your saved identity documents and wallet PIN will not be deleted.") },
                                                                                    confirmButton = {
                                                                                        TextButton(onClick = {
                                                                                            deleteConfirm = false
                                                                                            scope.launch { repository.deleteAccount(); name = ""; age = ""; mobile = ""; profileBytes = null }
                                                                                        }) { Text("Delete", color = Color(0xFFDC2626)) }
                                                                                    },
                                                                                    dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } }
                                                                                )
                                                                            }
                                                                        }

                                                                        @Composable
                                                                        private fun SettingsScreen(
                                                                            themeMode: String,
                                                                            onThemeModeChange: (String) -> Unit,
                                                                            pinManager: PinManager
                                                                        ) {
                                                                            val scope = rememberCoroutineScope()
                                                                            var showPinDialog by rememberSaveable { mutableStateOf(false) }
                                                                            var currentPin by remember { mutableStateOf("") }
                                                                            var newPin by remember { mutableStateOf("") }
                                                                            var confirmPin by remember { mutableStateOf("") }
                                                                            var pinMessage by remember { mutableStateOf<String?>(null) }
                                                                            var showHelp by rememberSaveable { mutableStateOf(false) }
                                                                            var pinSaving by remember { mutableStateOf(false) }

                                                                            Column(
                                                                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                                                                            ) {
                                                                                Spacer(Modifier.height(8.dp))
                                                                                Text("Settings", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                                                                Spacer(Modifier.height(18.dp))
                                                                                SettingsSection("Appearance") {
                                                                                    listOf(
                                                                                        "light" to "Light mode",
                                                                                        "dark" to "Dark mode",
                                                                                        "system" to "System default"
                                                                                    ).forEach { (value, label) ->
                                                                                        Row(
                                                                                            Modifier.fillMaxWidth().clickable { onThemeModeChange(value) },
                                                                                            verticalAlignment = Alignment.CenterVertically
                                                                                        ) {
                                                                                            RadioButton(
                                                                                                selected = themeMode == value,
                                                                                                onClick = { onThemeModeChange(value) }
                                                                                            )
                                                                                            Text(label)
                                                                                        }
                                                                                    }
                                                                                }
                                                                                SettingsSection("Security") {
                                                                                    SettingsAction("Change PIN", "Update the PIN used to unlock this wallet.") {
                                                                                        pinMessage = null
                                                                                        showPinDialog = true
                                                                                    }
                                                                                    HorizontalDivider()
                                                                                    Text("Fingerprint authentication — Not available in this version.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                        modifier = Modifier.padding(vertical = 10.dp))
                                                                                    Text("Automatic app lock — Not available in this version.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                        modifier = Modifier.padding(vertical = 10.dp))
                                                                                    Text("Your saved documents remain encrypted in private app storage. No cloud upload is used.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                }
                                                                                SettingsSection("Language") {
                                                                                    Text("English (current)")
                                                                                    Text("Tamil and Hindi are not available in this version.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                }
                                                                                SettingsSection("Backup") {
                                                                                    Text("Encrypted local backup and restore are not available in this version. No data will be exported or overwritten.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                }
                                                                                SettingsSection("Support") {
                                                                                    SettingsAction("Help and FAQ", "Using your offline wallet") {
                                                                                        showHelp = true
                                                                                    }
                                                                                    HorizontalDivider()
                                                                                    Text("Contact support — Not configured.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                        modifier = Modifier.padding(vertical = 10.dp))
                                                                                    Text("Send feedback — Not configured.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                        modifier = Modifier.padding(vertical = 10.dp))
                                                                                    Text("Privacy policy and terms — Not available in this version.",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                        modifier = Modifier.padding(vertical = 10.dp))
                                                                                    Text("App version ${com.myid.wallet.BuildConfig.VERSION_NAME}",
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                }
                                                                                pinMessage?.let {
                                                                                    Text(it, color = MaterialTheme.colorScheme.primary,
                                                                                        modifier = Modifier.padding(vertical = 8.dp))
                                                                                }
                                                                            }

                                                                            if (showPinDialog) {
                                                                                AlertDialog(
                                                                                    onDismissRequest = {
                                                                                        if (!pinSaving) showPinDialog = false
                                                                                    },
                                                                                    title = { Text("Change PIN") },
                                                                                    text = {
                                                                                        Column {
                                                                                            OutlinedTextField(
                                                                                                currentPin,
                                                                                                { currentPin = it.filter { char -> char.isDigit() }.take(12) },
                                                                                                label = { Text("Current PIN") },
                                                                                                visualTransformation = PasswordVisualTransformation(),
                                                                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                                                                                singleLine = true
                                                                                            )
                                                                                            Spacer(Modifier.height(8.dp))
                                                                                            OutlinedTextField(
                                                                                                newPin,
                                                                                                { newPin = it.filter { char -> char.isDigit() }.take(12) },
                                                                                                label = { Text("New PIN (at least 8 digits)") },
                                                                                                visualTransformation = PasswordVisualTransformation(),
                                                                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                                                                                singleLine = true
                                                                                            )
                                                                                            Spacer(Modifier.height(8.dp))
                                                                                            OutlinedTextField(
                                                                                                confirmPin,
                                                                                                { confirmPin = it.filter { char -> char.isDigit() }.take(12) },
                                                                                                label = { Text("Confirm new PIN") },
                                                                                                visualTransformation = PasswordVisualTransformation(),
                                                                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                                                                                singleLine = true
                                                                                            )
                                                                                            pinMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                                                                        }
                                                                                    },
                                                                                    confirmButton = {
                                                                                        TextButton(
                                                                                            enabled = !pinSaving,
                                                                                            onClick = {
                                                                                                if (newPin.length < 8) {
                                                                                                    pinMessage = "Use at least 8 digits."
                                                                                                } else if (newPin != confirmPin) {
                                                                                                    pinMessage = "New PINs do not match."
                                                                                                } else {
                                                                                                    pinSaving = true
                                                                                                    pinMessage = null
                                                                                                    scope.launch {
                                                                                                        try {
                                                                                                            val changed = withContext(Dispatchers.IO) {
                                                                                                                if (!pinManager.verify(currentPin)) false
                                                                                                                else pinManager.create(newPin)
                                                                                                            }
                                                                                                            if (changed) {
                                                                                                                showPinDialog = false
                                                                                                                pinMessage = "PIN changed."
                                                                                                                currentPin = ""
                                                                                                                newPin = ""
                                                                                                                confirmPin = ""
                                                                                                            } else {
                                                                                                                pinMessage = "Current PIN is incorrect."
                                                                                                            }
                                                                                                        } catch (e: CancellationException) {
                                                                                                            throw e
                                                                                                        } catch (_: Exception) {
                                                                                                            pinMessage = "Could not change the PIN. Please try again."
                                                                                                        } finally {
                                                                                                            pinSaving = false
                                                                                                        }
                                                                                                    }
                                                                                                }
                                                                                            }
                                                                                        ) { Text(if (pinSaving) "Saving…" else "Change PIN") }
                                                                                    },
                                                                                    dismissButton = {
                                                                                        TextButton(
                                                                                            enabled = !pinSaving,
                                                                                            onClick = {
                                                                                                showPinDialog = false
                                                                                                pinMessage = null
                                                                                                currentPin = ""
                                                                                                newPin = ""
                                                                                                confirmPin = ""
                                                                                            }
                                                                                        ) { Text("Cancel") }
                                                                                    }
                                                                                )
                                                                            }
                                                                            if (showHelp) {
                                                                                AlertDialog(
                                                                                    onDismissRequest = { showHelp = false },
                                                                                    title = { Text("Help and FAQ") },
                                                                                    text = {
                                                                                        Text("Add documents from the Home tab using your camera or gallery. Search works on this device. Favourite status is saved with the document metadata. Images are encrypted in private app storage; deleting your profile does not delete documents.")
                                                                                    },
                                                                                    confirmButton = {
                                                                                        TextButton(onClick = { showHelp = false }) { Text("Close") }
                                                                                    }
                                                                                )
                                                                            }
                                                                        }

                                                                        @Composable
                                                                        private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
                                                                            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                                                                                Text(title, fontWeight = FontWeight.SemiBold)
                                                                                Spacer(Modifier.height(8.dp))
                                                                                Card(
                                                                                    Modifier.fillMaxWidth(),
                                                                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                                                                ) {
                                                                                    Column(Modifier.padding(16.dp), content = content)
                                                                                }
                                                                            }
                                                                        }

                                                                        @Composable
                                                                        private fun SettingsAction(title: String, subtitle: String, onClick: () -> Unit) {
                                                                            TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                                                                                Column(Modifier.fillMaxWidth()) {
                                                                                    Text(title, color = MaterialTheme.colorScheme.onSurface)
                                                                                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                                }
                                                                            }
                                                                        }

                                                                        private fun categoryFrameColor(category: String): Color = when {
                                                                            category.contains("Aadhaar", ignoreCase = true) -> Color(0xCC1D4ED8)
                                                                            category.contains("PAN", ignoreCase = true) -> Color(0xCC102A43)
                                                                            category.contains("Driving", ignoreCase = true) -> Color(0xCC243B53)
                                                                            category.contains("Voter", ignoreCase = true) -> Color(0xCC166534)
                                                                            category.contains("Passport", ignoreCase = true) -> Color(0xCC1E3A5F)
                                                                            else -> Color(0xCC475569)
                                                                        }
                                                                        @Composable
                                                                        private fun ProfileAvatar(bytes: ByteArray?) {
                                                                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                                                Surface(Modifier.size(116.dp), shape = CircleShape, color = Color(0xFFE5ECF5)) {
                                                                                    if (bytes != null) {
                                                                                        val bitmap = rememberDecodedBitmap(bytes, 800)
                                                                                        if (bitmap != null) Image(bitmap.asImageBitmap(), "Profile image",
                                                                                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                                                    } else Icon(Icons.Default.AccountCircle, null, Modifier.fillMaxSize().padding(18.dp),
                                                                                        tint = Color(0xFF627D98))
                                                                                }
                                                                            }
                                                                        }
                                                                            @Composable
                                                                            private fun QuickShowScreen(document: DocumentEntity, repository: WalletRepository, onExit: () -> Unit) {
                                                                                var isBack by remember(document.id) { mutableStateOf(false) }
                                                                                var front by remember(document.id) { mutableStateOf<ByteArray?>(null) }
                                                                                var back by remember(document.id) { mutableStateOf<ByteArray?>(null) }
                                                                                var error by remember(document.id) { mutableStateOf<String?>(null) }
                                                                                val rotation by animateFloatAsState(if (isBack) 180f else 0f, label = "quickShowFlip")

                                                                                    BackHandler(onBack = onExit)

                                                                                LaunchedEffect(document.id) {
                                                                                    try {
                                                                                        val images = withContext(Dispatchers.IO) {
                                                                                            val f = if (document.frontFile.isNotBlank()) {
                                                                                                repository.readEncryptedImage(document.frontFile)
                                                                                            } else null
                                                                                            val b = document.backFile?.let {
                                                                                                repository.readEncryptedImage(it)
                                                                                            }
                                                                                            f to b
                                                                                        }
                                                                                        front = images.first
                                                                                        back = images.second
                                                                                    } catch (e: CancellationException) {
                                                                                        throw e
                                                                                    } catch (e: Exception) {
                                                                                        error = e.message ?: "Could not load this document."
                                                                                    }
                                                                                }

                                                                                Box(
                                                                                    Modifier.fillMaxSize().background(Color(0xFF102A43)).padding(16.dp),
                                                                                    contentAlignment = Alignment.Center
                                                                                ) {
                                                                                    Column(Modifier.fillMaxSize()) {
                                                                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                                                            Spacer(Modifier.weight(1f))
                                                                                            TextButton(onClick = onExit) { Text("Exit", color = Color.White) }
                                                                                        }
                                                                                        Spacer(Modifier.weight(1f))
                                                                                        Card(
                                                                                            Modifier.fillMaxWidth().height(260.dp).graphicsLayer {
                                                                                                rotationY = rotation
                                                                                                cameraDistance = 12 * density
                                                                                            }.clickable {
                                                                                                if (back != null) isBack = !isBack
                                                                                            },
                                                                                            shape = RoundedCornerShape(24.dp)
                                                                                        ) {
                                                                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                                                val bytes = if (isBack) back else front
                                                                                                if (bytes != null) {
                                                                                                    val bitmap = rememberDecodedBitmap(bytes, 2000)
                                                                                                    if (bitmap != null) {
                                                                                                        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                                                                                                            if (isBack) rotationY = 180f
                                                                                                        }, contentScale = ContentScale.Fit)
                                                                                                    }
                                                                                                } else Text("Back side not added")
                                                                                            }
                                                                                        }
                                                                                        Spacer(Modifier.weight(1f))
                                                                                        Text(
                                                                                            if (isBack) "Back side" else "Front side",
                                                                                            Modifier.align(Alignment.CenterHorizontally),
                                                                                            color = Color.White
                                                                                        )
                                                                                        error?.let { Text(it, color = Color.White) }
                                                                                    }
                                                                                }
                                                                            }
