@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabrydl99.gfc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gabrydl99.gfc.BuildConfig
import com.gabrydl99.gfc.core.Diagnoses
import com.gabrydl99.gfc.core.Ffmpeg
import com.gabrydl99.gfc.core.Kind
import com.gabrydl99.gfc.core.MB
import com.gabrydl99.gfc.core.Mode
import com.gabrydl99.gfc.core.Preset
import com.gabrydl99.gfc.core.Settings
import com.gabrydl99.gfc.core.Updates
import com.gabrydl99.gfc.core.Workspace
import com.gabrydl99.gfc.core.extOf
import com.gabrydl99.gfc.core.humanSize
import com.gabrydl99.gfc.work.CompressService
import com.gabrydl99.gfc.work.FileItem
import com.gabrydl99.gfc.work.RunState
import com.gabrydl99.gfc.work.Status
import com.gabrydl99.gfc.work.WorkRepo
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    activity: MainActivity,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onCompress: () -> Unit,
    onAbort: () -> Unit,
    onChangeFolder: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onAllowFiles: () -> Unit,
) {
    val items by WorkRepo.items.collectAsStateWithLifecycle()
    val settings by WorkRepo.settings.collectAsStateWithLifecycle()
    val run by WorkRepo.run.collectAsStateWithLifecycle()
    val allFiles by activity.allFiles.collectAsStateWithLifecycle()
    val encoders by Ffmpeg.encodersFlow.collectAsStateWithLifecycle()
    var why by remember { mutableStateOf<FileItem?>(null) }
    var sheet by remember { mutableStateOf<FileItem?>(null) }
    val p = LocalPalette.current
    val locked = run.running

    Scaffold(
        containerColor = p.canvas,
        bottomBar = { ActionBar(items, run, onCompress, onAbort, onOpenFolder) },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val wide = maxWidth >= 720.dp
            val content = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
            val filesPart: LazyListScope.() -> Unit = {
                item { Header() }
                item {
                    FilesBlock(items, settings, locked, onAddFiles, onAddFolder,
                        onRow = { sheet = it }, onWhy = { why = it }, onRemove = { WorkRepo.remove(it.id) })
                }
            }
            val settingsPart: LazyListScope.() -> Unit = {
                if (!allFiles) item { PermissionCard(onAllowFiles) }
                item { PresetsBlock(settings, locked) }
                item { ModeBlock(settings, locked) }
                item { FormatsBlock(settings, locked, encoders) }
                item { VideoBlock(settings, locked) }
                item { PdfBlock(settings, locked) }
                item { SaveBlock(settings, locked, onChangeFolder, onOpenFolder) }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LazyColumn(Modifier.weight(1f), contentPadding = content,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { filesPart() }
                    LazyColumn(Modifier.weight(1f).widthIn(max = 560.dp), contentPadding = content,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { settingsPart() }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = content,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    filesPart(); settingsPart()
                }
            }
        }
    }

    why?.let { WhyDialog(it) { why = null } }
    UpdateDialog(activity)
    sheet?.let { s ->
        val live = items.firstOrNull { it.id == s.id } ?: s
        ItemSheet(live, locked,
            onDismiss = { sheet = null },
            onOpen = { f -> sheet = null; onOpenFile(f) },
            onShare = { f -> sheet = null; onShareFile(f) },
            onWhy = { sheet = null; why = live },
            onRetry = { sheet = null; WorkRepo.resetItem(live.id) },
            onRemove = { sheet = null; WorkRepo.remove(live.id) })
    }
}

// ------------------------------------------------------------------ pieces

@Composable
fun Logo(size: Int) {
    val cobalt = LocalPalette.current.cobalt
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width / 64f
        drawRoundRect(cobalt, Offset(2 * s, 2 * s), Size(60 * s, 60 * s), CornerRadius(14 * s, 14 * s))
        listOf(40f, 28f, 16f).forEachIndexed { i, w ->
            drawRoundRect(Color.White, Offset(12 * s, (13 + i * 14) * s), Size(w * s, 10 * s), CornerRadius(3 * s, 3 * s))
        }
    }
}

@Composable
private fun Header() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Logo(44)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("General File Compressor", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            Text("Videos, photos, PDFs, audio and Office files — offline",
                style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
    }
}

@Composable
fun Block(title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Surface(shape = RoundedCornerShape(20.dp), color = p.block, border = BorderStroke(1.dp, p.line),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun Caption(text: String, color: Color? = null) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color ?: LocalPalette.current.muted)
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.cobalt, contentColor = p.onCobalt)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, icon: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, p.line), contentPadding = PaddingValues(horizontal = 14.dp)) {
        if (icon) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = p.cobalt, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) p.ink else p.muted)
    }
}

@Composable
private fun Tile(item: FileItem) {
    val label = extOf(item.name).uppercase(Locale.ROOT).take(4).ifEmpty { "FILE" }
    Box(Modifier.size(46.dp).background(kindColor(item.kind), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (label.length > 3) 11.sp else 13.sp)
    }
}

fun expectedExt(name: String, kind: Kind, s: Settings): String {
    val ext = extOf(name)
    return when (kind) {
        Kind.VIDEO -> if (s.videoFormat != "keep") s.videoFormat else if (ext in listOf("mp4", "mkv", "mov", "webm")) ext else "mp4"
        Kind.IMAGE -> if (s.imageFormat != "keep") s.imageFormat else if (ext in listOf("jpg", "jpeg", "png", "webp")) ext else "jpg"
        Kind.AUDIO -> if (s.audioFormat != "keep") s.audioFormat else if (ext in listOf("mp3", "m4a", "ogg", "opus", "aac")) ext else "mp3"
        else -> ext
    }
}

private fun metaLine(it: FileItem, s: Settings): String {
    val out = expectedExt(it.name, it.kind, s).uppercase(Locale.ROOT)
    val src = extOf(it.name).uppercase(Locale.ROOT)
    val fmt = if (out != src && out.isNotEmpty()) "$src → $out" else src
    val r = it.result
    return when (it.status) {
        Status.DONE -> if (r != null && r.newSize > 0) {
            val pct = (r.savedRatio * 100).roundToInt()
            "${humanSize(r.originalSize)} → ${humanSize(r.newSize)}" + (if (pct > 0) " (−$pct%)" else "") +
                (r.notes.firstOrNull()?.let { n -> " · $n" } ?: "")
        } else "Done"
        Status.FAILED -> r?.message ?: "Failed"
        Status.WORKING -> it.stage.ifEmpty { "Working" }
        Status.STOPPED -> it.stage.ifEmpty { "Stopped" }
        Status.WAITING -> when {
            s.convertOnly -> "${humanSize(it.size)}, $fmt"
            s.mode == Mode.MB -> "${humanSize(it.size)} → about ${humanSize(minOf(it.size.toDouble(), s.targetMb * MB))}, $fmt"
            else -> "${humanSize(it.size)} → about ${humanSize(it.size * s.percent / 100.0)}, $fmt"
        }
    }
}

@Composable
private fun FileRow(item: FileItem, s: Settings, locked: Boolean, onClick: () -> Unit, onWhy: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val (statusText, statusColor) = when (item.status) {
        Status.WAITING -> "Waiting" to p.muted
        Status.WORKING -> "${(item.progress * 100).roundToInt()}%" to p.cobalt
        Status.DONE -> "Done" to p.success
        Status.FAILED -> if (CompressService.isSkip(item.result?.message ?: "")) "Skipped" to p.muted else "Failed" to p.danger
        Status.STOPPED -> "Stopped" to p.muted
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Tile(item)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            Text(metaLine(item, s), style = MaterialTheme.typography.bodySmall, color = if (item.status == Status.FAILED) p.danger else p.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val prog = when (item.status) { Status.DONE -> 1f; Status.WORKING -> item.progress; else -> 0f }
            LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (item.status == Status.FAILED) p.danger else kindColor(item.kind), trackColor = p.sunk,
                drawStopIndicator = {})
            if (item.status == Status.FAILED) {
                TextButton(onClick = onWhy, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                    Text("Why did it fail?", style = MaterialTheme.typography.labelMedium, color = p.cobalt)
                }
            }
        }
        if (!locked || item.status != Status.WORKING) {
            IconButton(onClick = onRemove, enabled = item.status != Status.WORKING, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove from the list", tint = p.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FilesBlock(
    items: List<FileItem>, s: Settings, locked: Boolean, onAddFiles: () -> Unit, onAddFolder: () -> Unit,
    onRow: (FileItem) -> Unit, onWhy: (FileItem) -> Unit, onRemove: (FileItem) -> Unit,
) {
    val p = LocalPalette.current
    val total = items.sumOf { it.size }
    Block("Files", trailing = {
        if (items.isNotEmpty()) Text("${items.size} · ${humanSize(total)}", style = MaterialTheme.typography.labelMedium, color = p.muted)
    }) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().background(p.sunk, RoundedCornerShape(16.dp)).clickable(onClick = onAddFiles)
                .padding(vertical = 26.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).background(p.cobalt, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Tap to add files", style = MaterialTheme.typography.titleSmall, color = p.ink)
                    Text("Or use Share → General File Compressor from the Gallery, WhatsApp or Files",
                        style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
        } else {
            items.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(color = p.line)
                FileRow(it, s, locked, onClick = { onRow(it) }, onWhy = { onWhy(it) }, onRemove = { onRemove(it) })
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Add files", icon = true, onClick = onAddFiles)
            SecondaryButton("Add folder", icon = true, onClick = onAddFolder)
            if (items.isNotEmpty()) SecondaryButton("Clear list", enabled = !locked, onClick = { WorkRepo.clear() })
        }
    }
}

@Composable
private fun PermissionCard(onAllow: () -> Unit) {
    val p = LocalPalette.current
    Surface(shape = RoundedCornerShape(20.dp), color = p.cobalt.copy(alpha = 0.10f), border = BorderStroke(1.dp, p.cobalt.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = p.cobalt, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Allow access to all files", style = MaterialTheme.typography.titleSmall, color = p.ink)
            }
            Spacer(Modifier.height(6.dp))
            Caption("Needed to move originals into the Original folder, to save in any folder and to read big videos without copying them first.")
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Allow", onClick = onAllow)
        }
    }
}

@Composable
private fun PresetsBlock(s: Settings, locked: Boolean) {
    val p = LocalPalette.current
    Block("Quick presets") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Preset.entries.forEach { pr ->
                FilterChip(selected = s.preset == pr.name, enabled = !locked,
                    onClick = { WorkRepo.updateSettings { pr.apply(it) } },
                    label = { Text(pr.label, style = MaterialTheme.typography.labelLarge) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.cobalt,
                        selectedLabelColor = p.onCobalt, labelColor = p.ink, containerColor = p.sunk))
            }
        }
        Spacer(Modifier.height(8.dp))
        Caption(Preset.entries.firstOrNull { it.name == s.preset }?.description
            ?: "Pick where the files are going, or set everything yourself below.")
    }
}

private fun manual(f: (Settings) -> Settings) = WorkRepo.updateSettings { f(it).copy(preset = null) }

@Composable
private fun ModeBlock(s: Settings, locked: Boolean) {
    val p = LocalPalette.current
    Block("How small") {
        val modes = listOf(Mode.PERCENT to "Percentage", Mode.MB to "Size in MB", Mode.CONVERT to "Convert only")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(selected = s.mode == m, onClick = { manual { it.copy(mode = m) } }, enabled = !locked,
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = p.cobalt, activeContentColor = p.onCobalt,
                        inactiveContainerColor = p.sunk, inactiveContentColor = p.ink, activeBorderColor = p.cobalt,
                        inactiveBorderColor = p.line),
                    icon = {}) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (s.mode) {
            Mode.PERCENT -> {
                var v by remember(s.percent) { mutableFloatStateOf(s.percent.toFloat()) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${v.roundToInt()}%", style = MaterialTheme.typography.displaySmall, color = p.cobalt)
                    Spacer(Modifier.width(10.dp))
                    Caption("of the original size", p.muted)
                }
                Slider(value = v, onValueChange = { v = it }, valueRange = 5f..95f, enabled = !locked,
                    onValueChangeFinished = { manual { it.copy(percent = v.roundToInt()) } },
                    colors = SliderDefaults.colors(thumbColor = p.cobalt, activeTrackColor = p.cobalt, inactiveTrackColor = p.line))
                Row { Caption("Smallest"); Spacer(Modifier.weight(1f)); Caption("Best quality") }
                Spacer(Modifier.height(6.dp))
                val gb = 1024.0 * MB
                Caption("At ${v.roundToInt()}%, a 1 GB video becomes about ${humanSize(gb * v.roundToInt() / 100)}.")
            }
            Mode.MB -> {
                var text by remember(s.targetMb) { mutableStateOf(fmtMb(s.targetMb)) }
                OutlinedTextField(value = text, enabled = !locked, singleLine = true,
                    onValueChange = { t ->
                        text = t.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7)
                        text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { mb -> manual { it.copy(targetMb = mb) } }
                    },
                    label = { Text("Final size of each file (MB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8.0, 15.0, 25.0, 50.0, 100.0).forEach { mb ->
                        FilterChip(selected = s.targetMb == mb, enabled = !locked, onClick = { manual { it.copy(targetMb = mb) } },
                            label = { Text("${fmtMb(mb)} MB") }, shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.cobalt,
                                selectedLabelColor = p.onCobalt, containerColor = p.sunk))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Caption("Every file is brought to about this size. Files already smaller are skipped.")
            }
            Mode.CONVERT -> Caption("Files change format without aiming for a size. Pick the formats below. " +
                "When the container allows it (for example MKV → MP4) the video isn't re-encoded, so no quality is lost. " +
                "PDF and Office files can't be converted and will be skipped.")
        }
    }
}

private fun fmtMb(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.weight(1f))
        Box {
            Surface(onClick = { if (enabled) open = true }, shape = RoundedCornerShape(12.dp), color = p.sunk,
                border = BorderStroke(1.dp, p.line)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(options.firstOrNull { it.first == value }?.second ?: value, style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) p.ink else p.muted)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = p.muted)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (k, v) ->
                    DropdownMenuItem(text = { Text(v, fontWeight = if (k == value) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onPick(k) })
                }
            }
        }
    }
}

@Composable
private fun FormatsBlock(s: Settings, locked: Boolean, encoders: Set<String>) {
    Block("Formats") {
        val video = mutableListOf("keep" to "Keep original", "mp4" to "MP4", "mkv" to "MKV", "mov" to "MOV")
        if ("libvpx-vp9" in encoders) video += "webm" to "WebM"
        video += listOf("mp3" to "MP3 (audio only)", "m4a" to "M4A (audio only)")
        Choice("Videos", s.videoFormat, video, !locked) { v -> manual { it.copy(videoFormat = v) } }
        Choice("Photos", s.imageFormat, listOf("keep" to "Keep original", "jpg" to "JPG", "png" to "PNG", "webp" to "WebP"), !locked) { v ->
            manual { it.copy(imageFormat = v) }
        }
        Choice("Audio", s.audioFormat, listOf("keep" to "Keep original", "mp3" to "MP3", "m4a" to "M4A", "ogg" to "OGG", "opus" to "Opus"), !locked) { v ->
            manual { it.copy(audioFormat = v) }
        }
        Spacer(Modifier.height(4.dp))
        Caption("PDF and Office files keep their format.")
    }
}

@Composable
private fun VideoBlock(s: Settings, locked: Boolean) {
    Block("Video") {
        Choice("Resolution", s.resolution, listOf("auto" to "Auto", "original" to "Original", "2160" to "2160p (4K)",
            "1440" to "1440p", "1080" to "1080p", "720" to "720p", "480" to "480p", "360" to "360p"), !locked) { v ->
            manual { it.copy(resolution = v) }
        }
        Choice("Frame rate", s.fps, listOf("auto" to "Auto", "original" to "Original", "60" to "60 fps", "30" to "30 fps",
            "25" to "25 fps", "24" to "24 fps", "15" to "15 fps"), !locked) { v -> manual { it.copy(fps = v) } }
        Choice("Speed", s.speed, listOf("fast" to "Fast", "balanced" to "Balanced", "best" to "Best quality"), !locked) { v ->
            manual { it.copy(speed = v) }
        }
        Spacer(Modifier.height(4.dp))
        Caption("Auto lowers resolution and frame rate only when the size you picked needs it. " +
            "Best quality is slower and uses more battery.")
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.cobalt, checkedThumbColor = Color.White,
                uncheckedTrackColor = p.sunk, uncheckedThumbColor = p.muted, uncheckedBorderColor = p.muted))
    }
}

@Composable
private fun PdfBlock(s: Settings, locked: Boolean) {
    Block("PDF") {
        SwitchRow("Rebuild scanned pages as pictures", s.pdfAsPictures, !locked) { v -> manual { it.copy(pdfAsPictures = v) } }
        Spacer(Modifier.height(4.dp))
        Caption("Off: the pictures and scans inside are re-compressed and the text stays selectable. " +
            "On: every page becomes one picture — smallest for scans, but the text can't be selected anymore.")
    }
}

@Composable
private fun SaveBlock(s: Settings, locked: Boolean, onChange: () -> Unit, onOpen: () -> Unit) {
    val p = LocalPalette.current
    Block("Save to") {
        val root = s.outputDir ?: Workspace.defaultRoot().absolutePath
        val shown = root.removePrefix("/storage/emulated/0/").removePrefix("/storage/emulated/0")
        Text(shown.ifEmpty { "Phone storage" } + if (s.outputDir == null) "  (default)" else "",
            style = MaterialTheme.typography.bodyMedium, color = p.ink)
        Caption("Results go in Compressed as name_compressed" +
            if (s.moveOriginals) ", originals in Original." else ", originals stay where they are.")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Change folder", enabled = !locked, onClick = onChange)
            if (s.outputDir != null) SecondaryButton("Use default", enabled = !locked,
                onClick = { WorkRepo.updateSettings { it.copy(outputDir = null) } })
            SecondaryButton("Open folder", onClick = onOpen)
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow("Move originals to the Original folder", s.moveOriginals, !locked) { v ->
            WorkRepo.updateSettings { it.copy(moveOriginals = v) }
        }
        Caption("Originals are moved only after their compressed copy is saved.")
    }
}

@Composable
private fun ActionBar(items: List<FileItem>, run: RunState, onCompress: () -> Unit, onAbort: () -> Unit, onOpenFolder: () -> Unit) {
    val p = LocalPalette.current
    val pending = items.count { it.status == Status.WAITING || it.status == Status.STOPPED }
    Surface(color = p.block, border = BorderStroke(1.dp, p.line), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (run.running) {
                val cur = items.firstOrNull { it.status == Status.WORKING }
                val overall = if (run.total > 0) ((run.index - 1).coerceAtLeast(0) + (cur?.progress ?: 0f)) / run.total else 0f
                LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = p.cobalt, trackColor = p.sunk, drawStopIndicator = {})
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when {
                    run.aborting && run.running -> "Stopping…"
                    run.running -> "File ${run.index} of ${run.total}" +
                        (items.firstOrNull { it.status == Status.WORKING }?.let { " · ${it.stage}" } ?: "")
                    run.summary.isNotEmpty() && pending == 0 && items.isNotEmpty() -> run.summary
                    pending > 0 -> "$pending file${if (pending == 1) "" else "s"} ready"
                    else -> "Add some files to get started."
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                when {
                    run.running -> OutlinedButton(onClick = onAbort, enabled = !run.aborting, shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, p.danger), modifier = Modifier.height(48.dp)) {
                        Text(if (run.aborting) "Aborting…" else "Abort", color = p.danger, style = MaterialTheme.typography.labelLarge)
                    }
                    pending == 0 && items.any { it.status == Status.DONE } ->
                        PrimaryButton("Open folder", onClick = onOpenFolder)
                    else -> PrimaryButton("Compress", enabled = pending > 0, onClick = onCompress)
                }
            }
        }
    }
}

@Composable
private fun WhyDialog(item: FileItem, onClose: () -> Unit) {
    val msg = item.result?.message ?: ""
    val d = Diagnoses.diagnose(msg)
    val p = LocalPalette.current
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        title = { Text(d.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text("What happened", fontWeight = FontWeight.Bold, color = p.ink)
                Text(msg, color = p.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("Possible causes", fontWeight = FontWeight.Bold, color = p.ink)
                d.causes.forEach { Text("• $it", color = p.muted, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(10.dp))
                Text("What to try", fontWeight = FontWeight.Bold, color = p.ink)
                d.tips.forEach { Text("• $it", color = p.muted, style = MaterialTheme.typography.bodySmall) }
            }
        })
}

@Composable
private fun ItemSheet(
    item: FileItem, locked: Boolean, onDismiss: () -> Unit, onOpen: (File) -> Unit, onShare: (File) -> Unit,
    onWhy: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.block) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tile(item)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(humanSize(item.size), style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
            val r = item.result
            if (r != null) {
                Spacer(Modifier.height(12.dp))
                if (r.ok && r.output != null) {
                    Text("Saved as ${r.output.name} (${humanSize(r.newSize)})", style = MaterialTheme.typography.bodySmall, color = p.ink)
                }
                if (!r.ok) Text(r.message, style = MaterialTheme.typography.bodySmall, color = p.danger)
                r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = p.muted) }
                r.originalMovedTo?.let { Text("Original moved to ${it.substringAfterLast("/Download/", it)}", style = MaterialTheme.typography.bodySmall, color = p.muted) }
            }
            Spacer(Modifier.height(16.dp))
            val out = r?.output?.takeIf { it.exists() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (out != null) {
                    PrimaryButton("Open result", modifier = Modifier.fillMaxWidth()) { onOpen(out) }
                    SecondaryButton("Share result", modifier = Modifier.fillMaxWidth()) { onShare(out) }
                }
                if (item.status == Status.FAILED) SecondaryButton("Why did it fail?", modifier = Modifier.fillMaxWidth(), onClick = onWhy)
                if (!locked && (item.status == Status.FAILED || item.status == Status.DONE)) {
                    SecondaryButton("Do it again with the current settings", modifier = Modifier.fillMaxWidth(), onClick = onRetry)
                }
                if (item.status != Status.WORKING) SecondaryButton("Remove from the list", modifier = Modifier.fillMaxWidth(), onClick = onRemove)
            }
        }
    }
}

@Composable
private fun UpdateDialog(activity: MainActivity) {
    val st by Updates.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    LaunchedEffect(st) {
        val s = st
        if (s is Updates.State.Ready) activity.installUpdate(s.info, s.file)
    }
    when (val s = st) {
        is Updates.State.Available -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text("Update available", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("General File Compressor ${s.info.version} is ready to install. You have ${BuildConfig.VERSION_NAME}.",
                        style = MaterialTheme.typography.bodyMedium, color = p.ink)
                    if (s.info.size > 0) Caption("Download: ${humanSize(s.info.size)}")
                    if (s.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(s.info.notes, style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { activity.startUpdate(s.info) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text("Later") } })
        is Updates.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading the update", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = p.cobalt, trackColor = p.sunk, drawStopIndicator = {})
                    Spacer(Modifier.height(10.dp))
                    Caption("${(s.progress * 100).roundToInt()}%" + if (s.info.size > 0) " of ${humanSize(s.info.size)}" else "")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text("Cancel") } })
        is Updates.State.NeedsPermission -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text("One more step", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text("Allow General File Compressor to install the update: turn on \"Allow from this source\", " +
                    "then come back here.", style = MaterialTheme.typography.bodyMedium, color = p.ink)
            },
            confirmButton = { TextButton(onClick = { activity.installUpdate(s.info, s.file) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text("Later") } })
        is Updates.State.Failed -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text("The update didn't download", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text("${s.message}\n\nYou can download it from its GitHub page instead.",
                    style = MaterialTheme.typography.bodyMedium, color = p.ink)
            },
            confirmButton = { TextButton(onClick = { Updates.dismiss(); activity.openPage(s.info.pageUrl) }) { Text("Open the page") } },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text("Close") } })
        else -> {}
    }
}
