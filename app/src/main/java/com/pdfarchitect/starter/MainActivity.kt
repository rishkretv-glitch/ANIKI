package com.pdfarchitect.starter

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import java.io.File
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var incomingDocument by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingDocument = viewIntentUri(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF9BB8FF),
                secondary = Color(0xFF9FE3D0),
                surface = Color(0xFF101114),
                background = Color.Black,
            )) { PdfArchitectApp(incomingDocument) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingDocument = viewIntentUri(intent)
    }

    private fun viewIntentUri(incoming: Intent?): Uri? =
        incoming?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
}

private enum class ExportMode { PRESERVE, FLATTEN }

private data class SaveCommand(
    val uri: Uri, val page: Int, val edits: PageEdits?,
    val confirmation: CompletableDeferred<Unit>? = null,
)

@Composable
private fun PdfArchitectApp(externalDocument: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { InkStore(context.applicationContext) }
    val imageAssets = remember { ImageAssets(context.applicationContext) }
    val recents = remember { RecentDocuments(context.applicationContext) }
    val textSearch = remember { PdfTextSearch(context.applicationContext) }
    val recentItems = remember { mutableStateListOf<RecentDocument>().apply { addAll(recents.list()) } }
    val saveQueue = remember { Channel<SaveCommand>(Channel.UNLIMITED) }

    var requestedUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(externalDocument) {
        if (externalDocument != null) requestedUri = externalDocument
    }
    var session by remember { mutableStateOf<PdfDocumentSession?>(null) }
    var documentTitle by remember { mutableStateOf("PDF Architect") }
    var pageIndex by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<RenderedPage?>(null) }
    var edits by remember { mutableStateOf(PageEdits()) }
    var undoStack by remember { mutableStateOf<List<PageEdits>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<PageEdits>>(emptyList()) }
    var tool by remember { mutableStateOf(EditorTool.PEN) }
    var onlyPen by remember { mutableStateOf(true) }
    var inkColor by remember { mutableIntStateOf(AndroidColor.rgb(35, 112, 237)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var opening by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var exportMode by remember { mutableStateOf(ExportMode.PRESERVE) }
    var showExportChoices by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<PdfSearchHit>?>(null) }
    var showPageJump by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("1") }
    var pendingTextPoint by remember { mutableStateOf<InkPoint?>(null) }
    var pendingImage by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var textInput by remember { mutableStateOf("") }
    var pendingCalibration by remember { mutableStateOf<Segment?>(null) }
    var metersInput by remember { mutableStateOf("5.0") }

    // Exactly one writer preserves mutation order across rapid strokes, Undo and page navigation.
    LaunchedEffect(store, saveQueue) {
        for (request in saveQueue) {
            try {
                request.edits?.let { store.save(request.uri, request.page, it) }
                request.confirmation?.complete(Unit)
            } catch (error: Exception) {
                request.confirmation?.completeExceptionally(error)
                message = "Не удалось сохранить аннотацию: ${error.message}"
            }
        }
    }

    fun enqueueSave(snapshot: PageEdits) {
        val uri = requestedUri ?: return
        if (saveQueue.trySend(SaveCommand(uri, pageIndex, snapshot)).isFailure) {
            message = "Очередь сохранения закрыта"
        }
    }

    fun commit(next: PageEdits) {
        if (next == edits) return
        undoStack = (undoStack + edits).takeLast(50)
        redoStack = emptyList()
        edits = next
        enqueueSave(next)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            requestedUri = uri
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    pendingImage = imageAssets.importImage(uri)
                    tool = EditorTool.IMAGE
                    message = "Коснитесь страницы стилусом, чтобы вставить изображение."
                } catch (error: Exception) {
                    message = "Не удалось импортировать изображение: ${error.message}"
                }
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target ->
        val source = requestedUri
        val active = session
        if (target != null && source != null && active != null) {
            scope.launch {
                exporting = true
                exportProgress = "Подготовка…"
                try {
                    val barrier = CompletableDeferred<Unit>()
                    saveQueue.send(SaveCommand(source, pageIndex, edits, barrier))
                    barrier.await() // Flush all preceding page annotations before reading every page.
                    if (exportMode == ExportMode.PRESERVE) {
                        PreservingPdfExporter(context.applicationContext, store, imageAssets)
                            .export(source, target) { done, all ->
                                exportProgress = "Обработано страниц: $done / $all"
                            }
                        message = "PDF-копия сохранена. Исходный текст и векторные объекты сохранены; новые заметки добавлены поверх. Цифровые подписи требуют повторной проверки."
                    } else {
                        active.exportFlattened(context.applicationContext, source, target, store, imageAssets) { done, all ->
                            exportProgress = "Создано страниц: $done / $all"
                        }
                        message = "PDF-копия сохранена. Текст и объекты уплощены в изображение."
                    }
                } catch (error: Exception) {
                    message = "Ошибка экспорта: ${error.message}"
                } finally {
                    exporting = false
                }
            }
        }
    }

    val managedSession = session
    DisposableEffect(managedSession) {
        onDispose {
            // Rendering holds a lock on an IO worker. Never block the main thread to close it.
            if (managedSession != null) {
                Thread({ managedSession.close() }, "pdf-session-close").start()
            }
        }
    }
    val managedPage = page
    DisposableEffect(managedPage) {
        onDispose { managedPage?.bitmap?.recycle() }
    }

    LaunchedEffect(requestedUri) {
        val uri = requestedUri ?: return@LaunchedEffect
        session = null; page = null; edits = PageEdits(); pendingImage = null
        opening = true
        try {
            val loaded = PdfDocumentSession.open(context.applicationContext, uri)
            val title = withContext(Dispatchers.IO) { fileName(context, uri) }
            documentTitle = title
            pageIndex = 0; zoom = 1f
            recents.add(uri, title)
            recentItems.clear(); recentItems.addAll(recents.list())
            session = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = "Не удалось открыть документ: ${error.message}"
        } finally {
            opening = false
        }
    }

    LaunchedEffect(session, pageIndex) {
        val current = session ?: return@LaunchedEffect
        val uri = requestedUri ?: return@LaunchedEffect
        page = null; edits = PageEdits()
        undoStack = emptyList(); redoStack = emptyList(); zoom = 1f
        rendering = true
        try {
            val bitmap = withContext(Dispatchers.IO) { current.renderPage(pageIndex) }
            try {
                // Wait until earlier page changes are persisted before reading a page again.
                val barrier = CompletableDeferred<Unit>()
                saveQueue.send(SaveCommand(uri, pageIndex, null, barrier))
                barrier.await()
                val annotations = store.load(uri, pageIndex)
                page = bitmap
                edits = annotations
            } catch (error: Throwable) {
                bitmap.bitmap.recycle()
                throw error
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = "Не удалось показать страницу: ${error.message}"
        } finally {
            rendering = false
        }
    }

    if (showSearch && session != null) {
        AlertDialog(
            onDismissRequest = { if (!searching) showSearch = false },
            title = { Text("Поиск в PDF") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = searchQuery,
                    onValueChange = { searchQuery = it.take(100); searchHits = null },
                    label = { Text("Текст для поиска") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (searching) CircularProgressIndicator()
                val hits = searchHits
                if (hits != null) {
                    Text("Найдено совпадений: ${hits.size}")
                    if (hits.isEmpty()) Text("Текст не найден. Для сканированных PDF понадобится OCR.")
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(hits) { hit ->
                            TextButton(onClick = {
                                pageIndex = hit.pageIndex
                                showSearch = false
                            }, modifier = Modifier.fillMaxWidth()) {
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text("Страница ${hit.pageIndex + 1}")
                                    Text(hit.snippet, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            } },
            confirmButton = { Button(enabled = searchQuery.isNotBlank() && !searching,
                onClick = {
                    val source = requestedUri ?: return@Button
                    searching = true
                    scope.launch {
                        try { searchHits = textSearch.search(source, searchQuery) }
                        catch (error: Exception) { message = "Ошибка поиска: ${error.message}" }
                        finally { searching = false }
                    }
                }) { Text("Искать") } },
            dismissButton = { TextButton(enabled = !searching,
                onClick = { showSearch = false }) { Text("Закрыть") } },
        )
    }
    if (showExportChoices) {
        AlertDialog(
            onDismissRequest = { showExportChoices = false },
            title = { Text("Как сохранить PDF?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Рекомендуемый режим сохраняет исходный текст, векторную графику и ссылки. Новые штрихи становятся PDF-графикой; текстовые заметки остаются изображениями.")
                Button(onClick = {
                    showExportChoices = false
                    exportMode = ExportMode.PRESERVE
                    exportPicker.launch(documentTitle.substringBeforeLast('.', documentTitle) + "_edited.pdf")
                }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить с исходным текстом") }
                FilledTonalButton(onClick = {
                    showExportChoices = false
                    exportMode = ExportMode.FLATTEN
                    exportPicker.launch(documentTitle.substringBeforeLast('.', documentTitle) + "_flattened.pdf")
                }, modifier = Modifier.fillMaxWidth()) { Text("Уплощённая копия") }
                Text("Не подходит для редактирования подписанного PDF без повторного подписания.",
                    style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { showExportChoices = false }) { Text("Отмена") } },
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Очистить аннотации страницы?") },
            text = { Text("Удалятся перо, изображения, текст и измерения только на этой странице. Можно отменить сразу после удаления.") },
            confirmButton = { Button(onClick = {
                commit(PageEdits())
                showClearConfirmation = false
            }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) {
                Text("Отмена")
            } },
        )
    }
    if (showPageJump && session != null) {
        AlertDialog(
            onDismissRequest = { showPageJump = false },
            title = { Text("Перейти к странице") },
            text = { OutlinedTextField(value = jumpInput,
                onValueChange = { jumpInput = it.filter(Char::isDigit).take(7) },
                label = { Text("Номер (1–${session?.pageCount ?: 1})") },
                singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val target = jumpInput.toIntOrNull()
                if (target != null && target in 1..(session?.pageCount ?: 1)) {
                    pageIndex = target - 1
                    showPageJump = false
                } else message = "Введите номер в пределах документа"
            }) { Text("Перейти") } },
            dismissButton = { TextButton(onClick = { showPageJump = false }) { Text("Отмена") } },
        )
    }
    if (pendingTextPoint != null) {
        AlertDialog(
            onDismissRequest = { pendingTextPoint = null },
            title = { Text("Добавить текст") },
            text = { OutlinedTextField(
                value = textInput, onValueChange = { textInput = it.take(500) },
                label = { Text("Текст примечания") }, maxLines = 6,
            ) },
            confirmButton = { TextButton(onClick = {
                val p = pendingTextPoint
                if (p != null && textInput.isNotBlank()) {
                    commit(edits.copy(texts = edits.texts + TextStamp(
                        p.x, p.y, textInput.trim(), inkColor
                    )))
                }
                pendingTextPoint = null
            }) { Text("Добавить") } },
            dismissButton = { TextButton(onClick = { pendingTextPoint = null }) { Text("Отмена") } },
        )
    }
    if (pendingCalibration != null) {
        AlertDialog(
            onDismissRequest = { pendingCalibration = null },
            title = { Text("Калибровка масштаба") },
            text = {
                Column {
                    Text("Укажите известную длину проведённого отрезка в метрах.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = metersInput,
                        onValueChange = { metersInput = it.take(20) },
                        label = { Text("Длина, м") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = {
                val length = metersInput.replace(',', '.').toDoubleOrNull()
                val segment = pendingCalibration
                if (length != null && length.isFinite() && length > 0 && segment != null) {
                    commit(edits.copy(calibration = Calibration(segment, length)))
                    pendingCalibration = null
                    tool = EditorTool.MEASURE
                } else message = "Введите положительное число, например 5.00"
            }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { pendingCalibration = null }) { Text("Отмена") } },
        )
    }
    if (message != null) {
        AlertDialog(onDismissRequest = { message = null },
            title = { Text("PDF Architect") },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(documentTitle, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = !exporting,
                        onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Открыть") }
                    if (session != null) {
                        TextButton(enabled = !exporting, onClick = { showSearch = true }) { Text("Поиск") }
                        Button(enabled = !exporting && page != null,
                            onClick = { showExportChoices = true }) { Text("Экспорт") }
                    }
                }
            }
        },
        bottomBar = {
            if (session != null) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            EditorTool.entries.forEach { candidate ->
                                FilterChip(selected = tool == candidate,
                                    enabled = !exporting,
                                    onClick = {
                                        if (candidate == EditorTool.IMAGE && pendingImage == null) {
                                            imagePicker.launch("image/*")
                                        } else tool = candidate
                                    },
                                    label = { Text(candidate.title) })
                            }
                            TextButton(enabled = !exporting,
                                onClick = { showClearConfirmation = true }) {
                                Text("Очистить страницу")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(enabled = undoStack.isNotEmpty() && !exporting,
                                onClick = {
                                    val previous = undoStack.last()
                                    undoStack = undoStack.dropLast(1)
                                    redoStack = redoStack + edits
                                    edits = previous; enqueueSave(previous)
                                }) { Text("↶") }
                            TextButton(enabled = redoStack.isNotEmpty() && !exporting,
                                onClick = {
                                    val next = redoStack.last()
                                    redoStack = redoStack.dropLast(1)
                                    undoStack = undoStack + edits
                                    edits = next; enqueueSave(next)
                                }) { Text("↷") }
                            listOf(AndroidColor.rgb(35, 112, 237), AndroidColor.rgb(214, 65, 75),
                                AndroidColor.BLACK).forEachIndexed { idx, c ->
                                FilterChip(selected = inkColor == c, onClick = { inkColor = c },
                                    label = { Text(listOf("Син.", "Крас.", "Чёрн.")[idx]) })
                            }
                            Spacer(Modifier.weight(1f))
                            Text("S Pen", style = MaterialTheme.typography.labelSmall)
                            Switch(checked = onlyPen, onCheckedChange = { onlyPen = it })
                        }
                        if (tool == EditorTool.AREA) {
                            Text("Площадь: отмечайте углы стилусом, затем коснитесь первой точки.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = pageIndex > 0 && !exporting,
                                onClick = { pageIndex-- }) { Text("‹ Стр.") }
                            TextButton(onClick = {
                                jumpInput = (pageIndex + 1).toString()
                                showPageJump = true
                            }, enabled = !exporting) {
                                Text("${pageIndex + 1} / ${session?.pageCount ?: 0}")
                            }
                            TextButton(enabled = pageIndex < (session?.pageCount ?: 1) - 1 && !exporting,
                                onClick = { pageIndex++ }) { Text("Стр. ›") }
                            TextButton(enabled = zoom > 1.01f,
                                onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Text("−") }
                            Text("${(zoom * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium)
                            TextButton(enabled = zoom < 4.99f,
                                onClick = { zoom = (zoom + 0.5f).coerceAtMost(5f) }) { Text("+") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val displayedPage = page
            val active = session
            when {
                opening || rendering || exporting -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(if (exporting) exportProgress else "Обработка документа…")
                    }
                }
                active != null && displayedPage != null -> {
                    key(active, pageIndex) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { InkPageView(it) },
                            update = { view ->
                                view.image = displayedPage.bitmap
                                view.pageWidthPt = displayedPage.widthPt
                                view.pageHeightPt = displayedPage.heightPt
                                view.edits = edits
                                view.tool = tool
                                view.stylusOnly = onlyPen
                                view.inkColor = inkColor
                                view.resolveImage = imageAssets::load
                                view.zoom = zoom
                                view.onZoomChanged = { zoom = it }
                                view.onStroke = { stroke ->
                                    commit(edits.copy(strokes = edits.strokes + stroke))
                                }
                                view.onEraseStroke = { index ->
                                    if (index in edits.strokes.indices) {
                                        commit(edits.copy(strokes = edits.strokes.toMutableList().apply {
                                            removeAt(index)
                                        }))
                                    }
                                }
                                view.onTextTap = { point ->
                                    textInput = ""; pendingTextPoint = point
                                }
                                view.onImageTap = { point ->
                                    val img = pendingImage
                                    if (img == null) imagePicker.launch("image/*")
                                    else {
                                        val (name, aspect) = img
                                        val width = 0.33f
                                        val height = (width * displayedPage.widthPt /
                                            (displayedPage.heightPt * aspect)).coerceIn(0.04f, 0.65f)
                                        commit(edits.copy(images = edits.images + ImageStamp(
                                            name, point.x.coerceAtMost(1f - width),
                                            point.y.coerceAtMost(1f - height), width, height
                                        )))
                                        pendingImage = null
                                        tool = EditorTool.PEN
                                    }
                                }
                                view.onArea = { area ->
                                    commit(edits.copy(areas = edits.areas + area))
                                    if (edits.calibration == null) {
                                        message = "Площадь сохранена. Задайте масштаб для расчёта в м²."
                                    }
                                }
                                view.onSegment = { segment, calibration ->
                                    if (calibration) {
                                        metersInput = "5.0"
                                        pendingCalibration = segment
                                    } else if (edits.calibration == null) {
                                        message = "Сначала выберите «Масштаб» и проведите эталонный отрезок."
                                    } else {
                                        commit(edits.copy(measurements = edits.measurements + segment))
                                    }
                                }
                            },
                        )
                    }
                }
                active == null -> {
                    Column(Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("PDF Architect Ultra", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("PDF, S Pen, измерения, офлайн-пометки")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                            Text("Выбрать PDF")
                        }
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = {
                            scope.launch {
                                try {
                                    val demo = withContext(Dispatchers.IO) {
                                        val target = File(context.filesDir, "Engineering_Demo.pdf")
                                        if (!target.isFile || target.length() == 0L) {
                                            context.assets.open("Engineering_Demo.pdf").use { source ->
                                                target.outputStream().use { dest -> source.copyTo(dest) }
                                            }
                                        }
                                        Uri.fromFile(target)
                                    }
                                    requestedUri = demo
                                } catch (error: Exception) {
                                    message = "Не удалось открыть демо: ${error.message}"
                                }
                            }
                        }) { Text("Попробовать на демо-чертежах") }
                        if (recentItems.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Text("Недавние документы", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            LazyColumn {
                                items(recentItems, key = { it.uri.toString() }) { recent ->
                                    FilledTonalButton(
                                        onClick = { requestedUri = recent.uri },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(recent.title, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fileName(context: Context, uri: Uri): String = runCatching {
    if (uri.scheme == "file") return@runCatching File(uri.path ?: "").name
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull() ?: "Документ.pdf"
