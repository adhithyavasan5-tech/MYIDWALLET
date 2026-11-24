package com.myid.wallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.myid.wallet.data.AppDatabase
import com.myid.wallet.data.DocumentEntity
import com.myid.wallet.data.WalletRepository
import com.myid.wallet.security.EncryptionManager
import com.myid.wallet.security.PinManager
import com.myid.wallet.ui.theme.MyIDTheme
import com.myid.wallet.util.PdfExporter
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer

class MainActivity : ComponentActivity() {
    private lateinit var repository: WalletRepository
    private lateinit var pinManager: PinManager
    private val crypto = EncryptionManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        repository = WalletRepository(this, db.documentDao(), crypto)
        pinManager = PinManager(this)

        setContent {
            MyIDTheme {
                WalletRoot(
                    repository = repository,
                    pinManager = pinManager,
                    onShare = { doc, sides -> sharePdf(doc, sides) },
                    onSave = { doc, sides -> savePdf(doc, sides) }
                )
            }
        }
    }

    private fun collectImages(doc: DocumentEntity, sides: String): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        lifecycleScope.launch { }
        return result
    }

    private fun sharePdf(doc: DocumentEntity, sides: List<ByteArray>) {
        try {
            val file = PdfExporter.createPdf(this, sides, doc.title)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${doc.title}"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, e.message ?: "Could not share PDF", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun savePdf(doc: DocumentEntity, sides: List<ByteArray>) {
        try {
            val file = PdfExporter.createPdf(this, sides, doc.title)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads file.")
            contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            file.delete()
            android.widget.Toast.makeText(this, "PDF saved to Downloads", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, e.message ?: "Could not save PDF", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WalletRoot(
    repository: WalletRepository,
    pinManager: PinManager,
    onShare: (DocumentEntity, List<ByteArray>) -> Unit,
    onSave: (DocumentEntity, List<ByteArray>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documents by repository.documents.collectAsState(initial = emptyList())
    var unlocked by remember { mutableStateOf(false) }
    var setupMode by remember { mutableStateOf(!pinManager.isConfigured()) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<DocumentEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Aadhaar") }
    var pendingBack by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    repository.importDocument(uri, title.ifBlank { category }, category, pendingBack)
                    title = ""
                    showAdd = false
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: "Import failed"
                }
            }
        }
    }

    MyIDTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!unlocked) {
                PinScreen(
                    setup = setupMode,
                    pin = pin,
                    confirmPin = confirmPin,
                    error = error,
                    onPin = { if (it.length <= 12) pin = it },
                    onConfirm = { if (it.length <= 12) confirmPin = it },
                    onSubmit = {
                        if (setupMode) {
                            if (pin != confirmPin) error = "PINs do not match."
                            else if (!pinManager.create(pin)) error = "Use at least 8 digits."
                            else { unlocked = true; error = null }
                        } else {
                            if (pinManager.verify(pin)) { unlocked = true; error = null }
                            else error = "Incorrect PIN."
                        }
                        pin = ""
                        confirmPin = ""
                    }
                )
            } else if (selected != null) {
                val doc = selected!!
                CardDetailScreen(
                    document = doc,
                    repository = repository,
                    onBack = { selected = null },
                    onFavourite = { scope.launch { repository.favourite(doc) } },
                    onSave = { bytes -> onSave(doc, bytes) },
                    onShare = { bytes -> onShare(doc, bytes) }
                )
            } else if (showAdd) {
                AddDocumentScreen(
                    title = title,
                    category = category,
                    pendingBack = pendingBack,
                    error = error,
                    onTitle = { title = it },
                    onCategory = { category = it },
                    onBackSide = { pendingBack = it },
                    onPick = { picker.launch(arrayOf("image/*")) },
                    onCancel = { showAdd = false; error = null }
                )
            } else {
                HomeScreen(
                    documents = documents,
                    dark = dark,
                    onDark = { dark = it },
                    onAdd = { showAdd = true; pendingBack = false },
                    onOpen = { selected = it },
                    onFavourite = { doc -> scope.launch { repository.favourite(doc) } }
                )
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
        Text("MyID Wallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF102B50))
        Spacer(Modifier.height(8.dp))
        Text(if (setup) "Create your wallet PIN" else "Enter your PIN")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin, onValueChange = onPin,
            label = { Text("8-digit PIN") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        if (setup) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirmPin, onValueChange = onConfirm,
                label = { Text("Confirm PIN") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) {
            Text(if (setup) "Create Wallet" else "Unlock Wallet")
        }
    }
}

@Composable
private fun HomeScreen(
    documents: List<DocumentEntity>,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onOpen: (DocumentEntity) -> Unit,
    onFavourite: (DocumentEntity) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MyID Wallet", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Your identity. Your cards.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onDark(!dark) }) { Text(if (dark) "Light" else "Dark") }
        }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF102B50))) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("YOUR DIGITAL WALLET", color = Color.White.copy(alpha = .75f), fontSize = 11.sp)
                Text("${documents.size} cards", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Stored on this device", color = Color.White.copy(alpha = .8f))
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Cards", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Icon(Icons.Default.Add, null); Text(" Add") }
        }
        Spacer(Modifier.height(12.dp))
        if (documents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
                    Text("Your wallet is empty", fontWeight = FontWeight.SemiBold)
                    Text("Add your first identity card.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAdd) { Text("Add a card") }
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(documents, key = { it.id }) { doc ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(doc) },
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(doc.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(doc.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onFavourite(doc) }) {
                                Icon(if (doc.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDocumentScreen(
    title: String,
    category: String,
    pendingBack: Boolean,
    error: String?,
    onTitle: (String) -> Unit,
    onCategory: (String) -> Unit,
    onBackSide: (Boolean) -> Unit,
    onPick: () -> Unit,
    onCancel: () -> Unit
) {
    val categories = listOf("Aadhaar", "PAN Card", "Driving Licence", "Voter ID", "Passport", "Vehicle RC", "College ID", "Employee ID", "Insurance", "Other")
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, null); Text(" Back") }
        Text("Add a card", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(title, onTitle, label = { Text("Card title (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("Document type")
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = category, onValueChange = {}, readOnly = true,
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                label = { Text("Category") }
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                categories.forEach { item ->
                    DropdownMenuItem(text = { Text(item) }, onClick = { onCategory(item); expanded = false })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Select which side you are uploading")
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !pendingBack, onClick = { onBackSide(false) }, label = { Text("Front") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = pendingBack, onClick = { onBackSide(true) }, label = { Text("Back") })
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose image") }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text("Tip: Add the front first, then open the saved card and use the edit/upload flow to add its back. This starter currently imports a new front or back record; full side replacement UI is a next implementation step.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun CardDetailScreen(
    document: DocumentEntity,
    repository: WalletRepository,
    onBack: () -> Unit,
    onFavourite: () -> Unit,
    onSave: (List<ByteArray>) -> Unit,
    onShare: (List<ByteArray>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isBack by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(true) }
    var front by remember { mutableStateOf<ByteArray?>(null) }
    var back by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val rotation by animateFloatAsState(if (isBack) 180f else 0f, label = "cardFlip")

    LaunchedEffect(document.id) {
        try {
            if (document.frontFile.isNotBlank()) front = repository.readEncryptedImage(document.frontFile)
            document.backFile?.let { back = repository.readEncryptedImage(it) }
        } catch (e: Exception) { error = e.message }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text(" Back") }
        Text(document.title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().height(250.dp)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12 * density
                }
                .background(Color(0xFF102B50), RoundedCornerShape(22.dp))
                .clickable { isBack = !isBack }
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            val bytes = if (isBack) back else front
            if (bytes != null) {
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (isBack) "Back of ${document.title}" else "Front of ${document.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
            } else {
                Text(if (isBack) "Back side not added" else "Tap to flip card", color = Color.White)
            }
            if (isBack) {
                // A 180-degree parent rotation also flips text; rotate the backside content
                // so that the image is readable after the card turns.
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f })
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (isBack) "BACK • Tap card to flip" else "FRONT • Tap card to flip", modifier = Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide details", modifier = Modifier.weight(1f))
            Switch(checked = !showDetails, onCheckedChange = { showDetails = !it })
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val selected = buildList {
                    if (!isBack) front?.let { add(it) } else back?.let { add(it) }
                }
                if (selected.isNotEmpty()) onSave(selected) else error = "No image available for this side."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Icon(Icons.Default.Download, null); Text(" SAVE AS PDF") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val selected = buildList {
                    if (front != null) add(front!!)
                    if (back != null) add(back!!)
                }
                if (selected.isNotEmpty()) onShare(selected) else error = "No images available."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Icon(Icons.Default.Share, null); Text(" SHARE") }
        TextButton(onClick = onFavourite, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Add / remove favourite")
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    }
}
