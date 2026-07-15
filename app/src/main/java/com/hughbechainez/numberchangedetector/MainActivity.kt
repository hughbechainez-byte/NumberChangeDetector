package com.hughbechainez.numberchangedetector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.MediaController
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hughbechainez.numberchangedetector.databinding.ActivityMainBinding
import com.hughbechainez.numberchangedetector.scanner.CornerPreset
import com.hughbechainez.numberchangedetector.scanner.ScanProfile
import com.hughbechainez.numberchangedetector.scanner.formatTimestampMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var workManager: WorkManager
    private var selectedUri: Uri? = null
    private var activeWorkId: UUID? = null
    private var resultJson: String? = null
    private var handledTerminalWork: UUID? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workManager = WorkManager.getInstance(this)
        setupUi()
        restoreSelection()
        observeWork()
    }

    private fun setupUi() {
        binding.cornerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            CornerPreset.values().map { it.label }
        )
        binding.profileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ScanProfile.values().map { it.label }
        )
        binding.selectVideoButton.setOnClickListener { picker.launch(arrayOf("video/*")) }
        binding.startScanButton.setOnClickListener { startScan() }
        binding.cancelScanButton.setOnClickListener {
            activeWorkId?.let(workManager::cancelWorkById)
                ?: workManager.cancelUniqueWork(ScanWorker.UNIQUE_WORK_NAME)
        }
        binding.copyJsonButton.setOnClickListener {
            resultJson?.let { json ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("number-transition-result.json", json))
                binding.statusText.text = "Result JSON copied"
            }
        }
    }

    private fun onVideoSelected(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectedUri = uri
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SAVED_URI, uri.toString()).apply()
        binding.selectedVideoText.text = uri.lastPathSegment ?: uri.toString()
        binding.videoPreview.setVideoURI(uri)
        binding.videoPreview.setMediaController(MediaController(this).apply { setAnchorView(binding.videoPreview) })
        binding.videoPreview.setOnPreparedListener { player ->
            player.isLooping = true
            binding.videoPreview.seekTo(1)
        }
        binding.statusText.text = "Video ready"
    }

    private fun restoreSelection() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SAVED_URI, null) ?: return
        runCatching { Uri.parse(raw) }.getOrNull()?.let(::onVideoSelected)
    }

    private fun startScan() {
        val uri = selectedUri ?: run {
            binding.statusText.text = "Select a video first"
            return
        }
        val corner = CornerPreset.values()[binding.cornerSpinner.selectedItemPosition]
        val profile = ScanProfile.values()[binding.profileSpinner.selectedItemPosition]
        val input = Data.Builder()
            .putString(ScanWorker.KEY_SOURCE_URI, uri.toString())
            .putString(ScanWorker.KEY_CORNER, corner.name)
            .putString(ScanWorker.KEY_PROFILE, profile.name)
            .build()
        val request = OneTimeWorkRequestBuilder<ScanWorker>().setInputData(input).build()
        activeWorkId = request.id
        handledTerminalWork = null
        resultJson = null
        binding.resultsText.text = "Scanning..."
        binding.copyJsonButton.isEnabled = false
        workManager.enqueueUniqueWork(
            ScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun observeWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(ScanWorker.UNIQUE_WORK_NAME).observe(this) { infos ->
            val work = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                ?: infos.lastOrNull()
                ?: return@observe
            activeWorkId = work.id
            renderWork(work)
        }
    }

    private fun renderWork(work: WorkInfo) {
        val active = work.state == WorkInfo.State.RUNNING || work.state == WorkInfo.State.ENQUEUED ||
            work.state == WorkInfo.State.BLOCKED
        binding.startScanButton.isEnabled = !active
        binding.selectVideoButton.isEnabled = !active
        binding.cornerSpinner.isEnabled = !active
        binding.profileSpinner.isEnabled = !active
        binding.cancelScanButton.isEnabled = active
        when (work.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val percent = work.progress.getInt(ScanWorker.KEY_PROGRESS_PERCENT, 0)
                val phase = work.progress.getString(ScanWorker.KEY_PROGRESS_PHASE) ?: "queued"
                val message = work.progress.getString(ScanWorker.KEY_PROGRESS_MESSAGE) ?: "Waiting to scan"
                binding.scanProgress.progress = percent
                binding.statusText.text = "$phase: $message ($percent%)"
            }
            WorkInfo.State.SUCCEEDED -> {
                binding.scanProgress.progress = 100
                binding.statusText.text = "Scan complete"
                if (handledTerminalWork != work.id) {
                    handledTerminalWork = work.id
                    loadResult(work.outputData.getString(ScanWorker.KEY_RESULT_PATH))
                }
            }
            WorkInfo.State.FAILED -> {
                binding.statusText.text = "Scan failed: ${work.outputData.getString(ScanWorker.KEY_ERROR) ?: "unknown error"}"
            }
            WorkInfo.State.CANCELLED -> binding.statusText.text = "Scan cancelled"
        }
    }

    private fun loadResult(path: String?) {
        if (path.isNullOrBlank()) {
            binding.resultsText.text = "Scan completed without a readable result file"
            return
        }
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { File(path).readText(Charsets.UTF_8) }.getOrNull()
            }
            if (json == null) {
                binding.resultsText.text = "Result file is unavailable"
                return@launch
            }
            resultJson = json
            binding.copyJsonButton.isEnabled = true
            binding.resultsText.text = renderResult(json)
        }
    }

    private fun renderResult(raw: String): String = runCatching {
        val root = JSONObject(raw)
        val marks = root.getJSONArray("transitionMarks")
        val lines = ArrayList<String>()
        for (index in 0 until marks.length()) {
            val mark = marks.getJSONObject(index)
            val from = if (mark.isNull("fromNumber")) "none" else mark.getInt("fromNumber").toString()
            val to = mark.getInt("toNumber")
            val timestamp = formatTimestampMs(mark.getLong("eventBoundaryMs"))
            val confidence = (mark.optDouble("confidence", 0.0) * 100.0).toInt()
            lines += "$from -> $to    $timestamp    $confidence%"
        }
        val metrics = root.getJSONObject("metrics")
        val runtime = formatTimestampMs(metrics.getLong("wallClockMs"))
        val speed = metrics.optDouble("videoToWallSpeed", 0.0)
        if (lines.isEmpty()) lines += "No number transitions confirmed"
        lines += ""
        lines += "${marks.length()} transitions | runtime $runtime | %.2fx realtime".format(speed)
        val warnings = root.optJSONArray("warnings")
        if (warnings != null && warnings.length() > 0) {
            lines += "Warnings:"
            for (index in 0 until warnings.length()) lines += "- ${warnings.getString(index)}"
        }
        lines.joinToString("\n")
    }.getOrElse { "Could not parse result: ${it.message}" }

    private companion object {
        const val PREFS = "number-detector"
        const val KEY_SAVED_URI = "selectedUri"
    }
}
