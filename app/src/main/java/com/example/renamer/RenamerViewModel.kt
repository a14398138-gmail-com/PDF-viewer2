package com.example.renamer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RenamerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val prefs = application.getSharedPreferences("renamer_folder_settings", 0)
    private val scanner = PdfScanner(application)
    var directory by mutableStateOf<DocumentFile?>(null)
        private set
    var items by mutableStateOf<List<PaperCandidate>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("PDF自体は送信せず、抽出したDOIのみCrossrefに送信します。")
        private set

    init {
        PDFBoxResourceLoader.init(application)
        prefs.getString("folder_uri", null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }) {
                directory = DocumentFile.fromTreeUri(context, uri)
                scan()
            } else {
                status = "フォルダへの読み書きアクセスを再許可してください。"
            }
        }
    }

    fun selectFolder(uri: Uri) {
        if (busy) return
        try {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            directory = DocumentFile.fromTreeUri(context, uri)
            prefs.edit().putString("folder_uri", uri.toString()).apply()
            scan()
        } catch (e: Exception) {
            status = "フォルダへのアクセスを許可できません: ${e.message}"
        }
    }

    fun select(index: Int, checked: Boolean) {
        if (!busy) items = items.mapIndexed { i, item ->
            if (i == index && item.newName != null) item.copy(selected = checked) else item
        }
    }

    fun scan(force: Boolean = false) {
        val dir = directory ?: return
        if (busy) return
        busy = true
        items = emptyList()
        viewModelScope.launch {
            try {
                items = scanner.scan(dir, force) { current, total ->
                    withContext(Dispatchers.Main) { status = "確認中: $current / $total" }
                }
                status = "未処理 ${items.size}件、${items.count { it.newName != null }}件を変更できます。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "解析に失敗しました: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun renameSelected() {
        val dir = directory ?: return
        if (busy) return
        val selected = items.filter { it.selected && it.newName != null }
        if (selected.isEmpty()) return
        busy = true
        viewModelScope.launch {
            try {
                val completed = mutableSetOf<PaperCandidate>()
                var skipped = 0
                var warnings = 0
                withContext(Dispatchers.IO) {
                    // Snapshot the folder listing once (name -> uri) instead of
                    // re-listing per candidate, which made renaming O(n^2) in
                    // large folders. The snapshot is updated after each rename,
                    // so collisions with earlier batch results are still caught.
                    val takenNames = mutableMapOf<String, Uri>()
                    dir.listFiles().forEach { f ->
                        val n = f.name?.lowercase()
                        if (n != null) takenNames[n] = f.uri
                    }
                    for (candidate in selected) {
                        val target = candidate.newName ?: continue
                        val oldUri = candidate.file.uri
                        try {
                            val holder = takenNames[target.lowercase()]
                            val collision = holder != null && holder != oldUri
                            if (collision || candidate.file.name != candidate.originalName ||
                                !candidate.file.renameTo(target)) {
                                skipped++
                                continue
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            skipped++
                            continue
                        }
                        takenNames.remove(candidate.originalName.lowercase())
                        takenNames[target.lowercase()] = candidate.file.uri
                        completed.add(candidate)
                        // A provider may change the document URI on rename.
                        try {
                            reconcileHistory(oldUri, candidate.file.uri, candidate.file.name ?: target)
                            scanner.rememberRenamed(candidate.file)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            warnings++
                        }
                    }
                }
                items = items.filterNot { it in completed }
                status = "${completed.size}件を変更、$skipped 件をスキップしました。" +
                    if (warnings > 0) " $warnings 件で履歴の更新に失敗しました。" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "リネームに失敗しました: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun reconcileHistory(oldUri: Uri, newUri: Uri, name: String) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.pdfHistoryDao()
        db.withTransaction {
            // OpenDocument and OpenDocumentTree can refer to the same file via different URIs.
            for (item in dao.getAllHistorySync()) {
                val uri = Uri.parse(item.uriString)
                val same = uri == oldUri || (uri.authority == oldUri.authority &&
                    runCatching { DocumentsContract.getDocumentId(uri) ==
                        DocumentsContract.getDocumentId(oldUri) }.getOrDefault(false))
                if (same) {
                    val replacement = if (uri == oldUri) newUri else
                        DocumentsContract.buildDocumentUri(newUri.authority,
                            DocumentsContract.getDocumentId(newUri))
                    dao.updateRenamedDocument(item.id, replacement.toString(), name)
                }
            }
        }
    }
}
