package app.archivepocket

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.util.Size as PixelSize
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.archivepocket.core.ArchivePreview
import app.archivepocket.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshots stay allowed so users can report UI issues; passwords are always masked and never saved.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { PocketApp() }
    }
}

// ---------------------------------------------------------------- palette & formatting
private val Indigo = Color(0xFF3949AB)
private val IndigoDeep = Color(0xFF283593)
private val IndigoLight = Color(0xFF5C6BC0)
private val FolderBlue = Color(0xFF4FA3F7)
private val FolderBlueDark = Color(0xFF1E88E5)
private val UpGreen = Color(0xFF43A047)

private fun fileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun storageSize(bytes: Long): String {
    val gb = bytes / (1000.0 * 1000 * 1000)
    return if (gb >= 100) "%.0f GB".format(gb) else "%.2f GB".format(gb)
}

// One shared formatter: creating a DateFormat per list row is slow and caused scroll jank.
private val DATE_FORMAT: DateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
private fun dateText(millis: Long): String = if (millis > 0) DATE_FORMAT.format(Date(millis)) else ""

private val SORT_NAMES = listOf("Name", "Size", "Date", "Type")

// ---------------------------------------------------------------- file kinds & MIME
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar", "zipx")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "opus")
private val DOC_EXT = setOf("doc", "docx", "odt", "rtf")
private val SHEET_EXT = setOf("xls", "xlsx", "ods", "csv")
private val SLIDE_EXT = setOf("ppt", "pptx", "odp")
private val TEXT_EXT = setOf("txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py", "log")

private enum class FileKind(val label: String, val color: Color) {
    IMAGE("IMG", Color(0xFF00897B)), VIDEO("VID", Color(0xFF7C4DFF)), AUDIO("MP3", Color(0xFFD81B60)),
    PDF("PDF", Color(0xFFE53935)), DOC("DOC", Color(0xFF1E88E5)), SHEET("XLS", Color(0xFF2E7D32)),
    SLIDE("PPT", Color(0xFFEF6C00)), APK("APK", Color(0xFF43A047)), ARCHIVE("ZIP", Color(0xFF8D6E63)),
    TEXT("TXT", Color(0xFF546E7A)), OTHER("FILE", Color(0xFF78909C))
}

private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
private fun isArchive(name: String): Boolean = extension(name) in ARCHIVE_EXTENSIONS
private fun isPreviewableArchive(name: String): Boolean = extension(name) in setOf("zip", "zipx", "jar", "rar")
private fun fileKind(name: String): FileKind = when (val ext = extension(name)) {
    in IMAGE_EXT -> FileKind.IMAGE
    in VIDEO_EXT -> FileKind.VIDEO
    in AUDIO_EXT -> FileKind.AUDIO
    "pdf" -> FileKind.PDF
    in DOC_EXT -> FileKind.DOC
    in SHEET_EXT -> FileKind.SHEET
    in SLIDE_EXT -> FileKind.SLIDE
    "apk", "apks", "xapk" -> FileKind.APK
    in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
    in TEXT_EXT -> FileKind.TEXT
    else -> FileKind.OTHER
}

private fun mimeType(name: String): String = when (val ext = extension(name)) {
    "apk" -> "application/vnd.android.package-archive"
    "apks", "xapk" -> "application/zip"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "mkv" -> "video/x-matroska"
    "md", "log", "kt", "py" -> "text/plain"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

private fun launchFile(context: Context, file: File, report: (String) -> Unit) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(context, file), mimeType(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
    } catch (_: ActivityNotFoundException) {
        report("No app is installed that can open this file type.")
    } catch (_: Exception) {
        report("This file could not be opened. Check storage access and file integrity.")
    }
}

private fun shareFiles(context: Context, files: List<File>, report: (String) -> Unit) {
    val plain = files.filter { it.isFile }
    if (plain.isEmpty()) { report("Select one or more files (not folders) to share."); return }
    try {
        val uris = ArrayList<Uri>(plain.map { contentUri(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeType(plain[0].name)).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share ${plain.size} file(s)"))
    } catch (_: Exception) {
        report("Sharing failed. No app accepted the selected files.")
    }
}

private fun copyText(context: Context, label: String, text: String, report: (String) -> Unit) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clipboard == null) { report("Clipboard is not available."); return }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    report("Copied to clipboard:\n$text")
}

// ---------------------------------------------------------------- thumbnails
/** Small in-memory thumbnail cache so scrolling back never re-decodes an image. */
private val THUMBS = LruCache<String, ImageBitmap>(96)

private fun decodeThumbnail(context: Context, file: File, kind: FileKind): ImageBitmap? = runCatching {
    when (kind) {
        FileKind.IMAGE -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
        FileKind.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, PixelSize(256, 256), null).asImageBitmap()
        } else null
        FileKind.APK -> {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.path, 0)?.applicationInfo ?: return@runCatching null
            info.sourceDir = file.path
            info.publicSourceDir = file.path
            info.loadIcon(pm).toBitmap(192, 192).asImageBitmap()
        }
        else -> null
    }
}.getOrNull()

@Composable
private fun rememberThumbnail(file: File, modified: Long, kind: FileKind): ImageBitmap? {
    val context = LocalContext.current
    val key = file.path + "@" + modified
    // Plain state + LaunchedEffect (not produceState) so the Compose Lint rule ProduceStateDoesNotAssignValue cannot misfire.
    var bitmap by remember(key) { mutableStateOf(THUMBS.get(key)) }
    LaunchedEffect(key) {
        if (bitmap == null) {
            // Decode off the main thread once; the LruCache makes scrolling back instant.
            bitmap = withContext(Dispatchers.IO) { decodeThumbnail(context, file, kind) }?.also { THUMBS.put(key, it) }
        }
    }
    return bitmap
}

// ---------------------------------------------------------------- icons
@Composable
private fun FolderGlyph(size: Dp, up: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(FolderBlueDark, Offset(0f, h * 0.16f), Size(w * 0.46f, h * 0.2f), CornerRadius(w * 0.08f))
        drawRoundRect(Brush.verticalGradient(listOf(FolderBlue, FolderBlueDark)), Offset(0f, h * 0.26f), Size(w, h * 0.6f), CornerRadius(w * 0.08f))
        drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(w * 0.06f, h * 0.3f), Size(w * 0.88f, h * 0.08f), CornerRadius(w * 0.04f))
        if (up) {
            val stroke = w * 0.13f
            drawLine(UpGreen, Offset(w * 0.7f, h * 0.8f), Offset(w * 0.7f, h * 0.3f), stroke, StrokeCap.Round)
            drawLine(UpGreen, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.7f, h * 0.3f), stroke, StrokeCap.Round)
            drawLine(UpGreen, Offset(w * 0.9f, h * 0.5f), Offset(w * 0.7f, h * 0.3f), stroke, StrokeCap.Round)
        }
    }
}

/** Colourful "stacked books with a strap" glyph used for archives, like classic desktop archivers. */
@Composable
private fun ArchiveGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(Color(0xFFFDD835), Offset(w * 0.04f, h * 0.1f), Size(w * 0.26f, h * 0.8f), CornerRadius(w * 0.05f))
        drawRoundRect(Color(0xFFE53935), Offset(w * 0.32f, h * 0.06f), Size(w * 0.34f, h * 0.88f), CornerRadius(w * 0.05f))
        drawRoundRect(Color(0xFF1E88E5), Offset(w * 0.68f, h * 0.1f), Size(w * 0.28f, h * 0.8f), CornerRadius(w * 0.05f))
        drawRect(Color(0xFF43A047), Offset(w * 0.04f, h * 0.72f), Size(w * 0.26f, h * 0.18f))
        drawRect(Color(0xFF7E57C2), Offset(w * 0.68f, h * 0.1f), Size(w * 0.28f, h * 0.16f))
        drawRect(Color(0xFF8D6E63), Offset(0f, h * 0.36f), Size(w, h * 0.26f))
        drawRect(Color(0xFF5D4037), Offset(0f, h * 0.36f), Size(w, h * 0.04f))
        drawRect(Color(0xFF5D4037), Offset(0f, h * 0.58f), Size(w, h * 0.04f))
        drawRoundRect(Color(0xFFECEFF1), Offset(w * 0.38f, h * 0.32f), Size(w * 0.24f, h * 0.34f), CornerRadius(w * 0.05f))
        drawRect(Color(0xFF8D6E63), Offset(w * 0.44f, h * 0.4f), Size(w * 0.12f, h * 0.18f))
    }
}

/** A coloured page with folded corner and a type label (PDF, DOC, APK, ...). */
@Composable
private fun TypeBadge(kind: FileKind, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            val page = Path().apply {
                moveTo(w * 0.16f, h * 0.04f); lineTo(w * 0.6f, h * 0.04f); lineTo(w * 0.84f, h * 0.28f)
                lineTo(w * 0.84f, h * 0.96f); lineTo(w * 0.16f, h * 0.96f); close()
            }
            drawPath(page, Brush.verticalGradient(listOf(kind.color.copy(alpha = 0.92f), kind.color)))
            val fold = Path().apply { moveTo(w * 0.6f, h * 0.04f); lineTo(w * 0.6f, h * 0.28f); lineTo(w * 0.84f, h * 0.28f); close() }
            drawPath(fold, Color.White.copy(alpha = 0.5f))
            drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(w * 0.1f, h * 0.56f), Size(w * 0.8f, h * 0.28f), CornerRadius(w * 0.06f))
            if (kind == FileKind.AUDIO) {
                drawCircle(Color.White.copy(alpha = 0.9f), w * 0.07f, Offset(w * 0.4f, h * 0.44f))
                drawLine(Color.White.copy(alpha = 0.9f), Offset(w * 0.46f, h * 0.44f), Offset(w * 0.46f, h * 0.2f), w * 0.04f, StrokeCap.Round)
            }
        }
        Text(kind.label, Modifier.offset(y = size * 0.2f), color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = if (kind.label.length > 3) 8.sp else 10.sp, maxLines = 1)
    }
}

/** Thumbnail (image / video frame / APK icon) when available, otherwise a type-specific glyph. */
@Composable
private fun FileVisual(name: String, directory: Boolean, file: File? = null, modified: Long = 0, up: Boolean = false, size: Dp = 46.dp, radius: Dp = 10.dp) {
    if (directory) { FolderGlyph(size, up); return }
    val kind = fileKind(name)
    if (kind == FileKind.ARCHIVE) { ArchiveGlyph(size); return }
    val thumbnail = if (file != null && (kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.APK)) rememberThumbnail(file, modified, kind) else null
    if (thumbnail == null) { TypeBadge(kind, size); return }
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Image(thumbnail, name, Modifier.fillMaxSize(), contentScale = if (kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop)
        if (kind == FileKind.VIDEO) Box(Modifier.size(size * 0.46f).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(size * 0.34f))
        }
    }
}

@Composable
private fun GridGlyph(grid: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = this.size.width; val h = this.size.height
        if (grid) {
            for (x in 0..1) for (y in 0..1) drawRoundRect(tint, Offset(w * (0.06f + x * 0.5f), h * (0.06f + y * 0.5f)), Size(w * 0.38f, h * 0.38f), CornerRadius(w * 0.08f))
        } else {
            for (i in 0..2) {
                drawRoundRect(tint, Offset(w * 0.06f, h * (0.1f + i * 0.3f)), Size(w * 0.18f, h * 0.18f), CornerRadius(w * 0.05f))
                drawRoundRect(tint, Offset(w * 0.34f, h * (0.13f + i * 0.3f)), Size(w * 0.6f, h * 0.12f), CornerRadius(w * 0.05f))
            }
        }
    }
}

/** Toolbar glyph: an archive with a "+" (create) or an up arrow (extract) badge, drawn without extra icon dependencies. */
@Composable
private fun ArchiveIcon(extract: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f)
    IconButton(onClick = onClick, enabled = enabled) {
        Canvas(Modifier.size(26.dp).semantics { contentDescription = description }) {
            val w = size.width; val h = size.height
            drawRoundRect(tint, Offset(0f, h * 0.08f), Size(w * 0.66f, h * 0.84f), CornerRadius(w * 0.08f))
            for (i in 0..3) drawRect(Indigo, Offset(w * 0.23f, h * (0.2f + i * 0.16f)), Size(w * 0.2f, h * 0.07f))
            val c = Offset(w * 0.74f, h * 0.72f); val r = w * 0.25f
            drawCircle(tint, r, c)
            val stroke = w * 0.09f
            if (extract) {
                drawLine(Indigo, Offset(c.x, c.y + r * 0.5f), Offset(c.x, c.y - r * 0.5f), stroke, StrokeCap.Round)
                drawLine(Indigo, Offset(c.x - r * 0.45f, c.y - r * 0.05f), Offset(c.x, c.y - r * 0.5f), stroke, StrokeCap.Round)
                drawLine(Indigo, Offset(c.x + r * 0.45f, c.y - r * 0.05f), Offset(c.x, c.y - r * 0.5f), stroke, StrokeCap.Round)
            } else {
                drawLine(Indigo, Offset(c.x, c.y + r * 0.5f), Offset(c.x, c.y - r * 0.5f), stroke, StrokeCap.Round)
                drawLine(Indigo, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.5f, c.y), stroke, StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------- quick access
private val QUICK_FOLDERS = listOf(
    "Download" to Environment.DIRECTORY_DOWNLOADS, "DCIM (Camera)" to Environment.DIRECTORY_DCIM,
    "Pictures" to Environment.DIRECTORY_PICTURES, "Movies" to Environment.DIRECTORY_MOVIES,
    "Music" to Environment.DIRECTORY_MUSIC, "Documents" to Environment.DIRECTORY_DOCUMENTS
)

/** Tap = open/select, long-press = context menu. Ripple only; no forced haptics (they felt like stutter). */
private fun Modifier.entryGestures(enabled: Boolean, selection: Set<String>, interaction: MutableInteractionSource, onTap: () -> Unit, onLongPress: () -> Unit): Modifier =
    this.indication(interaction, ripple()).pointerInput(enabled, selection) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onPress = { offset ->
                val press = PressInteraction.Press(offset)
                interaction.emit(press)
                interaction.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            },
            onTap = { onTap() },
            onLongPress = { onLongPress() }
        )
    }

// ---------------------------------------------------------------- app
@Composable
fun PocketApp(model: PocketViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var theme by rememberSaveable { mutableStateOf(0) }
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF9FA8DA), secondary = Color(0xFF90CAF9), background = Color(0xFF111318), surface = Color(0xFF111318),
        surfaceVariant = Color(0xFF1C1F27), secondaryContainer = Color(0xFF283047), onSecondaryContainer = Color(0xFFDCE1FF)
    ) else lightColorScheme(
        primary = Indigo, secondary = FolderBlueDark, background = Color(0xFFF4F5FB), surface = Color(0xFFF4F5FB),
        surfaceVariant = Color(0xFFE8EAF6), secondaryContainer = Color(0xFFE3E7FF), onSecondaryContainer = IndigoDeep
    )
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Newest downloads first by default, so recently downloaded files are easy to find.
    var sort by rememberSaveable { mutableStateOf(2) }
    var descending by rememberSaveable { mutableStateOf(true) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }
    var contextPath by remember { mutableStateOf<String?>(null) } // entry whose long-press menu is open

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.checkAccess() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.checkAccess() }
    val requestAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    LaunchedEffect(state.fileToOpen) {
        state.fileToOpen?.let { file -> launchFile(context, file, model::message); model.consumeOpenedFile() }
    }

    val selected = state.entries.filter { it.path in state.selected }
    val selecting = state.selected.isNotEmpty()
    val enabled = !state.busy && !state.loading && !state.previewing
    val hasFolder = state.folders.isNotEmpty()
    val atRoot = state.folders.size <= 1
    val visible = remember(state.entries, query, sort, descending, showHidden) {
        val comparator = when (sort) {
            1 -> compareBy<Entry> { it.size }
            2 -> compareBy<Entry> { it.modified }
            3 -> compareBy<Entry>({ extension(it.name) }, { it.name.lowercase(Locale.ROOT) })
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: Entry -> entry.name }
        }
        val ordered = if (descending) comparator.reversed() else comparator
        state.entries.filter { (showHidden || !it.name.startsWith(".")) && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
            .sortedWith(compareByDescending<Entry> { it.directory }.then(ordered))
    }
    val folderCount = remember(visible) { visible.count { it.directory } }
    val fileCount = visible.size - folderCount
    BackHandler(enabled = enabled && (selecting || searching || !atRoot)) {
        when {
            selecting -> model.clearSelection()
            searching -> { searching = false; query = "" }
            else -> model.back()
        }
    }
    val openEntry: (Entry) -> Unit = { entry ->
        when {
            entry.directory && !selecting -> model.enter(entry)
            isPreviewableArchive(entry.name) && !selecting -> model.preview(entry)
            !selecting -> launchFile(context, entry.file, model::message)
            else -> model.select(entry)
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Column(Modifier.fillMaxSize().background(scheme.background)) {
            // ---- Top toolbar: browse mode / selection mode ----
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(if (selecting) IndigoDeep else Indigo, if (selecting) Indigo else IndigoLight)))) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        IconButton(onClick = model::clearSelection) { Icon(Icons.Filled.Close, "Clear selection", tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text("${state.selected.size} selected", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(fileSize(selected.sumOf { it.size }) + if (selected.any { it.directory }) " + folders" else "", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { shareFiles(context, selected.map { it.file }, model::message) }, enabled = enabled && selected.none { it.directory }) {
                            Icon(Icons.Filled.Share, "Share", tint = Color.White.copy(alpha = if (enabled && selected.none { it.directory }) 1f else 0.4f))
                        }
                        ArchiveIcon(extract = false, enabled = enabled, description = "Create ZIP") { dialog = "zip" }
                        ArchiveIcon(extract = true, enabled = enabled && selected.size == 1 && isArchive(selected[0].name), description = "Extract") { dialog = "extract" }
                        IconButton(onClick = { dialog = "delete" }, enabled = enabled) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                    } else {
                        Box {
                            IconButton(onClick = { quick = true }) { Icon(Icons.Filled.Menu, "Quick access", tint = Color.White) }
                            DropdownMenu(expanded = quick, onDismissRequest = { quick = false }) {
                                Text("Quick access", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                DropdownMenuItem(text = { Text("Internal storage") }, leadingIcon = { Icon(Icons.Filled.Home, null) }, enabled = enabled,
                                    onClick = { quick = false; model.open(model.root) })
                                QUICK_FOLDERS.forEach { (label, directory) ->
                                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { FolderGlyph(22.dp) }, enabled = enabled,
                                        onClick = { quick = false; model.open(Environment.getExternalStoragePublicDirectory(directory)) })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Alal Zip", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(if (atRoot || !hasFolder) "Internal storage" else state.folders.last().name, color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search", tint = Color.White)
                        }
                        IconButton(onClick = { grid = !grid }) { GridGlyph(grid = !grid, tint = Color.White) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItem(if (state.selected.size == state.entries.size && state.entries.isNotEmpty()) "Select none" else "Select all", enabled && state.entries.isNotEmpty()) { menu = false; model.selectAll() }
                            MenuItem("New folder", enabled && hasFolder) { menu = false; dialog = "mkdir" }
                            MenuItem("Copy", enabled && selected.isNotEmpty()) { menu = false; model.clipboard(false) }
                            MenuItem("Cut (move)", enabled && selected.isNotEmpty()) { menu = false; model.clipboard(true) }
                            MenuItem("Paste here (${state.clipboard.size})", enabled && hasFolder && state.clipboard.isNotEmpty()) { menu = false; model.paste() }
                            MenuItem("Rename", enabled && selected.size == 1) { menu = false; dialog = "rename" }
                            MenuItem("Details", enabled && selected.size == 1) { menu = false; dialog = "details" }
                            MenuItem("Refresh", enabled) { menu = false; model.refresh() }
                            HorizontalDivider()
                            MenuItem("Sort by\u2026  (${SORT_NAMES[sort]} ${if (descending) "\u2193" else "\u2191"})", true) { menu = false; dialog = "sort" }
                            MenuItem(if (grid) "List view" else "Grid view", true) { menu = false; grid = !grid }
                            MenuItem(if (showHidden) "Hide hidden files" else "Show hidden files", true) { menu = false; showHidden = !showHidden }
                            MenuItem("Theme: ${listOf("System", "Light", "Dark")[theme]}", true) { theme = (theme + 1) % 3 }
                            HorizontalDivider()
                            MenuItem("About", true) { menu = false; dialog = "about" }
                        }
                    }
                }
            }

            // ---- Content ----
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (searching) OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Search this folder") }, shape = RoundedCornerShape(14.dp),
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), enabled = enabled,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }
                )
                if (state.clipboard.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (state.cut) "Move" else "Copy"} ${state.clipboard.size} item(s): open the destination folder, then Paste.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::paste, enabled = enabled && hasFolder) { Text("Paste") }
                        TextButton(onClick = model::clearClipboard) { Text("Clear") }
                    }
                }
                if (state.busy) Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.operation, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text("${fileSize(state.progress)} processed (includes verification)", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = model::cancel) { Text("Cancel") }
                        }
                    }
                }
                if (state.loading || state.previewing) LinearProgressIndicator(Modifier.fillMaxWidth())

                if (!state.granted) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        FolderGlyph(96.dp)
                        Text("Storage access needed", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(
                            "Alal Zip browses your phone storage directly, like a file manager. " +
                                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Turn on \u201cAllow access to manage all files\u201d for Alal Zip, then come back." else "Allow the storage permission to continue.") +
                                " Nothing leaves your device: the app has no internet permission.",
                            Modifier.padding(vertical = 16.dp), textAlign = TextAlign.Center
                        )
                        Button(onClick = requestAccess, shape = RoundedCornerShape(14.dp)) { Text("Allow storage access") }
                    }
                } else {
                    // ---- Storage / navigation header card ----
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(enabled = enabled && !atRoot) { model.back() },
                        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            FolderGlyph(40.dp, up = !atRoot)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(if (atRoot) "Internal storage" else "Up one level", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                val used = (state.total - state.free).coerceAtLeast(0)
                                val fraction = if (state.total > 0) (used.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).height(6.dp).clip(CircleShape),
                                    color = if (fraction > 0.9f) Color(0xFFE53935) else MaterialTheme.colorScheme.primary)
                                Text("${storageSize(state.free)} free of ${storageSize(state.total)}  \u00b7  $folderCount folders, $fileCount files", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = model::refresh, enabled = enabled) { Icon(Icons.Filled.Refresh, "Refresh") }
                        }
                    }

                    if (visible.isEmpty() && !state.loading) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            FolderGlyph(72.dp)
                            Text(if (query.isBlank()) "This folder is empty" else "No matching files", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                            if (query.isBlank()) Text("Long-press the toolbar menu for New folder or Paste.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (grid) LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gridItems(visible, key = { it.path }) { entry ->
                            val checked = entry.path in state.selected
                            val interaction = remember { MutableInteractionSource() }
                            Box {
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                        .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                        .entryGestures(enabled, state.selected, interaction, { openEntry(entry) }) { model.ensureSelected(entry); contextPath = entry.path }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                        FileVisual(entry.name, entry.directory, entry.file, entry.modified, size = 84.dp, radius = 12.dp)
                                        if (checked) Box(Modifier.align(Alignment.TopEnd).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                    Text(if (entry.directory) dateText(entry.modified).substringBefore(' ') else fileSize(entry.size), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                                EntryMenu(entry, state.selected, state.clipboard.isNotEmpty(), enabled, contextPath == entry.path, { contextPath = null }, model, context) { dialog = it }
                            }
                        }
                    } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(visible, key = { it.path }) { entry ->
                            val checked = entry.path in state.selected
                            val interaction = remember { MutableInteractionSource() }
                            Box(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                        .entryGestures(enabled, state.selected, interaction, { openEntry(entry) }) { model.ensureSelected(entry); contextPath = entry.path }
                                        .padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FileVisual(entry.name, entry.directory, entry.file, entry.modified, size = 48.dp)
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                            Text(if (entry.directory) "Folder" else fileSize(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.weight(1f))
                                            Text(dateText(entry.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Checkbox(checked = checked, onCheckedChange = { model.select(entry) }, enabled = enabled)
                                }
                                EntryMenu(entry, state.selected, state.clipboard.isNotEmpty(), enabled, contextPath == entry.path, { contextPath = null }, model, context) { dialog = it }
                            }
                            HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // ---- Bottom breadcrumb path bar ----
            Row(
                Modifier.fillMaxWidth().background(IndigoDeep).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.folders.isEmpty()) Text(model.root.path, color = Color.White, fontSize = 15.sp, maxLines = 1)
                state.folders.forEachIndexed { index, folder ->
                    val last = index == state.folders.lastIndex
                    if (index > 0) Icon(Icons.Filled.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Text(
                        if (index == 0) "Internal storage" else folder.name,
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled && !last) { model.jumpTo(index) }
                            .background(if (last) Color.White.copy(alpha = 0.16f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = if (last) Color.White else Color.White.copy(alpha = 0.8f), fontSize = 15.sp, maxLines = 1,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // ---- Dialogs ----
        state.message?.let { text ->
            AlertDialog(onDismissRequest = { model.message(null) }, icon = { Icon(Icons.Filled.Info, null) }, title = { Text("Alal Zip") },
                text = { Text(text) }, confirmButton = { TextButton(onClick = { model.message(null) }) { Text("OK") } })
        }
        state.archivePreview?.let { preview ->
            ArchiveBrowser(preview = preview, busy = state.previewing, dismiss = model::closePreview, openItem = model::openArchiveItem) {
                val source = state.archiveSource
                model.closePreview()
                if (source != null) { model.clearSelection(); model.ensureSelected(source); dialog = "extract" }
            }
        }
        state.passwordRequest?.let { request ->
            // RAR-style: extraction/opening was attempted without a password first; ask only when the archive needs one.
            var password by remember(request) { mutableStateOf("") }
            AlertDialog(onDismissRequest = { password = ""; model.cancelPassword() }, icon = { ArchiveGlyph(40.dp) },
                title = { Text(if (request.wrongPassword) "Wrong password" else "Password required") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (request.wrongPassword) "That password was rejected for \u201c${request.source.name}\u201d. Try again."
                        else "\u201c${request.source.name}\u201d is encrypted. Enter the archive password to continue.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, label = { Text("Archive password") }, shape = RoundedCornerShape(12.dp),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                } },
                confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { val chars = password.toCharArray(); password = ""; model.answerPassword(chars) }) { Text("Unlock") } },
                dismissButton = { TextButton(onClick = { password = ""; model.cancelPassword() }) { Text("Cancel") } })
        }
        state.collision?.let { name ->
            AlertDialog(onDismissRequest = { model.collisionAnswer(false) }, title = { Text("Name already exists") },
                text = { Text("Replace \u201c$name\u201d? The existing item will be renamed to AP-backup-\u2026 and kept, not deleted. If the operation fails, the backup stays.") },
                confirmButton = { TextButton(onClick = { model.collisionAnswer(true) }) { Text("Keep backup & replace") } },
                dismissButton = { TextButton(onClick = { model.collisionAnswer(false) }) { Text("Cancel operation") } })
        }
        when (dialog) {
            "mkdir", "rename" -> NameDialog(if (dialog == "mkdir") "New folder" else "Rename", if (dialog == "rename") selected.firstOrNull()?.name ?: "" else "",
                dismiss = { dialog = null }) { name -> if (dialog == "mkdir") model.mkdir(name) else model.rename(name); dialog = null }
            "delete" -> AlertDialog(onDismissRequest = { dialog = null }, icon = { Icon(Icons.Filled.Delete, null) }, title = { Text("Delete ${selected.size} item(s)?") },
                text = { Text("This permanently deletes the selected files and all contents of selected folders. There is no undo.") },
                confirmButton = { TextButton(onClick = { dialog = null; model.delete() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
            "details" -> selected.singleOrNull()?.let { entry ->
                AlertDialog(onDismissRequest = { dialog = null },
                    icon = { FileVisual(entry.name, entry.directory, entry.file, entry.modified, size = 56.dp) },
                    title = { Text(entry.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DetailRow("Type", if (entry.directory) "Folder" else "${extension(entry.name).uppercase(Locale.ROOT).ifEmpty { "File" }}  (${mimeType(entry.name)})")
                        DetailRow("Size", if (entry.directory) "\u2014" else fileSize(entry.size) + " (${entry.size} bytes)")
                        DetailRow("Modified", dateText(entry.modified).ifEmpty { "Unknown" })
                        DetailRow("Access", listOfNotNull(if (entry.file.canRead()) "read" else null, if (entry.file.canWrite()) "write" else null).joinToString(", ").ifEmpty { "none" })
                        DetailRow("Path", entry.path)
                    } },
                    confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                    dismissButton = { TextButton(onClick = { copyText(context, "Path", entry.path, model::message) }) { Text("Copy path") } })
            }
            "zip", "extract" -> ArchiveDialog(
                create = dialog == "zip",
                current = state.folders.lastOrNull() ?: model.root,
                root = model.root,
                initialName = if (dialog == "zip") (selected.singleOrNull()?.name?.substringBeforeLast('.') ?: "Archive") + ".zip"
                    else selected.singleOrNull()?.name?.substringBeforeLast('.')?.ifBlank { null } ?: "Extracted",
                dismiss = { dialog = null }
            ) { name, password, destination ->
                if (dialog == "zip") model.zip(name, password, destination) else model.extract(name, password, destination)
                dialog = null
            }
            "sort" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Sort by") },
                text = {
                    Column {
                        SORT_NAMES.forEachIndexed { index, label ->
                            Row(Modifier.fillMaxWidth().clickable { sort = index }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = sort == index, onClick = { sort = index })
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        listOf(false to "Ascending (A\u2192Z, small\u2192large, old\u2192new)", true to "Descending (Z\u2192A, large\u2192small, new\u2192old)").forEach { (value, label) ->
                            Row(Modifier.fillMaxWidth().clickable { descending = value }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = descending == value, onClick = { descending = value })
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("Folders are always listed before files.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
            "about" -> AlertDialog(onDismissRequest = { dialog = null }, icon = { ArchiveGlyph(48.dp) }, title = { Text("Alal Zip 0.4.1") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Offline file manager and archiver: thumbnails, file-type icons, list/grid views, quick-access folders, secure Open with\u2026 and Share, plus folder-style ZIP/RAR browsing. Encrypted archives ask for their password only when needed, like RAR. Opening an archive item extracts only that member to private cache (512 MiB viewing limit), never the entire archive. ZIP/AES: Zip4j 2.11.6 (Apache-2.0). RAR extraction: Junrar 8.1.1 (UnRAR license). No RAR creation, split archives or links. 2 GiB extraction / 10,000 entries / 64 MiB RAR dictionary limits. Keep the app in the foreground during operations.")
                    Text("\nJunrar code may not be used to develop a RAR (WinRAR) compatible archiver. Copyright Alexander Roshal. Full third-party notices are bundled in app assets and source licenses.")
                } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** RAR-style long-press context menu. Only the active entry composes a menu, which keeps scrolling smooth. */
@Composable
private fun EntryMenu(
    entry: Entry, selection: Set<String>, clipboardFilled: Boolean, enabled: Boolean, expanded: Boolean, dismiss: () -> Unit,
    model: PocketViewModel, context: Context, openDialog: (String) -> Unit
) {
    if (!expanded) return
    val single = selection.size == 1 && entry.path in selection
    val archive = !entry.directory && isArchive(entry.name)
    DropdownMenu(expanded = true, onDismissRequest = dismiss) {
        fun pick(action: () -> Unit) { dismiss(); action() }
        Text(entry.name, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isPreviewableArchive(entry.name) && single) MenuItem("Open archive", enabled) { pick { model.preview(entry) } }
        else if (!entry.directory && single) MenuItem("Open with\u2026", enabled) { pick { launchFile(context, entry.file, model::message) } }
        if (entry.directory && single) MenuItem("Open folder", enabled) { pick { model.clearSelection(); model.enter(entry) } }
        if (archive && single) {
            MenuItem("Extract to ${entry.name.substringBeforeLast('.')}/", enabled) { pick { model.extract(entry.name.substringBeforeLast('.'), null) } }
            MenuItem("Extract here", enabled) { pick { model.extractHere(null) } }
            MenuItem("Extract files\u2026", enabled) { pick { openDialog("extract") } }
        }
        MenuItem("Add to ZIP\u2026", enabled) { pick { openDialog("zip") } }
        if (!entry.directory) MenuItem("Share", enabled) { pick { shareFiles(context, listOf(entry.file), model::message) } }
        MenuItem("Copy", enabled) { pick { model.clipboard(false) } }
        MenuItem("Cut", enabled) { pick { model.clipboard(true) } }
        MenuItem("Paste", enabled && clipboardFilled) { pick { model.paste() } }
        MenuItem("New folder", enabled) { pick { openDialog("mkdir") } }
        MenuItem("Delete", enabled) { pick { openDialog("delete") } }
        MenuItem("Rename", enabled && single) { pick { openDialog("rename") } }
        MenuItem("Copy path", enabled && single) { pick { copyText(context, "Path", entry.path, model::message) } }
        MenuItem("Details", enabled && single) { pick { openDialog("details") } }
    }
}

// ---------------------------------------------------------------- archive browser
private data class ArchiveNode(val path: String, val name: String, val directory: Boolean, val size: Long)

private fun archiveChildren(preview: ArchivePreview, folder: String): List<ArchiveNode> {
    val prefix = if (folder.isBlank()) "" else "$folder/"
    val result = linkedMapOf<String, ArchiveNode>()
    preview.items.forEach { item ->
        val normalized = item.path.replace('\\', '/').trim('/')
        if (!normalized.startsWith(prefix)) return@forEach
        val remainder = normalized.removePrefix(prefix)
        if (remainder.isEmpty()) return@forEach
        val name = remainder.substringBefore('/')
        val nested = '/' in remainder
        val path = if (folder.isBlank()) name else "$folder/$name"
        val node = ArchiveNode(path, name, nested || item.directory, if (nested || item.directory) 0 else item.size)
        val key = name.lowercase(Locale.ROOT)
        val previous = result[key]
        result[key] = if (previous?.directory == true) previous else node
    }
    return result.values.sortedWith(compareByDescending<ArchiveNode> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

@Composable
private fun ArchiveBrowser(preview: ArchivePreview, busy: Boolean, dismiss: () -> Unit, openItem: (String, CharArray?) -> Unit, extractAll: () -> Unit) {
    var folder by remember(preview.archiveName) { mutableStateOf("") }
    var query by rememberSaveable(preview.archiveName) { mutableStateOf("") }
    var menuPath by remember { mutableStateOf<String?>(null) }
    var passwordPath by remember { mutableStateOf<String?>(null) }
    val allChildren = remember(preview, folder) { archiveChildren(preview, folder) }
    val children = remember(allChildren, query) { allChildren.filter { query.isBlank() || it.name.contains(query, true) } }
    val totalSize = remember(preview) { preview.items.sumOf { if (it.directory) 0L else it.size } }
    fun goBack() { if (folder.isBlank()) dismiss() else folder = folder.substringBeforeLast('/', "") }
    Dialog(onDismissRequest = ::goBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Indigo, IndigoLight))).statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::goBack, enabled = !busy) { Icon(Icons.Filled.Close, "Close archive", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(preview.archiveName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${preview.items.count { !it.directory }} files \u00b7 ${fileSize(totalSize)} unpacked \u00b7 not extracted", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
                    }
                    ArchiveIcon(extract = true, enabled = !busy, description = "Extract all") { extractAll() }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(14.dp),
                    singleLine = true, label = { Text("Search this archive folder") }, enabled = !busy, leadingIcon = { Icon(Icons.Filled.Search, null) })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ArchiveGlyph(18.dp)
                    Text("  " + preview.archiveName + "/" + folder, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (folder.isNotBlank()) item(key = "__archive_up__") {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { goBack() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            FolderGlyph(44.dp, up = true)
                            Text("Up one level", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(Modifier.padding(start = 76.dp))
                    }
                    items(children, key = { it.path }) { node ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            if (node.directory) { folder = node.path; query = "" } else openItem(node.path, null)
                        }.padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileVisual(node.name, node.directory, size = 48.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(if (node.directory) "Folder" else fileSize(node.size) + "  \u00b7  " + fileKind(node.name).label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!node.directory) Box {
                                IconButton(onClick = { menuPath = node.path }, enabled = !busy) { Icon(Icons.Filled.MoreVert, "File options") }
                                DropdownMenu(expanded = menuPath == node.path, onDismissRequest = { menuPath = null }) {
                                    MenuItem("Open with\u2026", !busy) { menuPath = null; openItem(node.path, null) }
                                    MenuItem("Open with password\u2026", !busy) { menuPath = null; passwordPath = node.path }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    if (children.isEmpty()) item { Text(if (query.isBlank()) "Empty folder" else "No matching files", Modifier.padding(24.dp)) }
                }
                Text("Tap a file to open it with an installed viewer. Only that item is copied to private cache; the archive is not extracted.",
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
    passwordPath?.let { path ->
        var password by remember(path) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { password = ""; passwordPath = null }, title = { Text("Open encrypted file") },
            text = { OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("Archive password") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)) },
            confirmButton = { TextButton(onClick = {
                val chars = password.takeIf { it.isNotEmpty() }?.toCharArray(); password = ""; passwordPath = null; openItem(path, chars)
            }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { password = ""; passwordPath = null }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- small dialogs
@Composable
private fun MenuItem(text: String, enabled: Boolean, action: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = action)
}

@Composable
private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp)) },
        confirmButton = { TextButton(onClick = { submit(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ArchiveDialog(create: Boolean, current: File, root: File, initialName: String, dismiss: () -> Unit, submit: (String, CharArray?, File) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    // Deliberately not rememberSaveable: passwords must never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var destination by remember(current.path) { mutableStateOf(current) }
    var picking by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && (!create || password == repeat)
    fun close() { password = ""; repeat = ""; dismiss() }
    if (picking) {
        FolderPickerDialog(start = destination, root = root, dismiss = { picking = false }) { chosen ->
            destination = chosen; picking = false
        }
        return
    }
    AlertDialog(onDismissRequest = { close() }, icon = { ArchiveGlyph(40.dp) }, title = { Text(if (create) "Create ZIP" else "Extract ZIP / RAR") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("Destination: ${destination.path}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { picking = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Change folder\u2026") }
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(if (create) "ZIP file name" else "Output folder name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            if (create) {
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (optional)") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedTextField(value = repeat, onValueChange = { repeat = it }, label = { Text("Repeat password") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            }
            Text(if (create) "The ZIP is created in the destination folder. A non-empty password enables AES-256. File names are not hidden. Forgotten passwords cannot be recovered."
                else "Files are extracted into a new sub-folder of the destination folder you choose. Encrypted archives ask for their password only when it is needed. Up to 2 GiB output. Split volumes and RAR links are unsupported.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = valid, onClick = {
            val chars = if (create) password.takeIf { it.isNotEmpty() }?.toCharArray() else null
            password = ""; repeat = ""
            submit(if (create && !name.endsWith(".zip", true)) "$name.zip" else name, chars, destination)
        }) { Text(if (create) "Create" else "Extract") } },
        dismissButton = { TextButton(onClick = { close() }) { Text("Cancel") } })
}

/** RAR-style destination chooser: browse folders under the storage root and pick one. */
@Composable
private fun FolderPickerDialog(start: File, root: File, dismiss: () -> Unit, select: (File) -> Unit) {
    fun path(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    var folder by remember { mutableStateOf(if (start.isDirectory && path(start).startsWith(path(root))) start else root) }
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(folder.path, reload) {
        val listed = withContext(Dispatchers.IO) {
            folder.listFiles()?.filter { it.isDirectory }?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { child: File -> child.name })
        }
        failed = listed == null
        children = listed ?: emptyList()
    }
    val parent = folder.parentFile
    val canGoUp = parent != null && path(folder) != path(root) && path(folder).startsWith(path(root))
    if (creating) {
        NameDialog("New folder in ${folder.name}", "", dismiss = { creating = false }) { newName ->
            creating = false
            val safe = newName.trim()
            if (safe.isNotBlank() && !safe.contains('/') && safe != "." && safe != "..") {
                val created = File(folder, safe)
                if (created.isDirectory || created.mkdir()) { folder = created } else reload++
            }
        }
    }
    AlertDialog(onDismissRequest = dismiss, icon = { FolderGlyph(34.dp) }, title = { Text("Choose destination folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(folder.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canGoUp) TextButton(onClick = { parent?.let { folder = it } }) { Text("Up one level") }
                    TextButton(onClick = { creating = true }) { Text("New folder\u2026") }
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    when {
                        failed -> Text("Cannot read this folder. Check that storage access (All files access) is allowed.", style = MaterialTheme.typography.bodySmall)
                        children.isEmpty() -> Text("No sub-folders here.", style = MaterialTheme.typography.bodySmall)
                        else -> children.forEach { child ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { folder = child }.padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                FolderGlyph(22.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(child.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { select(folder) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

