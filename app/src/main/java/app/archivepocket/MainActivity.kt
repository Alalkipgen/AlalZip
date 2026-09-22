package app.archivepocket

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
private val Indigo = Color(0xFF4F46E5)
private val IndigoDeep = Color(0xFF3730A3)
private val IndigoLight = Color(0xFF6366F1)
private val Amber = Color(0xFF39D5F5)        // cyan archive-action accent from the redesigned icon
private val AmberDark = Color(0xFF2783DE)
private val FolderBlue = Color(0xFF4F46E5)
private val FolderBlueDark = Color(0xFF3730A3)
private val NavyBright = Color(0xFF39D5F5)
private val NavyMid = Color(0xFF2783DE)
private val NavyDeep = Color(0xFF3730A3)
private val NavyInk = Color(0xFF211B60)
private val ZipAccent = Color(0xFF39D5F5)
private val UpGreen = Color(0xFF46A171)

// A single clean sans-serif voice keeps file names, actions and prompts immediately readable.
private val UiFont = FontFamily.SansSerif
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = UiFont, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = UiFont, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = UiFont, fontSize = 12.5.sp, lineHeight = 17.5.sp, letterSpacing = 0.15.sp),
    labelLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

// One rounded language for every prompt box, card and input in the app.
private val DialogShape = RoundedCornerShape(22.dp)
private val FieldShape = RoundedCornerShape(12.dp)
private val CardShape = RoundedCornerShape(14.dp)

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
        // Flat, high-contrast folder geometry shared with the launcher and file-type system.
        drawRoundRect(IndigoDeep, Offset(w * 0.06f, h * 0.14f), Size(w * 0.43f, h * 0.24f), CornerRadius(w * 0.08f))
        drawRoundRect(Indigo, Offset(w * 0.03f, h * 0.27f), Size(w * 0.94f, h * 0.58f), CornerRadius(w * 0.13f))
        drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(w * 0.10f, h * 0.32f), Size(w * 0.80f, h * 0.08f), CornerRadius(w * 0.04f))
        if (up) {
            val center = Offset(w * 0.74f, h * 0.66f); val radius = w * 0.20f
            drawCircle(Color.White, radius, center)
            val stroke = w * 0.075f
            drawLine(UpGreen, Offset(center.x, center.y + radius * 0.48f), Offset(center.x, center.y - radius * 0.48f), stroke, StrokeCap.Round)
            drawLine(UpGreen, Offset(center.x - radius * 0.42f, center.y - radius * 0.04f), Offset(center.x, center.y - radius * 0.50f), stroke, StrokeCap.Round)
            drawLine(UpGreen, Offset(center.x + radius * 0.42f, center.y - radius * 0.04f), Offset(center.x, center.y - radius * 0.50f), stroke, StrokeCap.Round)
        }
    }
}

/** Flat archive-document glyph matching the redesigned adaptive launcher icon. */
@Composable
private fun ArchiveGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        fun x(v: Float) = w * v
        fun y(v: Float) = h * v
        val file = Path().apply {
            moveTo(x(0.16f), y(0.04f)); lineTo(x(0.66f), y(0.04f)); lineTo(x(0.92f), y(0.30f))
            lineTo(x(0.92f), y(0.90f)); quadraticTo(x(0.92f), y(0.96f), x(0.84f), y(0.96f))
            lineTo(x(0.16f), y(0.96f)); quadraticTo(x(0.08f), y(0.96f), x(0.08f), y(0.88f))
            lineTo(x(0.08f), y(0.13f)); quadraticTo(x(0.08f), y(0.04f), x(0.16f), y(0.04f)); close()
        }
        drawPath(file, Color(0xFFE7ECF2))
        drawPath(file, NavyInk, style = Stroke(width = w * 0.055f))
        val fold = Path().apply {
            moveTo(x(0.66f), y(0.04f)); lineTo(x(0.66f), y(0.30f)); lineTo(x(0.92f), y(0.30f)); close()
        }
        drawPath(fold, Color(0xFFD8E1EC))
        drawPath(fold, NavyInk, style = Stroke(width = w * 0.045f))
        drawRoundRect(ZipAccent, Offset(x(0.455f), y(0.12f)), Size(x(0.09f), y(0.54f)), CornerRadius(w * 0.035f))
        var toothY = 0.18f
        repeat(5) {
            drawRoundRect(NavyDeep, Offset(x(0.425f), y(toothY)), Size(x(0.15f), y(0.032f)), CornerRadius(w * 0.01f))
            toothY += 0.085f
        }
        val arrowOutline = Path().apply {
            moveTo(x(0.38f), y(0.60f)); lineTo(x(0.62f), y(0.60f)); lineTo(x(0.62f), y(0.72f))
            lineTo(x(0.72f), y(0.72f)); lineTo(x(0.50f), y(0.91f)); lineTo(x(0.28f), y(0.72f))
            lineTo(x(0.38f), y(0.72f)); close()
        }
        drawPath(arrowOutline, NavyInk)
        val arrow = Path().apply {
            moveTo(x(0.42f), y(0.62f)); lineTo(x(0.58f), y(0.62f)); lineTo(x(0.58f), y(0.76f))
            lineTo(x(0.64f), y(0.76f)); lineTo(x(0.50f), y(0.87f)); lineTo(x(0.36f), y(0.76f))
            lineTo(x(0.42f), y(0.76f)); close()
        }
        drawPath(arrow, ZipAccent)
    }
}

/** A coordinated folded-document tile carrying the file-type label (PDF, DOC, APK, ...). */
@Composable
private fun TypeBadge(kind: FileKind, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            val page = Path().apply {
                moveTo(w * 0.14f, h * 0.05f); lineTo(w * 0.66f, h * 0.05f); lineTo(w * 0.91f, h * 0.30f)
                lineTo(w * 0.91f, h * 0.92f); lineTo(w * 0.14f, h * 0.92f); close()
            }
            drawPath(page, Color(0xFFE7ECF2))
            drawPath(page, NavyInk, style = Stroke(width = w * 0.055f))
            val fold = Path().apply {
                moveTo(w * 0.66f, h * 0.05f); lineTo(w * 0.66f, h * 0.30f); lineTo(w * 0.91f, h * 0.30f); close()
            }
            drawPath(fold, Color(0xFFD8E1EC))
            drawRoundRect(kind.color, Offset(w * 0.10f, h * 0.66f), Size(w * 0.85f, h * 0.27f), CornerRadius(w * 0.06f))
            if (kind == FileKind.AUDIO) {
                drawLine(NavyDeep, Offset(w * 0.38f, h * 0.34f), Offset(w * 0.38f, h * 0.54f), w * 0.05f, StrokeCap.Round)
                drawLine(NavyDeep, Offset(w * 0.50f, h * 0.26f), Offset(w * 0.50f, h * 0.58f), w * 0.05f, StrokeCap.Round)
                drawLine(NavyDeep, Offset(w * 0.62f, h * 0.36f), Offset(w * 0.62f, h * 0.52f), w * 0.05f, StrokeCap.Round)
            }
        }
        Text(
            kind.label, Modifier.offset(y = size * 0.26f), color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = if (kind.label.length > 3) 8.sp else 10.sp, letterSpacing = 0.3.sp, maxLines = 1
        )
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
private fun GridGlyph(grid: Boolean, tint: Color, description: String) {
    Canvas(Modifier.size(22.dp).semantics { contentDescription = description }) {
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

/** Zipper tile with a "+" (create) or up-arrow (extract) badge, shared by the toolbar and the selection bar. */
@Composable
private fun ArchiveActionGlyph(extract: Boolean, tile: Color, inner: Color, badge: Color, description: String? = null, size: Dp = 27.dp) {
    Canvas(Modifier.size(size).semantics { if (description != null) contentDescription = description }) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(tile, Offset(0f, h * 0.06f), Size(w * 0.64f, h * 0.88f), CornerRadius(w * 0.18f))
        for (i in 0..3) drawRect(inner, Offset(w * 0.26f, h * (0.2f + i * 0.16f)), Size(w * 0.14f, h * 0.08f))
        val c = Offset(w * 0.74f, h * 0.73f); val r = w * 0.26f
        drawCircle(badge, r, c)
        val stroke = w * 0.1f
        drawLine(inner, Offset(c.x, c.y + r * 0.52f), Offset(c.x, c.y - r * 0.52f), stroke, StrokeCap.Round)
        if (extract) {
            drawLine(inner, Offset(c.x - r * 0.46f, c.y - r * 0.06f), Offset(c.x, c.y - r * 0.54f), stroke, StrokeCap.Round)
            drawLine(inner, Offset(c.x + r * 0.46f, c.y - r * 0.06f), Offset(c.x, c.y - r * 0.54f), stroke, StrokeCap.Round)
        } else {
            drawLine(inner, Offset(c.x - r * 0.52f, c.y), Offset(c.x + r * 0.52f, c.y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun ArchiveIcon(extract: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        ArchiveActionGlyph(
            extract = extract, tile = Color.White.copy(alpha = if (enabled) 1f else 0.4f), inner = IndigoDeep,
            badge = Amber.copy(alpha = if (enabled) 1f else 0.4f), description = description
        )
    }
}

/** Selection-mode actions placed within thumb reach, each with a visible label. */
@Composable
private fun SelectionBar(canExtract: Boolean, canShare: Boolean, enabled: Boolean,
                         onExtract: () -> Unit, onZip: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 6.dp, shadowElevation = 10.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            SelectionAction("Extract", enabled && canExtract, Modifier.weight(1f), onExtract) { tint ->
                ArchiveActionGlyph(extract = true, tile = tint, inner = MaterialTheme.colorScheme.surfaceVariant, badge = Amber, size = 26.dp)
            }
            SelectionAction("Add to ZIP", enabled, Modifier.weight(1f), onZip) { tint ->
                ArchiveActionGlyph(extract = false, tile = tint, inner = MaterialTheme.colorScheme.surfaceVariant, badge = Amber, size = 26.dp)
            }
            SelectionAction("Share", enabled && canShare, Modifier.weight(1f), onShare) { tint ->
                Icon(Icons.Filled.Share, null, Modifier.size(24.dp), tint = tint)
            }
            SelectionAction("Delete", enabled, Modifier.weight(1f), onDelete) { tint ->
                Icon(Icons.Filled.Delete, null, Modifier.size(24.dp), tint = tint)
            }
        }
    }
}

@Composable
private fun SelectionAction(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val tint = if (label == "Delete" && enabled) MaterialTheme.colorScheme.error
        else if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Column(
        modifier.clip(CardShape).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        glyph(tint)
        Text(label, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelMedium, color = tint,
            maxLines = 2, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- quick access
private val QUICK_FOLDERS = listOf(
    "Download" to Environment.DIRECTORY_DOWNLOADS, "DCIM (Camera)" to Environment.DIRECTORY_DCIM,
    "Pictures" to Environment.DIRECTORY_PICTURES, "Movies" to Environment.DIRECTORY_MOVIES,
    "Music" to Environment.DIRECTORY_MUSIC, "Documents" to Environment.DIRECTORY_DOCUMENTS
)

/** Tap = open/select, long-press = context menu with a single light haptic confirmation. */
private fun Modifier.entryGestures(enabled: Boolean, selection: Set<String>, interaction: MutableInteractionSource, haptics: HapticFeedback,
                                   onTap: () -> Unit, onLongPress: () -> Unit): Modifier =
    this.indication(interaction, ripple()).pointerInput(enabled, selection) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onPress = { offset ->
                val press = PressInteraction.Press(offset)
                interaction.emit(press)
                interaction.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            },
            onTap = { onTap() },
            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }
        )
    }

// ---------------------------------------------------------------- app
@Composable
fun PocketApp(model: PocketViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var theme by rememberSaveable { mutableStateOf(0) }
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF8CB9F4), onPrimary = Color(0xFF102033), secondary = Color(0xFF59D7F5),
        tertiary = Color(0xFF72BC8F), background = Color(0xFF111315), surface = Color(0xFF17191C),
        onSurface = Color(0xFFF4F5F7), surfaceVariant = Color(0xFF202327), onSurfaceVariant = Color(0xFFB6BDC8),
        secondaryContainer = Color(0xFF292E36), onSecondaryContainer = Color(0xFFE7EBF2),
        outline = Color(0xFF626A76), outlineVariant = Color(0xFF343941)
    ) else lightColorScheme(
        primary = Indigo, onPrimary = Color.White, secondary = Color(0xFF2783DE), tertiary = Color(0xFF2A8F76),
        background = Color(0xFFF8F7FA), surface = Color(0xFFF8F7FA), onSurface = Color(0xFF22222A),
        surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF686875),
        secondaryContainer = Color(0xFFECEBFF), onSecondaryContainer = IndigoDeep,
        outline = Color(0xFF8A8996), outlineVariant = Color(0xFFE3E1E8)
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
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Long operations keep running through a foreground service; this permission only makes their progress visible.
    LaunchedEffect(state.granted) {
        if (state.granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
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
    // Successful operations report through a snackbar instead of interrupting with a dialog.
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        val target = state.noticeTarget
        val result = snackbar.showSnackbar(
            message = notice, actionLabel = if (target != null) "Open folder" else null,
            withDismissAction = target == null, duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && target != null) model.reveal(target)
        model.consumeNotice()
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

    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        Column(Modifier.fillMaxSize().background(scheme.background)) {
            // ---- Top toolbar: browse mode / selection mode ----
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                    .background(if (selecting) IndigoDeep else MaterialTheme.colorScheme.primary)
            ) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        IconButton(onClick = model::clearSelection) { Icon(Icons.Filled.Close, "Clear selection", tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text("${state.selected.size} selected", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text(fileSize(selected.sumOf { it.size }) + if (selected.any { it.directory }) " + folders" else "", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = model::selectAll, enabled = enabled && state.entries.isNotEmpty()) {
                            Icon(Icons.Filled.Check, "Select all", tint = Color.White)
                        }
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
                            Text("Alal Zip", color = Color.White, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(if (atRoot || !hasFolder) "Internal storage" else state.folders.last().name, color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search", tint = Color.White)
                        }
                        IconButton(onClick = { grid = !grid }) {
                            GridGlyph(grid = !grid, tint = Color.White, description = if (grid) "List view" else "Grid view")
                        }
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
                            MenuItem("Verify large copies: ${if (state.verifyLarge) "On" else "Off"}", true) { model.toggleVerifyLarge() }
                            HorizontalDivider()
                            MenuItem("About", true) { menu = false; dialog = "about" }
                        }
                    }
                }
            }

            // ---- Content ----
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (searching) OutlinedTextField(
                    value = query, onValueChange = { query = it }, placeholder = { Text("Search this folder") }, shape = FieldShape,
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), enabled = enabled,
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
                if (state.clipboard.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (state.cut) "Move" else "Copy"} ${state.clipboard.size} item(s): open the destination folder, then Paste.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::paste, enabled = enabled && hasFolder) { Text("Paste") }
                        TextButton(onClick = model::clearClipboard) { Text("Clear") }
                    }
                }
                if (state.busy) Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        val share = if (state.operationTotal > 0) (state.progress.toFloat() / state.operationTotal).coerceIn(0f, 1f) else null
                        if (share != null) {
                            LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                                trackColor = MaterialTheme.colorScheme.outlineVariant)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                                trackColor = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (share != null) "${(share * 100).toInt()}%  \u00b7  ${state.operation.substringBefore(" \u00b7 ")}" else state.operation,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    if (share != null) "${fileSize(state.progress)} of ${fileSize(state.operationTotal)} (includes verification)"
                                    else "${fileSize(state.progress)} processed (includes verification)",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (state.currentItem.isNotBlank()) Text(state.currentItem, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                        Button(onClick = requestAccess, shape = CardShape, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp)) {
                            Text("Allow storage access", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    // ---- Storage / navigation header card ----
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
                        .clickable(enabled = enabled && !atRoot) { model.back() },
                        shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            FolderGlyph(40.dp, up = !atRoot)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(if (atRoot) "Internal storage" else "Up one level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                val used = (state.total - state.free).coerceAtLeast(0)
                                val fraction = if (state.total > 0) (used.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(8.dp).clip(CircleShape),
                                    color = if (fraction > 0.9f) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outlineVariant)
                                Text("${storageSize(state.free)} free of ${storageSize(state.total)}  \u00b7  $folderCount folders, $fileCount files", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = model::refresh, enabled = enabled) { Icon(Icons.Filled.Refresh, "Refresh") }
                        }
                    }

                    if (visible.isEmpty() && !state.loading) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            FolderGlyph(84.dp)
                            Text(if (query.isBlank()) "This folder is empty" else "No matching files", Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (query.isBlank()) Text("Use the toolbar menu for New folder or Paste.", Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Modifier.fillMaxWidth().clip(CardShape)
                                        .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)
                                        .border(if (checked) 1.5.dp else 1.dp,
                                            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CardShape)
                                        .entryGestures(enabled, state.selected, interaction, haptics, { openEntry(entry) }) { model.ensureSelected(entry); contextPath = entry.path }
                                        .padding(10.dp),
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
                            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().clip(CardShape)
                                        .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)
                                        .border(if (checked) 1.5.dp else 1.dp,
                                            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CardShape)
                                        .entryGestures(enabled, state.selected, interaction, haptics, { openEntry(entry) }) { model.ensureSelected(entry); contextPath = entry.path }
                                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
                                    Checkbox(checked = checked, onCheckedChange = { model.select(entry) }, enabled = enabled,
                                        modifier = Modifier.semantics { contentDescription = "Select ${entry.name}" })
                                }
                                EntryMenu(entry, state.selected, state.clipboard.isNotEmpty(), enabled, contextPath == entry.path, { contextPath = null }, model, context) { dialog = it }
                            }
                        }
                    }
                }
            }

            if (selecting) SelectionBar(
                canExtract = selected.size == 1 && isArchive(selected[0].name),
                canShare = selected.isNotEmpty() && selected.none { it.directory },
                enabled = enabled,
                onExtract = { dialog = "extract" }, onZip = { dialog = "zip" },
                onShare = { shareFiles(context, selected.map { it.file }, model::message) },
                onDelete = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); dialog = "delete" }
            )
            SnackbarHost(snackbar, Modifier.padding(horizontal = 10.dp))

            // ---- Bottom breadcrumb path bar ----
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.folders.isEmpty()) Text(model.root.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
                state.folders.forEachIndexed { index, folder ->
                    val last = index == state.folders.lastIndex
                    if (index > 0) Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        if (index == 0) "Internal storage" else folder.name,
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled && !last) { model.jumpTo(index) }
                            .background(if (last) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // ---- Dialogs ----
        state.message?.let { text ->
            PocketDialog(
                title = "Alal Zip", onDismiss = { model.message(null) }, confirmLabel = "OK",
                onConfirm = { model.message(null) }, dismissLabel = null,
                glyph = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) }
            ) { Text(text, style = MaterialTheme.typography.bodyMedium) }
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
            PocketDialog(
                title = if (request.wrongPassword) "Wrong password" else "Password required",
                subtitle = request.source.name,
                onDismiss = { password = ""; model.cancelPassword() },
                confirmLabel = "Unlock", confirmEnabled = password.isNotEmpty(),
                onConfirm = { val chars = password.toCharArray(); password = ""; model.answerPassword(chars) },
                onDismissClick = { password = ""; model.cancelPassword() },
                glyph = { ArchiveGlyph(36.dp) }
            ) {
                Text(
                    if (request.wrongPassword) "That password was rejected. Try again." else "This archive is encrypted.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PasswordField(password, { password = it }, "Archive password", isError = request.wrongPassword)
            }
        }
        state.collision?.let { name ->
            PocketDialog(
                title = "Name already exists", subtitle = name,
                onDismiss = { model.collisionAnswer(false) },
                confirmLabel = "Replace", onConfirm = { model.collisionAnswer(true) },
                dismissLabel = "Cancel operation", onDismissClick = { model.collisionAnswer(false) },
                glyph = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) }
            ) {
                ExpandableNote(
                    summary = "The existing item is kept as a backup, not deleted.",
                    details = "It is renamed to AP-backup-\u2026 before the new data is written. If the operation fails, the backup stays in place."
                )
            }
        }
        when (dialog) {
            "mkdir", "rename" -> NameDialog(if (dialog == "mkdir") "New folder" else "Rename", if (dialog == "rename") selected.firstOrNull()?.name ?: "" else "",
                dismiss = { dialog = null }) { name -> if (dialog == "mkdir") model.mkdir(name) else model.rename(name); dialog = null }
            "delete" -> PocketDialog(
                title = "Delete ${selected.size} item(s)?",
                subtitle = selected.singleOrNull()?.name,
                onDismiss = { dialog = null },
                confirmLabel = "Delete", destructive = true, onConfirm = { dialog = null; model.delete() },
                glyph = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
            ) {
                Text("Selected files and everything inside selected folders are removed. There is no undo.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "details" -> selected.singleOrNull()?.let { entry ->
                PocketDialog(
                    title = entry.name,
                    subtitle = if (entry.directory) "Folder" else fileSize(entry.size),
                    onDismiss = { dialog = null }, confirmLabel = "Close", onConfirm = { dialog = null },
                    dismissLabel = "Copy path", onDismissClick = { copyText(context, "Path", entry.path, model::message) },
                    glyph = { FileVisual(entry.name, entry.directory, entry.file, entry.modified, size = 40.dp) }
                ) {
                    DetailRow("Type", if (entry.directory) "Folder" else "${extension(entry.name).uppercase(Locale.ROOT).ifEmpty { "File" }}  (${mimeType(entry.name)})")
                    DetailRow("Size", if (entry.directory) "\u2014" else fileSize(entry.size) + " (${entry.size} bytes)")
                    DetailRow("Modified", dateText(entry.modified).ifEmpty { "Unknown" })
                    DetailRow("Access", listOfNotNull(if (entry.file.canRead()) "read" else null, if (entry.file.canWrite()) "write" else null).joinToString(", ").ifEmpty { "none" })
                    DetailRow("Path", entry.path)
                }
            }
            "zip", "extract" -> ArchiveDialog(
                create = dialog == "zip",
                current = state.folders.lastOrNull() ?: model.root,
                root = model.root,
                recent = remember(state.recent) { state.recent.map { File(it) } },
                initialName = if (dialog == "zip") (selected.singleOrNull()?.name?.substringBeforeLast('.') ?: "Archive") + ".zip"
                    else selected.singleOrNull()?.name?.substringBeforeLast('.')?.ifBlank { null } ?: "Extracted",
                dismiss = { dialog = null }
            ) { name, password, destination ->
                if (dialog == "zip") model.zip(name, password, destination) else model.extract(name, password, destination)
                dialog = null
            }
            "sort" -> PocketDialog(
                title = "Sort by", onDismiss = { dialog = null }, confirmLabel = "Done",
                onConfirm = { dialog = null }, dismissLabel = null
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SORT_NAMES.forEachIndexed { index, label -> ChoiceRow(label, sort == index) { sort = index } }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChoiceRow("Ascending (A\u2192Z, small\u2192large, old\u2192new)", !descending) { descending = false }
                    ChoiceRow("Descending (Z\u2192A, large\u2192small, new\u2192old)", descending) { descending = true }
                }
                Text("Folders are always listed before files.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "about" -> PocketDialog(
                title = "Alal Zip", subtitle = "Version 0.7.0  \u00b7  offline, no internet permission",
                onDismiss = { dialog = null }, confirmLabel = "Close", onConfirm = { dialog = null }, dismissLabel = null,
                glyph = { ArchiveGlyph(40.dp) }
            ) {
                Text("File manager and archiver with thumbnails, list and grid views, quick-access folders, folder-style ZIP/RAR browsing and secure Open with\u2026 and Share.",
                    style = MaterialTheme.typography.bodyMedium)
                ExpandableNote(
                    summary = "Encrypted archives ask for their password only when it is needed.",
                    details = "Opening an archive item extracts only that member to private cache (512 MiB viewing limit), never the whole archive. " +
                        "ZIP/AES: Zip4j 2.11.6 (Apache-2.0). RAR extraction: Junrar 8.1.1 (UnRAR license); no RAR creation, split archives or links. " +
                        "Limits: 2 GiB extraction, 10,000 entries, 64 MiB RAR dictionary. Keep the app in the foreground during operations. " +
                        "Junrar code may not be used to develop a RAR (WinRAR) compatible archiver. Copyright Alexander Roshal. " +
                        "Full third-party notices are bundled in app assets and source licenses."
                )
            }
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
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::goBack, enabled = !busy) { Icon(Icons.Filled.Close, "Close archive", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(preview.archiveName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${preview.items.count { !it.directory }} files \u00b7 ${fileSize(totalSize)} unpacked \u00b7 not extracted", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
                    }
                    ArchiveIcon(extract = true, enabled = !busy, description = "Extract all") { extractAll() }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = FieldShape,
                    singleLine = true, placeholder = { Text("Search this archive folder") }, enabled = !busy,
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(FieldShape).background(MaterialTheme.colorScheme.secondaryContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 9.dp),
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
        PocketDialog(
            title = "Open encrypted file", subtitle = path.substringAfterLast('/'),
            onDismiss = { password = ""; passwordPath = null },
            confirmLabel = "Open", confirmEnabled = password.isNotEmpty(),
            onConfirm = { val chars = password.takeIf { it.isNotEmpty() }?.toCharArray(); password = ""; passwordPath = null; openItem(path, chars) },
            onDismissClick = { password = ""; passwordPath = null },
            glyph = { ArchiveGlyph(36.dp) }
        ) { PasswordField(password, { password = it }, "Archive password") }
    }
}

// ---------------------------------------------------------------- prompt boxes
/**
 * One shell for every prompt box: glyph and title on a single left-aligned line, an optional
 * subtitle for the object being acted on, a 12 dp content rhythm and a filled primary action.
 */
@Composable
private fun PocketDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissLabel: String? = "Cancel",
    onDismissClick: (() -> Unit)? = null,
    subtitle: String? = null,
    glyph: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val secondaryButton: (@Composable () -> Unit)? = dismissLabel?.let { label ->
        { TextButton(onClick = onDismissClick ?: onDismiss) { Text(label, maxLines = 1) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) { glyph() }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = confirmEnabled, shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                    else ButtonDefaults.buttonColors()
            ) { Text(confirmLabel, maxLines = 1) }
        },
        dismissButton = secondaryButton
    )
}

/** Tappable destination summary: folder glyph, readable path, Change affordance. */
@Composable
private fun DestinationRow(folder: File, root: File, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.secondaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .clickable(onClick = onChange).padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderGlyph(26.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Destination", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(shortPath(folder, root), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Filled.KeyboardArrowRight, "Change destination folder", tint = MaterialTheme.colorScheme.primary)
    }
}

/** "Internal storage / Download" reads better in a prompt box than /storage/emulated/0/Download. */
private fun shortPath(folder: File, root: File): String {
    val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
    val path = runCatching { folder.canonicalPath }.getOrElse { folder.absolutePath }
    if (path == rootPath) return "Internal storage"
    if (!path.startsWith(rootPath + File.separator)) return path
    return "Internal storage / " + path.removePrefix(rootPath + File.separator).replace("/", " / ")
}

/** Keeps prompt boxes short: one plain line, with the small print behind a Details toggle. */
@Composable
private fun ExpandableNote(summary: String, details: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text(if (open) "Hide details" else "Details", style = MaterialTheme.typography.labelLarge)
        }
        if (open) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full-width selectable row used by the Sort prompt box. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(FieldShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Masked field with a Show/Hide toggle; the value is never kept in saved instance state. */
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean = false, supporting: String? = null) {
    var visible by remember { mutableStateOf(false) }
    val helper: (@Composable () -> Unit)? = supporting?.let { text -> { Text(text) } }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true, shape = FieldShape, isError = isError,
        modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = helper,
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
        }
    )
}

// ---------------------------------------------------------------- small dialogs
@Composable
private fun MenuItem(text: String, enabled: Boolean, action: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = action)
}

@Composable
private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    PocketDialog(
        title = title, onDismiss = dismiss, confirmLabel = "Save", confirmEnabled = name.isNotBlank(),
        onConfirm = { submit(name) }, glyph = { FolderGlyph(30.dp) }
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") }, singleLine = true, shape = FieldShape)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveDialog(create: Boolean, current: File, root: File, recent: List<File>, initialName: String,
                          dismiss: () -> Unit, submit: (String, CharArray?, File) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf("ZIP") }
    // Deliberately not rememberSaveable: passwords must never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var destination by remember(current.path) { mutableStateOf(current) }
    var picking by remember { mutableStateOf(false) }
    val encryptedFormat = format != "TAR.GZ"
    val mismatch = create && encryptedFormat && repeat.isNotEmpty() && password != repeat
    val valid = name.isNotBlank() && (!create || !encryptedFormat || password == repeat)
    fun close() { password = ""; repeat = ""; dismiss() }
    fun complete() {
        val chars = if (create && encryptedFormat) password.takeIf { it.isNotEmpty() }?.toCharArray() else null
        val suffix = when (format) { "7Z" -> ".7z"; "TAR.GZ" -> ".tar.gz"; else -> ".zip" }
        val finalName = if (!create || name.endsWith(suffix, true) || (format == "TAR.GZ" && name.endsWith(".tgz", true))) {
            name
        } else {
            name.substringBeforeLast('.', name) + suffix
        }
        password = ""; repeat = ""
        submit(finalName, chars, destination)
    }
    if (picking) {
        FolderPickerDialog(start = destination, root = root, recent = recent, dismiss = { picking = false }) { chosen ->
            destination = chosen; picking = false
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = { close() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 40.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape)
            )
        }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) { ArchiveGlyph(32.dp) }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(if (create) "Create archive" else "Extract archive", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (create) "ZIP, 7Z or TAR.GZ" else "Unpack into a new sub-folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DestinationRow(destination, root) { picking = true }
            if (create) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Archive format", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ZIP", "7Z", "TAR.GZ").forEach { option ->
                            FilterChip(
                                selected = format == option,
                                onClick = {
                                    format = option
                                    if (option == "TAR.GZ") { password = ""; repeat = "" }
                                },
                                label = { Text(option) },
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = FieldShape, label = { Text(if (create) "$format file name" else "Output folder name") },
                leadingIcon = { if (create) ArchiveGlyph(22.dp) else FolderGlyph(22.dp) }
            )
            if (create && encryptedFormat) {
                PasswordField(password, { password = it }, "Password (optional)")
                PasswordField(repeat, { repeat = it }, "Repeat password", isError = mismatch,
                    supporting = if (mismatch) "Passwords do not match." else null)
            } else if (create) {
                Text(
                    "TAR.GZ is unencrypted. Choose 7Z when password protection is needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ExpandableNote(
                summary = if (create) "The $format archive is created in the destination folder."
                    else "Files land in a new sub-folder of the destination folder.",
                details = if (create && encryptedFormat) "A non-empty password enables AES-256. 7Z also encrypts file names; forgotten passwords cannot be recovered."
                    else if (create) "TAR.GZ is a standard unencrypted tar archive compressed with gzip."
                    else "Encrypted archives ask for their password only when needed. Up to 2 GiB output; split volumes and RAR links are unsupported."
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { close() }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = ::complete,
                    enabled = valid,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) { Text(if (create) "Create" else "Extract") }
            }
        }
    }
}

/** RAR-style destination chooser: browse folders under the storage root and pick one. */
@Composable
private fun FolderPickerDialog(start: File, root: File, recent: List<File>, dismiss: () -> Unit, select: (File) -> Unit) {
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
    PocketDialog(
        title = "Choose destination", subtitle = shortPath(folder, root),
        onDismiss = dismiss, confirmLabel = "Use this folder", onConfirm = { select(folder) },
        glyph = { FolderGlyph(32.dp) }
    ) {
        val shortcuts = remember(recent, folder.path) { recent.filter { it.isDirectory && path(it) != path(folder) }.take(5) }
        if (shortcuts.isNotEmpty()) Column {
            Text("Recent destinations", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.forEach { shortcut ->
                    AssistChip(
                        onClick = { folder = shortcut }, shape = RoundedCornerShape(16.dp),
                        leadingIcon = { FolderGlyph(18.dp) },
                        label = { Text(shortcut.name.ifBlank { "Internal storage" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoUp) TextButton(onClick = { parent?.let { folder = it } }) { Text("Up one level") }
            TextButton(onClick = { creating = true }) { Text("New folder\u2026") }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(CardShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                .verticalScroll(rememberScrollState()).padding(6.dp)
        ) {
            when {
                failed -> Text("Cannot read this folder. Check that storage access (All files access) is allowed.",
                    Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                children.isEmpty() -> Text("No sub-folders here.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> children.forEach { child ->
                    Row(
                        Modifier.fillMaxWidth().clip(FieldShape).clickable { folder = child }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FolderGlyph(24.dp)
                        Text(child.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

