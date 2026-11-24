package com.questfilemanager.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Quest File Manager: SAF-based file manager and archive/APK browser.
 * It deliberately uses Android's document picker and does not attempt to bypass protected storage.
 */
class MainActivity : Activity() {
    private val bg = Color.rgb(23, 25, 29)
    private val panel = Color.rgb(34, 37, 43)
    private val panel2 = Color.rgb(43, 47, 55)
    private val text = Color.rgb(238, 240, 243)
    private val muted = Color.rgb(145, 153, 165)
    private val accent = Color.rgb(190, 197, 208)

    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private var page = Page.FILES
    private var rootFolder: DocumentFile? = null
    private val stack = mutableListOf<DocumentFile>()
    private var clipboard: DocumentFile? = null
    private var clipboardMove = false
    private var selectedApk: Uri? = null
    private var selectedApkName = "No APK selected"
    private var archiveUri: Uri? = null
    private var archiveName = ""
    private var archiveEntries = listOf<String>()
    private var pendingExportEntry = ""
    private var pendingPatchSource: Uri? = null
    private var pendingPatchName = ""
    private var pendingPatchPath = ""

    private enum class Page { FILES, APK, TOOLS }
    private val reqTree = 100
    private val reqOpenApk = 101
    private val reqOpenArchive = 102
    private val reqExport = 103
    private val reqPatchFile = 104
    private val reqPatchOutput = 105

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        render()
    }

    private fun render() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(12))
        }
        title = label("Files", 27, text, true)
        subtitle = label("QUEST FILE MANAGER", 11, muted, true)
        header.addView(title)
        header.addView(subtitle)
        outer.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(5), dp(14), dp(9))
        }
        tabs.addView(tab("Files", Page.FILES))
        tabs.addView(tab("APK", Page.APK))
        tabs.addView(tab("Tools", Page.TOOLS))
        outer.addView(tabs)

        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("Ready", 12, muted)
        status.setPadding(dp(20), dp(9), dp(20), dp(13))
        outer.addView(status)
        setContentView(outer)
        showPage()
    }

    private fun tab(name: String, target: Page): View = Button(this).apply {
        text = name
        isAllCaps = false
        textSize = 13f
        setTextColor(if (page == target) this@MainActivity.text else muted)
        setBackgroundColor(if (page == target) panel2 else bg)
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { page = target; showPage() }
    }

    private fun showPage() {
        body.removeAllViews()
        title.text = when (page) { Page.FILES -> "Files"; Page.APK -> "APK explorer"; Page.TOOLS -> "Tools" }
        subtitle.text = when (page) { Page.FILES -> "BROWSE • MANAGE • ORGANISE"; Page.APK -> "INSPECT • EXTRACT • PATCH"; Page.TOOLS -> "UTILITIES • INFORMATION" }
        when (page) {
            Page.FILES -> showFiles()
            Page.APK -> showApk()
            Page.TOOLS -> showTools()
        }
        // Rebuild tab highlight after a page switch.
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        if (root.childCount > 0) {
            val outer = root.getChildAt(0) as? LinearLayout
            val tabs = outer?.getChildAt(1) as? LinearLayout
            tabs?.let {
                for (i in 0 until it.childCount) {
                    val b = it.getChildAt(i) as Button
                    val active = (i == page.ordinal)
                    b.setTextColor(if (active) text else muted)
                    b.setBackgroundColor(if (active) panel2 else bg)
                }
            }
        }
    }

    private fun showFiles() {
        val current = stack.lastOrNull()
        card("Storage access") {
            addView(label("${current?.name ?: "No folder selected"}", 16, text, true))
            addView(label(current?.uri?.lastPathSegment ?: "Choose a folder to start browsing files.", 11, muted))
            action("Choose folder", true) { chooseFolder() }
            if (current != null) {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(smallAction("Up") { if (stack.size > 1) { stack.removeAt(stack.lastIndex); showPage() } else status("Already at selected root") }, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.addView(smallAction("New folder") { newFolder() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.addView(smallAction("Paste") { pasteClipboard() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(row)
            }
        }
        if (current == null) {
            card("Getting started") {
                addView(label("Select a folder using Android's folder picker. The app can manage files and subfolders that Android allows the picker to expose.", 13, muted))
                addView(label("Protected Android/data and Android/obb locations may be blocked by Quest firmware. This app does not bypass those restrictions.", 12, muted))
            }
            return
        }
        val children = current.listFiles().sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
        card("${children.size} items") {
            if (children.isEmpty()) addView(label("This folder is empty.", 13, muted))
            children.forEach { file -> addView(fileRow(file)) }
        }
    }

    private fun fileRow(file: DocumentFile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(5), dp(8))
            setBackgroundColor(bg)
        }
        val icon = label(if (file.isDirectory) "▰" else iconFor(file.name ?: ""), 21, accent, true)
        icon.gravity = Gravity.CENTER
        row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(44)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        names.addView(label(file.name ?: "Unnamed", 14, text, true))
        names.addView(label(if (file.isDirectory) "Folder" else formatSize(file.length()), 11, muted))
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val more = TextView(this).apply { text = "⋮"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(10), 0, dp(8), 0) }
        more.setOnClickListener { fileMenu(file) }
        row.addView(more, LinearLayout.LayoutParams(dp(42), dp(44)))
        row.setOnClickListener {
            if (file.isDirectory) { stack.add(file); showPage() }
            else openFile(file)
        }
        row.setOnLongClickListener { fileMenu(file); true }
        return row
    }

    private fun fileMenu(file: DocumentFile) {
        val options = mutableListOf("Rename", "Copy", "Move", "Delete", "Details")
        if (!file.isDirectory && (file.name?.endsWith(".txt", true) == true || file.name?.endsWith(".json", true) == true || file.name?.endsWith(".xml", true) == true || file.name?.endsWith(".cfg", true) == true || file.name?.endsWith(".ini", true) == true || file.name?.endsWith(".log", true) == true)) options.add(0, "Edit text")
        AlertDialog.Builder(this).setTitle(file.name).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Rename" -> rename(file)
                "Copy" -> { clipboard = file; clipboardMove = false; status("Copied ${file.name}. Browse to a destination and tap Paste.") }
                "Move" -> { clipboard = file; clipboardMove = true; status("Ready to move ${file.name}. Browse to a destination and tap Paste.") }
                "Delete" -> confirmDelete(file)
                "Details" -> AlertDialog.Builder(this).setMessage("Name: ${file.name}\nType: ${if (file.isDirectory) "Folder" else "File"}\nSize: ${formatSize(file.length())}\nURI: ${file.uri}").setPositiveButton("OK", null).show()
                "Edit text" -> editText(file)
            }
        }.show()
    }

    private fun openFile(file: DocumentFile) {
        val name = file.name ?: ""
        when {
            name.endsWith(".apk", true) || name.endsWith(".zip", true) || name.endsWith(".jar", true) || name.endsWith(".aar", true) -> {
                archiveUri = file.uri; archiveName = name; scanArchive(file.uri); page = Page.APK; showPage()
            }
            isTextFile(name) -> editText(file)
            else -> AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("View first 256 bytes (hex)", "File details")) { _, which ->
                if (which == 0) showHex(file) else fileMenu(file)
            }.show()
        }
    }

    private fun showApk() {
        card("APK and archive explorer") {
            addView(label("Open APK, ZIP, JAR or AAR files and browse their entries. Tap a file to export it.", 13, muted))
            action("Open APK / archive", true) { pickFile("application/vnd.android.package-archive", reqOpenApk) }
            addView(label(selectedApkName, 12, muted))
            if (archiveUri != null) {
                addView(label("Current: $archiveName", 14, text, true))
                action("Refresh contents", false) { scanArchive(archiveUri!!) }
                if (archiveName.endsWith(".apk", true)) {
                    action("Add / replace file in APK", false) {
                        val source = archiveUri
                        if (source == null) status("Open an APK first") else pickFile("*/*", reqPatchFile)
                    }
                }
            }
        }
        if (archiveUri != null) {
            card("${archiveEntries.size} entries") {
                archiveEntries.take(1200).forEach { entry ->
                    val row = TextView(this).apply {
                        text = "  ${if (entry.endsWith("/")) "▰" else iconFor(entry)}   $entry"
                        textSize = 12f; setTextColor(if (entry.endsWith("/")) muted else this@MainActivity.text)
                        setPadding(dp(7), dp(9), dp(7), dp(9))
                    }
                    row.setOnClickListener {
                        if (!entry.endsWith("/")) {
                            pendingExportEntry = entry
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE, entry.substringAfterLast('/'))
                            }
                            startActivityForResult(intent, reqExport)
                        }
                    }
                    addView(row)
                }
                if (archiveEntries.size > 1200) addView(label("Showing first 1,200 entries to keep the interface responsive.", 11, muted))
            }
        }
    }

    private fun showTools() {
        card("File tools") {
            addView(label("Small utilities for inspecting files and game packages.", 13, muted))
            action("Open a text file", false) { pickFile("text/*", reqOpenArchive) }
            action("Open ZIP / APK", false) { pickFile("*/*", reqOpenArchive) }
            action("About storage access", false) {
                AlertDialog.Builder(this).setTitle("Storage access")
                    .setMessage("Quest File Manager uses Android's Storage Access Framework. It needs no root, Shizuku, wireless debugging or API key. Android/Quest firmware decides which folders are selectable. It cannot silently open protected app-private folders or bypass Android/data restrictions.")
                    .setPositiveButton("OK", null).show()
            }
        }
        card("APK editing status") {
            addView(label("This starter build can inspect APK/ZIP contents and export individual entries. APK rebuilding, signing, split-APK handling and resource-table editing are not included in this first source release.", 12, muted))
        }
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, reqTree)
    }

    private fun pickFile(mime: String, code: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            reqTree -> {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
                rootFolder = DocumentFile.fromTreeUri(this, uri)
                stack.clear(); rootFolder?.let { stack.add(it) }
                page = Page.FILES; showPage(); status("Folder opened")
            }
            reqOpenApk -> {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                selectedApk = uri; selectedApkName = displayName(uri)
                archiveUri = uri; archiveName = selectedApkName; scanArchive(uri); page = Page.APK; showPage()
            }
            reqOpenArchive -> {
                val name = displayName(uri)
                if (isTextFile(name)) readText(uri)?.let { showTextViewer(name, it) }
                else { archiveUri = uri; archiveName = name; scanArchive(uri); page = Page.APK; showPage() }
            }
            reqExport -> exportEntry(uri, pendingExportEntry)
            reqPatchFile -> {
                pendingPatchSource = uri; pendingPatchName = displayName(uri)
                val pathInput = EditText(this).apply {
                    setText("assets/QuestFileManager/$pendingPatchName")
                    setTextColor(this@MainActivity.text); setHintTextColor(muted)
                }
                AlertDialog.Builder(this).setTitle("Path inside APK")
                    .setMessage("Choose where the file should be placed. Existing entries at this path will be replaced.")
                    .setView(pathInput).setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue") { _, _ ->
                        pendingPatchPath = pathInput.text.toString().trim().trimStart('/')
                        if (pendingPatchPath.isBlank() || pendingPatchPath.contains("..")) {
                            status("Invalid APK path")
                        } else {
                            val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.android.package-archive"
                                putExtra(Intent.EXTRA_TITLE, "modified_${archiveName.ifBlank { "package.apk" }}")
                            }
                            startActivityForResult(save, reqPatchOutput)
                        }
                    }.show()
            }
            reqPatchOutput -> patchAndExport(uri)
        }
    }

    private fun scanArchive(uri: Uri) {
        try {
            val entries = mutableListOf<String>()
            ZipInputStream(contentResolver.openInputStream(uri) ?: error("Cannot open archive")).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    entries.add(e.name)
                    zin.closeEntry()
                }
            }
            archiveEntries = entries
            status("Loaded ${entries.size} archive entries")
            if (page == Page.APK) showPage()
        } catch (e: Exception) { archiveEntries = emptyList(); status("Could not read archive: ${e.message}") }
    }

    private fun exportEntry(destination: Uri, entryName: String) {
        val source = archiveUri ?: return
        try {
            var found = false
            ZipInputStream(contentResolver.openInputStream(source) ?: error("Cannot read source archive")).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.name == entryName) {
                        contentResolver.openOutputStream(destination, "w")!!.use { out -> zin.copyTo(out) }
                        found = true; break
                    }
                    zin.closeEntry()
                }
            }
            status(if (found) "Exported $entryName" else "Entry not found")
        } catch (e: Exception) { status("Export failed: ${e.message}") }
    }

    private fun patchAndExport(destination: Uri) {
        val source = archiveUri ?: run { status("Open an APK first"); return }
        val payload = pendingPatchSource ?: run { status("Choose a file to add"); return }
        try {
            val unsigned = File(cacheDir, "quest-file-manager-unsigned.apk")
            val signed = File(cacheDir, "quest-file-manager-signed.apk")
            val seen = HashSet<String>()
            ZipOutputStream(unsigned.outputStream().buffered()).use { out ->
                ZipInputStream(contentResolver.openInputStream(source) ?: error("Cannot read source APK")).use { zin ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        val name = entry.name
                        val signatureFile = name.startsWith("META-INF/", true) && (
                            name.endsWith(".SF", true) || name.endsWith(".RSA", true) ||
                            name.endsWith(".DSA", true) || name.endsWith(".EC", true) ||
                            name.equals("META-INF/MANIFEST.MF", true)
                        )
                        if (!entry.isDirectory && name != pendingPatchPath && !signatureFile && seen.add(name)) {
                            out.putNextEntry(ZipEntry(name))
                            zin.copyTo(out)
                            out.closeEntry()
                        } else if (entry.isDirectory && name != pendingPatchPath && seen.add(name)) {
                            out.putNextEntry(ZipEntry(name)); out.closeEntry()
                        }
                        zin.closeEntry()
                    }
                }
                if (!seen.add(pendingPatchPath)) error("Could not replace the selected APK entry")
                out.putNextEntry(ZipEntry(pendingPatchPath))
                contentResolver.openInputStream(payload)?.use { it.copyTo(out) } ?: error("Cannot read selected file")
                out.closeEntry()
            }
            ApkSigning(this).sign(unsigned, signed)
            contentResolver.openOutputStream(destination, "w")?.use { output -> signed.inputStream().use { it.copyTo(output) } }
                ?: error("Cannot write output APK")
            status("Patched and signed APK exported. Android may treat it as a different app because its signing certificate changed.")
        } catch (e: Exception) {
            status("APK patch failed: ${e.message}")
        } finally {
            pendingPatchSource = null
            pendingPatchName = ""
            pendingPatchPath = ""
        }
    }

    private fun newFolder() {
        val input = EditText(this).apply { hint = "Folder name"; setTextColor(this@MainActivity.text); setHintTextColor(muted) }
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val created = stack.lastOrNull()?.createDirectory(name)
                    status(if (created != null) "Folder created" else "Could not create folder")
                    showPage()
                }
            }.show()
    }

    private fun rename(file: DocumentFile) {
        val input = EditText(this).apply { setText(file.name ?: ""); setTextColor(this@MainActivity.text); setSelection(this.text.length) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val ok = file.renameTo(input.text.toString().trim())
                status(if (ok) "Renamed" else "Rename failed")
                showPage()
            }.show()
    }

    private fun confirmDelete(file: DocumentFile) {
        AlertDialog.Builder(this).setTitle("Delete ${file.name}?")
            .setMessage("This cannot be undone.").setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val ok = file.delete(); status(if (ok) "Deleted" else "Delete failed"); showPage()
            }.show()
    }

    private fun pasteClipboard() {
        val source = clipboard ?: run { status("Nothing copied or queued to move"); return }
        val dest = stack.lastOrNull() ?: run { status("Choose a destination folder first"); return }
        if (source.uri == dest.uri) { status("Source and destination are the same"); return }
        try {
            val result = if (source.isDirectory) copyDirectory(source, dest) else copyFile(source, dest)
            if (result && clipboardMove) source.delete()
            status(if (result) (if (clipboardMove) "Moved" else "Copied") else "Copy failed")
            if (result) clipboard = null
            showPage()
        } catch (e: Exception) { status("Operation failed: ${e.message}") }
    }

    private fun copyDirectory(source: DocumentFile, destination: DocumentFile): Boolean {
        val target = destination.createDirectory(source.name ?: "Folder") ?: return false
        source.listFiles().forEach { child ->
            val ok = if (child.isDirectory) copyDirectory(child, target) else copyFile(child, target)
            if (!ok) return false
        }
        return true
    }

    private fun copyFile(source: DocumentFile, destination: DocumentFile): Boolean {
        val name = source.name ?: "file"
        val mime = source.type ?: "application/octet-stream"
        val target = destination.createFile(mime, name) ?: return false
        contentResolver.openInputStream(source.uri).use { input ->
            contentResolver.openOutputStream(target.uri, "w").use { output ->
                if (input == null || output == null) return false
                input.copyTo(output)
            }
        }
        return true
    }

    private fun editText(file: DocumentFile) {
        val content = readText(file.uri) ?: run { status("Could not read file"); return }
        val editor = EditText(this).apply {
            setText(content); setTextColor(this@MainActivity.text); setHintTextColor(muted)
            textSize = 13f; typeface = Typeface.MONOSPACE; gravity = Gravity.TOP
            minLines = 12; maxLines = 22; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(panel)
        }
        AlertDialog.Builder(this).setTitle(file.name ?: "Text editor").setView(editor)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                try { contentResolver.openOutputStream(file.uri, "w")!!.bufferedWriter().use { it.write(editor.text.toString()) }; status("Saved ${file.name}") }
                catch (e: Exception) { status("Save failed: ${e.message}") }
            }.show()
    }

    private fun showTextViewer(name: String, value: String) {
        val view = TextView(this).apply { text = value; setTextColor(this@MainActivity.text); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this).setTitle(name).setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun showHex(file: DocumentFile) {
        try {
            val bytes = contentResolver.openInputStream(file.uri)!!.use { readAtMost(it, 256) }
            val lines = bytes.toList().chunked(16).mapIndexed { index, chunk ->
                val hex = chunk.joinToString(" ") { "%02X".format(it) }.padEnd(47)
                val ascii = chunk.joinToString("") { b -> val n = b.toInt() and 255; if (n in 32..126) n.toChar().toString() else "." }
                "%08X  %s  %s".format(index * 16, hex, ascii)
            }.joinToString("\n")
            showTextViewer("Hex: ${file.name}", lines.ifBlank { "Empty file" })
        } catch (e: Exception) { status("Could not read file: ${e.message}") }
    }

    private fun readText(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = readAtMost(input, 1_048_577)
            if (bytes.size > 1_048_576) "[File is larger than 1 MB. Opened first 1 MB only.]\n" + bytes.copyOf(1_048_576).toString(Charsets.UTF_8)
            else bytes.toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            out.write(buffer, 0, count)
            remaining -= count
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun isTextFile(name: String) = listOf(".txt", ".json", ".xml", ".cfg", ".ini", ".log", ".md", ".lua", ".kt", ".java", ".cs", ".yml", ".yaml", ".properties").any { name.endsWith(it, true) }
    private fun iconFor(name: String): String = when {
        name.endsWith(".apk", true) -> "APK"
        name.endsWith(".dll", true) -> "DLL"
        name.endsWith(".so", true) -> "SO"
        name.endsWith(".zip", true) -> "ZIP"
        name.endsWith(".txt", true) || name.endsWith(".json", true) -> "≡"
        else -> "▤"
    }
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun card(heading: String, build: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)); setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        box.addView(label(heading, 14, text, true))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, 0) }
        box.addView(content)
        content.build()
        body.addView(box)
    }

    private fun LinearLayout.action(name: String, primary: Boolean, click: () -> Unit) {
        val b = Button(this@MainActivity).apply {
            text = name; isAllCaps = false; textSize = 13f; setTextColor(this@MainActivity.text)
            setBackgroundColor(if (primary) panel2 else bg); setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) }
        }
        addView(b)
    }

    private fun smallAction(name: String, click: () -> Unit): View = Button(this).apply {
        text = name; isAllCaps = false; textSize = 11f; setTextColor(this@MainActivity.text); setBackgroundColor(panel2)
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(2), dp(5), dp(2), dp(2)) }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun status(value: String) { status.text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
