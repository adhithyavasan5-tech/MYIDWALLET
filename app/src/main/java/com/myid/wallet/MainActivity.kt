package com.myid.wallet

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.myid.wallet.util.ExtractedDocumentMetadata
import com.myid.wallet.util.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

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
    val scope = rememberCoroutineScope()
    val documents by repository.documents.collectAsState(initial = emptyList())
    val account by repository.account.collectAsState(initial = null)
    var unlocked by remember { mutableStateOf(false) }
    var setupMode by remember { mutableStateOf(!pinManager.isConfigured()) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var dark by remember { mutableStateOf(false) }
    var nav by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<DocumentEntity?>(null) }
    var editing by remember { mutableStateOf<DocumentEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var quickShow by remember { mutableStateOf<DocumentEntity?>(null) }

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
                else -> Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(selected = nav == 0, onClick = { nav = 0 },
                                icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                            NavigationBarItem(selected = nav == 1, onClick = { nav = 1 },
                                icon = { Icon(Icons.Default.Add, null) }, label = { Text("Add") })
                            NavigationBarItem(selected = nav == 2, onClick = { nav = 2 },
                                icon = { Icon(Icons.Default.AccountCircle, null) }, label = { Text("Account") })
                        }
                    }) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (nav) {
                            0 -> HomeScreen(documents, repository, onOpen = { selected = it },
                                onAdd = { editing = null; showAdd = true }, dark = dark, onDark = { dark = it })
                            1 -> AddDocumentScreen(existing = null, repository = repository,
                                onCancel = { nav = 0 }, onSaved = { nav = 0 })
                            2 -> AccountScreen(account, repository)
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
                        onDark: (Boolean) -> Unit
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
                            Spacer(Modifier.height(18.dp))
                            if (documents.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Add, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(64.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("Your wallet is empty", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Add your first identity card", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = "Open ${doc.title.ifBlank { doc.category }}",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                    modifier = Modifier.padding(end = 14.dp).size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                                @Composable
                                private fun EncryptedImage(repository: WalletRepository, fileName: String) {
                                    var bitmap by remember(fileName) { mutableStateOf<Bitmap?>(null) }
                                    LaunchedEffect(fileName) {
                                        if (fileName.isNotBlank()) {
                                            withContext(Dispatchers.IO) {
                                                val bytes = repository.readEncryptedImage(fileName)
                                                val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                withContext(Dispatchers.Main) { bitmap = result }
                                            }
                                        }
                                                }
                                    if (bitmap != null) {
                                        Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
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
                                            var cropSide by remember { mutableStateOf<String?>(null) }
                                            var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
                                            var cropCorners by remember { mutableStateOf(CropCorners()) }

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
                                                                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
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
                                                        onCancel: () -> Unit,
                                                        onDone: (Bitmap) -> Unit
                                                    ) {
                                                        val state = remember(corners) { mutableStateOf(corners) }
                                                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                TextButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, null); Text("Cancel") }
                                                                Spacer(Modifier.weight(1f))
                                                                Text("Adjust card edges", fontWeight = FontWeight.Bold)
                                                            }
                                                            Spacer(Modifier.height(12.dp))
                                                            Box(
                                                                Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {},
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Image(bitmap.asImageBitmap(), "Crop preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                                                Canvas(
                                                                    Modifier.fillMaxSize().pointerInput(Unit) {
                                                                        detectDragGestures { change, drag ->
                                                                            change.consume()
                                                                            val c = state.value
                                                                            val points = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
                                                                            val pos = change.position
                                                                            val size = this.size
                                                                            var nearest = 0
                                                                            var best = Float.MAX_VALUE
                                                                            points.forEachIndexed { index, point ->
                                                                                val dx = pos.x - point.x * size.width
                                                                                val dy = pos.y - point.y * size.height
                                                                                val distance = dx * dx + dy * dy
                                                                                if (distance < best) { best = distance; nearest = index }
                                                                            }
                                                                            val dx = drag.x / size.width
                                                                            val dy = drag.y / size.height
                                                                            when (nearest) {
                                                                                0 -> c.topLeft.offset(dx, dy)
                                                                                1 -> c.topRight.offset(dx, dy)
                                                                                2 -> c.bottomRight.offset(dx, dy)
                                                                                3 -> c.bottomLeft.offset(dx, dy)
                                                                            }
                                                                            state.value = c
                                                                        }
                                                                    }
                                                                ) {
                                                                    val c = state.value
                                                                    val points = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
                                                                    val px = points.map { Offset(it.x * size.width, it.y * size.height) }
                                                                    val path = Path().apply {
                                                                        moveTo(px[0].x, px[0].y)
                                                                        px.drop(1).forEach { lineTo(it.x, it.y) }
                                                                        close()
                                                                    }
                                                                    drawPath(path, Color(0xFF3B82F6).copy(alpha = .20f))
                                                                    drawPath(path, Color(0xFF3B82F6), style = Stroke(width = 4f))
                                                                    px.forEach { drawCircle(Color.White, 16f); drawCircle(Color(0xFF3B82F6), 11f) }
                                                                }
                                                            }
                                                            Spacer(Modifier.height(12.dp))
                                                            Text("Drag the blue handles to correct the card boundaries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                            Spacer(Modifier.height(12.dp))
                                                            Button(onClick = { onDone(CardImageProcessor.warp(bitmap, state.value)) },
                                                                modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Use Cropped Card") }
                                                        }
                                                    }

                                            var pendingSide by remember { mutableStateOf("front") }
                                            var cameraUri by remember { mutableStateOf<Uri?>(null) }
                                            val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                                                if (success) {
                                                    cameraUri?.let { uri ->
                                                        val bitmap = CardImageProcessor.decode(context, uri)
                                                        if (bitmap != null) {
                                                            cropBitmap = bitmap
                                                            cropCorners = CardImageProcessor.detectCorners(bitmap)
                                                            cropSide = pendingSide
                                                        } else error = "Could not read captured image."
                                                    }
                                                }
                                            }
                                            val cameraPermission = rememberLauncherForActivityResult(
                                                ActivityResultContracts.RequestPermission()
                                            ) { granted ->
                                                if (granted) {
                                                    val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                    cameraUri = uri
                                                    cameraLauncher.launch(uri)
                                                } else {
                                                    error = "Camera permission is required to capture an image."
                                                }
                                            }
                                            val frontGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                                if (uri != null) {
                                                    val bitmap = CardImageProcessor.decode(context, uri)
                                                    if (bitmap != null) {
                                                        cropBitmap = bitmap
                                                        cropCorners = CardImageProcessor.detectCorners(bitmap)
                                                        cropSide = "front"
                                                    } else error = "Could not read selected image."
                                                }
                                            }
                                            val backGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                                if (uri != null) {
                                                    val bitmap = CardImageProcessor.decode(context, uri)
                                                    if (bitmap != null) {
                                                        cropBitmap = bitmap
                                                        cropCorners = CardImageProcessor.detectCorners(bitmap)
                                                        cropSide = "back"
                                                    } else error = "Could not read selected image."
                                                }
                                            }

                                            fun startCamera(side: String) {
                                                pendingSide = side
                                                cameraPermission.launch(android.Manifest.permission.CAMERA)
                                            }

                                            if (cropBitmap != null && cropSide != null) {
                                                CropEditorScreen(
                                                    bitmap = cropBitmap!!,
                                                    corners = cropCorners,
                                                    onCancel = { cropBitmap = null; cropSide = null },
                                                    onDone = { cropped ->
                                                        val stream = ByteArrayOutputStream()
                                                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                                                        if (cropSide == "front") {
                                                            frontBytes = stream.toByteArray()
                                                        } else {
                                                            backBytes = stream.toByteArray()
                                                            removeBack = false
                                                        }
                                                        scope.launch {
                                                            val result = OcrExtractor.extract(cropped)
                                                            ocrNumber = result.documentNumber ?: ""
                                                            ocrName = result.holderName ?: ""
                                                            ocrDob = result.dateOfBirth ?: ""
                                                            ocrExpiry = result.expiryDate ?: ""
                                                            showOcrReview = true
                                                            cropped.recycle()
                                                        }
                                                        cropBitmap = null
                                                        cropSide = null
                                                    })
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

                                                                        LaunchedEffect(document.id) {
                                                                            withContext(Dispatchers.IO) {
                                                                                val f = if (document.frontFile.isNotBlank()) repository.readEncryptedImage(document.frontFile) else null
                                                                                val b = document.backFile?.let { repository.readEncryptedImage(it) }
                                                                                withContext(Dispatchers.Main) { front = f; back = b }
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
                                                                                        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
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
                                                                            var editing by remember { mutableStateOf(false) }
                                                                            var deleteConfirm by remember { mutableStateOf(false) }
                                                                            var name by remember(account) { mutableStateOf(account?.name ?: "") }
                                                                            var age by remember(account) { mutableStateOf(account?.age?.toString() ?: "") }
                                                                            var mobile by remember(account) { mutableStateOf(account?.mobile ?: "") }
                                                                            var profileBytes by remember(account) { mutableStateOf<ByteArray?>(null) }
                                                                            var removeProfile by remember(account) { mutableStateOf(false) }
                                                                            var error by remember { mutableStateOf<String?>(null) }

                                                                            LaunchedEffect(account?.profileFile) {
                                                                                account?.profileFile?.let { file ->
                                                                                    withContext(Dispatchers.IO) {
                                                                                        val bytes = repository.readEncryptedImage(file)
                                                                                        withContext(Dispatchers.Main) { profileBytes = bytes }
                                                                                    }
                                                                                } ?: run { profileBytes = null }
                                                                            }

                                                                            val profileGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                                                                if (uri != null) {
                                                                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                                                                        val bytes = input.readBytes()
                                                                                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                                                        if (bitmap != null) {
                                                                                            val stream = ByteArrayOutputStream()
                                                                                            val scaled = Bitmap.createScaledBitmap(bitmap,
                                                                                                minOf(800, bitmap.width), minOf(800, bitmap.height), true)
                                                                                            scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                                                                                            profileBytes = stream.toByteArray()
                                                                                            removeProfile = false
                                                                                            scaled.recycle()
                                                                                            bitmap.recycle()
                                                                                        } else error = "Could not read profile image."
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
                                                                                            } catch (e: Exception) { error = e.message ?: "Could not save profile." }
                                                                                        }
                                                                                    }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Save Profile") }
                                                                                }
                                                                            } else {
                                                                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                                                                    Spacer(Modifier.height(18.dp))
                                                                                    Text("Account", fontSize = 27.sp, fontWeight = FontWeight.Bold)
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
                                                                                        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
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
                                                                                val rotation by animateFloatAsState(if (isBack) 180f else 0f, label = "quickShowFlip")

                                                                                LaunchedEffect(document.id) {
                                                                                    withContext(Dispatchers.IO) {
                                                                                        val f = if (document.frontFile.isNotBlank()) repository.readEncryptedImage(document.frontFile) else null
                                                                                        val b = document.backFile?.let { repository.readEncryptedImage(it) }
                                                                                        withContext(Dispatchers.Main) { front = f; back = b }
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
                                                                                                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
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
                                                                                    }
                                                                                }
                                                                            }
