package com.bersihhp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var delBtn: Button
    private var files = listOf<File>()
    private val root: File = Environment.getExternalStorageDirectory()
    @Volatile private var scanning = false
    private var ready = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            build()
            ready = true
        } catch (e: Throwable) {
            showErr(e)
        }
    }

    private fun showErr(e: Throwable) {
        val t = TextView(this)
        t.text = "ERROR:\n" + android.util.Log.getStackTraceString(e)
        t.textSize = 12f
        t.setPadding(24, 80, 24, 24)
        t.setTextIsSelectable(true)
        setContentView(ScrollView(this).apply { addView(t) })
    }

    private fun build() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "Bersih HP"; textSize = 26f })
        info = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, 0) }
        col.addView(info)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this)
        row.addView(btn("File besar", 1f) { scan(0) })
        row.addView(btn("APK sisa", 1f) { scan(1) })
        row.addView(btn("Duplikat", 1f) { scan(2) })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Pilih jenis pemindaian di atas."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        col.addView(status)
        list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        delBtn = btn("Hapus yang dicentang", 0f) { confirmDelete() }
        delBtn.isEnabled = false
        col.addView(delBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(col)
        refreshInfo()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        try { refreshInfo() } catch (e: Throwable) { }
    }

    private fun btn(t: String, w: Float, f: () -> Unit): Button {
        val bt = Button(this)
        bt.text = t
        bt.isAllCaps = false
        bt.setOnClickListener {
            try { f() } catch (e: Throwable) { toast("Error: " + e.toString()) }
        }
        if (w > 0f) bt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        return bt
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun fmt(n: Long): String = when {
        n >= 1073741824L -> String.format(java.util.Locale.US, "%.1f GB", n / 1073741824.0)
        n >= 1048576L -> String.format(java.util.Locale.US, "%.0f MB", n / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.0f KB", n / 1024.0)
    }

    private fun refreshInfo() {
        val s = StatFs(root.path)
        val total = s.blockCountLong * s.blockSizeLong
        val free = s.availableBlocksLong * s.blockSizeLong
        val used = total - free
        info.text = "Terpakai ${fmt(used)} dari ${fmt(total)}  •  Sisa ${fmt(free)}"
        bar.max = 1000
        bar.progress = if (total > 0) (used * 1000 / total).toInt() else 0
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    private fun all(): Sequence<File> =
        root.walkTopDown().onEnter { !(it.parentFile == root && it.name == "Android") }.filter { it.isFile }

    private fun scan(kind: Int) {
        if (!hasAccess()) {
            askAccess()
            toast("Izinkan akses semua file dulu, lalu balik ke sini dan tekan tombolnya lagi")
            return
        }
        if (scanning) return
        scanning = true
        status.text = "Memindai, tunggu sebentar..."
        delBtn.isEnabled = false
        Thread {
            val found: List<File> = try {
                when (kind) {
                    0 -> all().filter { it.length() >= 52428800L }.sortedByDescending { it.length() }.take(100).toList()
                    1 -> all().filter { it.extension.equals("apk", true) }.sortedByDescending { it.length() }.take(100).toList()
                    else -> dupes()
                }
            } catch (e: Throwable) { emptyList() }
            runOnUiThread { scanning = false; show(found) }
        }.start()
    }

    private fun dupes(): List<File> {
        val out = mutableListOf<File>()
        val bySize = all().filter { it.length() >= 102400L }.groupBy { it.length() }.filter { it.value.size > 1 }
        for ((_, g) in bySize) {
            for ((h, same) in g.groupBy { quickHash(it) }) {
                if (h.isEmpty() || same.size < 2) continue
                out.addAll(same.sortedBy { it.lastModified() }.drop(1))
            }
        }
        return out.sortedByDescending { it.length() }.take(200)
    }

    private fun quickHash(f: File): String = try {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(524288)
            var n = r.read(buf)
            if (n > 0) md.update(buf, 0, n)
            if (r.length() > 1048576L) {
                r.seek(r.length() - 524288L)
                n = r.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    private fun show(found: List<File>) {
        files = found
        val labels = found.map { "${it.name}\n${fmt(it.length())}  •  ${it.parent?.removePrefix(root.path) ?: ""}" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        status.text = if (found.isEmpty()) "Nggak ketemu apa-apa. Bersih!"
        else "Ketemu ${found.size} file (${fmt(found.sumOf { it.length() })}). Centang yang mau dihapus."
        delBtn.isEnabled = found.isNotEmpty()
    }

    private fun confirmDelete() {
        val sel = (0 until files.size).filter { list.isItemChecked(it) }.map { files[it] }
        if (sel.isEmpty()) { toast("Belum ada yang dicentang"); return }
        val total = sel.sumOf { it.length() }
        AlertDialog.Builder(this)
            .setTitle("Hapus ${sel.size} file?")
            .setMessage("Total ${fmt(total)}. File dihapus permanen dan nggak bisa dikembalikan.")
            .setPositiveButton("Hapus") { _, _ ->
                Thread {
                    var freed = 0L
                    var ok = 0
                    for (f in sel) {
                        val s = f.length()
                        if (f.delete()) { freed += s; ok++ }
                    }
                    runOnUiThread {
                        refreshInfo()
                        show(files.filter { it.exists() })
                        status.text = "Terhapus $ok file, lega ${fmt(freed)}"
                    }
                }.start()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
