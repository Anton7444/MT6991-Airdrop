package com.airdrop.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import com.airdrop.android.daemon.RadioInstaller

object Received {
    const val FOLDER = "Download/Owly"

    private const val TAG = "AirDrop"

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val DOC = "primary:$FOLDER"
    private const val DOCUMENTS_UI = "com.android.documentsui"
    private const val FILES_ACTIVITY = "com.android.documentsui.files.FilesActivity"

    private fun folderUri(): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, DOC)

    private fun fileUri(name: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "$DOC/$name")

    fun folderIntent(ctx: Context): Intent = Intent(Intent.ACTION_VIEW)
        .setComponent(ComponentName(DOCUMENTS_UI, FILES_ACTIVITY))
        .setDataAndType(folderUri(), DocumentsContract.Document.MIME_TYPE_DIR)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openFolder(ctx: Context, background: Boolean = false) {
        if (background && RadioInstaller.showFolderAsRoot()) return
        if (launch(ctx, folderIntent(ctx))) return
        val implicit = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folderUri(), DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(ctx, implicit)) return
        Toast.makeText(ctx, ctx.getString(R.string.notif_saved), Toast.LENGTH_LONG).show()
    }

    fun openFile(ctx: Context, rawName: String) {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() && it != ".." && it != "." } ?: return
        val i = viewIntent(name)
        if (!launch(ctx, i) && !RadioInstaller.viewAsRoot(i.data.toString(), i.type.orEmpty())) openFolder(ctx)
    }

    fun openReceived(ctx: Context, path: String, background: Boolean = false) {
        val name = path.substringAfterLast('/')
        if (!hasViewer(name)) {
            Log.i(TAG, "open $name: no viewer for it, showing its folder")
            openFolder(ctx, background)
            return
        }
        val mime = mimeOf(name)
        if (mime.startsWith("image/")) {
            val shown = runCatching { registerImage(ctx, name, mime) }
                .onFailure { Log.w(TAG, "could not add $name to the media library: $it") }.getOrNull()
            if (shown != null) {
                Log.i(TAG, "open $name via the media library: $shown")
                if (RadioInstaller.viewAsRoot(shown.toString(), mime)) return
                val vi = Intent(Intent.ACTION_VIEW).setDataAndType(shown, mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (launch(ctx, vi)) return
            }
        }
        val i = viewIntent(name)
        val seen = runCatching { ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.name }.getOrNull()
        Log.i(TAG, "open $name: uri=${i.data} type=${i.type} resolves to $seen background=$background")
        if (background) {
            if (RadioInstaller.viewAsRoot(i.data.toString(), i.type.orEmpty())) return
            if (launch(ctx, i)) return
        } else {
            if (launch(ctx, i)) return
            if (RadioInstaller.viewAsRoot(i.data.toString(), i.type.orEmpty())) return
        }
        openFolder(ctx, background)
    }

    private fun registerImage(ctx: Context, name: String, mime: String): Uri? {
        val src = "/storage/emulated/0/$FOLDER/$name"
        val res = ctx.contentResolver
        val coll = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val size = RadioInstaller.fileSize(src)
        if (size <= 0) return null
        res.query(
            coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${android.provider.MediaStore.MediaColumns.SIZE}=? AND " +
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, size.toString(), "Pictures/Owly%"), null,
        )?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(coll, c.getLong(0)) }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Owly")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = res.insert(coll, values) ?: return null
        try {
            val ok = res.openOutputStream(uri)?.use { out -> RadioInstaller.catTo(src, out) } ?: false
            if (!ok) { res.delete(uri, null, null); return null }
            res.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { res.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    private fun viewIntent(name: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri(name), mimeOf(name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun openSent(ctx: Context, uri: String?, url: String?, knownMime: String? = null): Boolean {
        if (!url.isNullOrBlank()) {
            return launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val u = uri?.let(Uri::parse) ?: return false
        val mime = knownMime?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: runCatching { ctx.contentResolver.getType(u) }.getOrNull()
            ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(u, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Log.i(TAG, "open what was sent: $u ($mime)")
        return launch(ctx, i) || RadioInstaller.viewAsRoot(u.toString(), mime)
    }

    private fun launch(ctx: Context, i: Intent): Boolean = runCatching { ctx.startActivity(i) }
        .onFailure { Log.w(TAG, "cannot start ${i.action} ${i.data} (${i.type}): $it") }
        .isSuccess

    private fun hasViewer(name: String): Boolean = mimeOf(name) != "*/*"

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "bmp" -> "image/bmp"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "vcf" -> "text/x-vcard"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mov" -> "video/quicktime"
        "mp4", "m4v", "mkv" -> "video/*"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "*/*"
    }
}
