package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Rational
import android.view.Surface
import android.view.View
import android.view.MotionEvent
import android.media.Image
import android.media.ImageReader
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.compilationmaker.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

private fun JSONObject.putFinite(key: String, value: Float, fallback: Float = 0f) {
    put(key, if (value.isFinite()) value else fallback)
}

private fun JSONObject.putFiniteNullable(key: String, value: Float?) {
    put(key, value?.takeIf { it.isFinite() } ?: JSONObject.NULL)
}

private const val KEY_CORE_STATUS_ENABLED = "core_status_enabled"
private const val CORE_STATUS_SAMPLE_INTERVAL_MS = 250L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private val selectedVideoUris = mutableListOf<Uri>()
    private var isBusy = false
    private var isScrubbing = false
    private val statusFeedLines = ArrayDeque<String>()
    private val logTag = "CompilationMaker"
    private val previewHandler = Handler(Looper.getMainLooper())
    private val backgroundStatusHandler = Handler(Looper.getMainLooper())
    private var selectedPreviewMs = 0
    private var lastProgressText = ""
    private var lastProgressPercent = -1
    private var lastProgressUpdateMs = 0L
    private var notificationChannelReady = false
    private var lastNotificationUpdateMs = 0L
    private var lastNotificationProgressPercent = -1
    private var previewBitmap: Bitmap? = null
    private var pendingCompilationFile: File? = null
    private var pendingCompilationFormat: ExportFormat? = null
    private var pendingCompilationUri: Uri? = null
    private var isCompilationPreviewScrubbing = false
    private var selectedCompilationPreviewMs = 0
    private var roiTouchMode = RoiTouchMode.NONE
    private var roiTouchStartX = 0f
    private var roiTouchStartY = 0f
    private var roiStartXPercent = 0f
    private var roiStartYPercent = 0f
    private var roiStartWPercent = 0f
    private var roiStartHPercent = 0f
    private var isUpdatingRoiFields = false
    private var selectedVideoRotationDegrees = 0
    private var selectedVideoWidth = 0
    private var selectedVideoHeight = 0
    private val roiPrefs by lazy { getSharedPreferences("roi_prefs", MODE_PRIVATE) }
    private val roiCornerHitPx = 28f

    private val permissionRequestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                emitTransientStatus("Permissions updated")
            } else {
                emitTransientStatus("Video access permission is still required")
            }
        }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val data = result.data ?: return@registerForActivityResult
            val pickedUris = buildList {
                data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
                data.data?.let { add(it) }
            }.distinct()
            if (pickedUris.isEmpty()) return@registerForActivityResult
            pickedUris.forEach { picked ->
                try { contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            }
            onVideosSelected(pickedUris)
        }

    private fun onVideosSelected(pickedUris: List<Uri>) {
        if (hasActiveCompilation()) {
            emitTransientStatus("A compilation is already active. Reopen it instead of selecting another video.")
            restoreActiveCompilationWork()
            return
        }
        selectedVideoUris.clear()
        selectedVideoUris.addAll(pickedUris)
        val picked = pickedUris.first()
        selectedVideoUri = picked
        loadSelectedVideoMetadata(picked)
        restoreRoiState(picked)
        binding.selectedVideo.text = if (pickedUris.size == 1) picked.toString() else "${pickedUris.size} videos selected"
        binding.batchQueueText.text = pickedUris.mapIndexed { index, uri -> "${index + 1}. ${displayName(uri)} — queued" }.joinToString("\n")
        persistDraftState(CompilationPipelineState.VIDEO_SELECTED, "Video selected")
        emitRoiStatus(if (pickedUris.size == 1) "Video selected: ${picked.toString().take(60)}" else "${pickedUris.size} videos queued. The same ROI and scan settings apply to each video.")
        setUiBusy(false)
        setupVideoPreview(picked)
    }

    private fun launchVideoPicker() {
        val baseIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        baseIntent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/webm", "video/quicktime", "video/*"))
        baseIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        videoPickerLauncher.launch(baseIntent)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else uri.lastPathSegment.orEmpty()
        } ?: uri.lastPathSegment.orEmpty()
    }.getOrDefault(uri.lastPathSegment.orEmpty()).ifBlank { "video" }

    private lateinit var qualitySpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var scanSpeedSpinner: Spinner
    private lateinit var videoPreview: VideoView
    private lateinit var previewSeekBar: SeekBar
    private lateinit var compilationPreview: VideoView
    private lateinit var compilationPreviewSeekBar: SeekBar
    private lateinit var playCompilationPreviewButton: Button
    private lateinit var saveCompilationButton: Button
    private lateinit var discardCompilationButton: Button
    private lateinit var shareCompilationButton: Button
    private lateinit var openCompilationButton: Button
    private lateinit var frameImage: ImageView
    private lateinit var frameContainer: FrameLayout
    private lateinit var roiOverlay: View
    private lateinit var captureFrameButton: Button
    private lateinit var refreshRoiButton: Button
    private lateinit var scanAreaX: EditText
    private lateinit var scanAreaY: EditText
    private lateinit var scanAreaWidth: EditText
    private lateinit var scanAreaHeight: EditText
    private lateinit var progressPercentText: TextView
    private lateinit var elapsedTimeText: TextView
    private lateinit var backgroundStatusBanner: TextView
    private lateinit var statusFeedText: TextView
    private lateinit var statusFeedScroll: ScrollView
    private lateinit var coreStatusSwitch: CheckBox
    private lateinit var mainContent: ScrollView
    private lateinit var pipStatusContainer: View
    private lateinit var pipPhaseText: TextView
    private lateinit var pipPercentText: TextView
    private lateinit var pipProgressBar: ProgressBar
    private lateinit var pipStatusText: TextView
    private lateinit var pipSignalText: TextView
    private lateinit var pipLogText: TextView
    private lateinit var batchQueueText: TextView
    private lateinit var checkUpdatesButton: Button
    private lateinit var autoUpdateSwitch: CheckBox
    private lateinit var crashLogButton: Button
    private lateinit var transitionResultsText: TextView
    private lateinit var transitionStyleSpinner: Spinner
    private lateinit var experimentalModeSwitch: CheckBox
    private lateinit var experimentalDownscaleSpinner: Spinner
    private lateinit var experimentalModeWarningText: TextView

    private val qualityOptions = arrayOf(ExportQuality.Low, ExportQuality.Medium, ExportQuality.High)
    private val formatOptions = arrayOf(ExportFormat.Mp4, ExportFormat.Webm, ExportFormat.Mov)
    private val transitionStyleOptions = arrayOf(TransitionStyle.Instant, TransitionStyle.Gradual)
    private val checkpointProfiles = compilationScanProfiles()
    private val experimentalDownscaleOptions = intArrayOf(16, 24, 32, 40)
    private val defaultScanWindow = ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
    private val compilationWorkName = CompilationJobContract.UNIQUE_WORK_NAME
    private val progressNotificationChannelId = COMPILATION_NOTIFICATION_CHANNEL_ID
    private val progressNotificationId = ACTIVITY_PROGRESS_NOTIFICATION_ID
    private val workManager by lazy { WorkManager.getInstance(this) }
    @Volatile
    private var compilationWorkId: UUID? = null
    private var activeWorkInfoLiveData: LiveData<WorkInfo?>? = null
    private var activeWorkObserver: Observer<WorkInfo?>? = null
    private var activeBatchWorkInfoLiveData: LiveData<WorkInfo?>? = null
    private var activeBatchWorkObserver: Observer<WorkInfo?>? = null
    private var batchWorkId: UUID? = null
    private val compilationJobStore by lazy { CompilationJobStore(applicationContext) }
    private val compilationBatchStore by lazy { CompilationBatchStore(applicationContext) }
    private var currentPipelineState = CompilationPipelineState.IDLE
    private var latestWorkManagerState: WorkInfo.State? = null
    private var compilationStartInFlight = false
    private var compilationRestoreInFlight = false
    private var terminalHandlingWorkId: UUID? = null
    private val backgroundStatusPrefs by lazy { getSharedPreferences("background_status_prefs", MODE_PRIVATE) }
    @Volatile
    private var backgroundStatusMode = BackgroundStatusMode.CONCISE
    private var previousBackgroundStatus: BackgroundStatusSnapshot? = null
    private var lastBackgroundFeedAtMs = 0L
    private val coreActivityLock = Any()
    private var pendingCoreActivity: CompilationCoreActivity? = null
    private var coreActivityRenderScheduled = false
    private val coreActivityListener: (CompilationCoreActivity) -> Unit = { activity ->
        queueCoreActivity(activity)
    }
    private val coreActivityRenderer = Runnable { renderPendingCoreActivity() }
    private val updateRepository by lazy { UpdateRepository.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpUi()
        CoreActivityTelemetry.addListener(coreActivityListener)
        ensureProgressNotificationChannel()
        UpdateNotifier.ensureChannel(this)
        requestPermissionsIfNeeded()
        startForegroundUpdateChecks()
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        stopPreviewProgressUpdates()
        videoPreview.stopPlayback()
    }

    override fun onUserLeaveHint() {
        if (hasActiveCompilation() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            updatePictureInPictureParams(activeJob = true)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                runCatching { enterPictureInPictureMode(buildPictureInPictureParams(activeJob = true)) }
                    .onFailure { AppLog.w(this, logTag, "Could not enter background status picture-in-picture", it) }
            }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        renderPictureInPictureMode(isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        restoreActiveCompilationWork()
        restoreActiveBatchWork()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissCompilationWorkObserver()
        dismissBatchWorkObserver()
        CoreActivityTelemetry.removeListener(coreActivityListener)
        backgroundStatusHandler.removeCallbacks(coreActivityRenderer)
        stopPreviewProgressUpdates()
        stopCompilationPreviewProgressUpdates()
        pendingCompilationFile = null
        pendingCompilationUri = null
        previewBitmap?.recycle()
        previewBitmap = null
    }

    private fun setUpUi() {
        qualitySpinner = binding.qualityPicker
        formatSpinner = binding.formatPicker
        scanSpeedSpinner = binding.scanSpeedPicker
        transitionStyleSpinner = binding.transitionStylePicker
        videoPreview = binding.videoPreview
        previewSeekBar = binding.previewSeekBar
        compilationPreview = binding.compilationPreview
        compilationPreviewSeekBar = binding.compilationPreviewSeekBar
        playCompilationPreviewButton = binding.playCompilationPreviewButton
        saveCompilationButton = binding.saveCompilationButton
        discardCompilationButton = binding.discardCompilationButton
        shareCompilationButton = binding.shareCompilationButton
        openCompilationButton = binding.openCompilationButton
        frameImage = binding.frameImage
        frameContainer = binding.frameContainer
        roiOverlay = binding.roiOverlay
        captureFrameButton = binding.captureFrameButton
        refreshRoiButton = binding.refreshRoiButton
        scanAreaX = binding.scanAreaX
        scanAreaY = binding.scanAreaY
        scanAreaWidth = binding.scanAreaWidth
        scanAreaHeight = binding.scanAreaHeight
        progressPercentText = binding.progressPercentText
        backgroundStatusBanner = binding.backgroundStatusBanner
        statusFeedText = binding.statusFeedText
        statusFeedScroll = binding.statusFeedScroll
        coreStatusSwitch = binding.coreStatusSwitch
        mainContent = binding.mainContent
        pipStatusContainer = binding.pipStatusContainer
        pipPhaseText = binding.pipPhaseText
        pipPercentText = binding.pipPercentText
        pipProgressBar = binding.pipProgressBar
        pipStatusText = binding.pipStatusText
        pipSignalText = binding.pipSignalText
        pipLogText = binding.pipLogText
        batchQueueText = binding.batchQueueText
        elapsedTimeText = binding.elapsedTimeText
        checkUpdatesButton = binding.checkUpdatesButton
        autoUpdateSwitch = binding.autoUpdateSwitch
        autoUpdateSwitch.isChecked = updateRepository.autoDownloadEnabled
        crashLogButton = binding.crashLogButton
        transitionResultsText = binding.transitionResultsText
        experimentalModeSwitch = binding.experimentalModeSwitch
        experimentalDownscaleSpinner = binding.experimentalDownscalePicker
        experimentalModeWarningText = binding.experimentalModeWarning
        roiOverlay.isClickable = false
        roiOverlay.isFocusable = false
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoPreview)
        videoPreview.setMediaController(mediaController)

        binding.selectButton.setOnClickListener {
            launchVideoPicker()
        }

        previewSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                isScrubbing = true
                selectedPreviewMs = progress
                videoPreview.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                selectedPreviewMs = seekBar.progress
                videoPreview.seekTo(selectedPreviewMs)
                isScrubbing = false
            }
        })

        captureFrameButton.setOnClickListener {
            if (hasActiveCompilation()) {
                emitTransientStatus("ROI capture is unavailable while compilation is active.")
                return@setOnClickListener
            }
            captureFrame(selectedPreviewMs)
        }

        videoPreview.setOnPreparedListener { mediaPlayer ->
            if (hasActiveCompilation()) {
                emitRoiStatus("ROI preview paused while compilation is active")
                return@setOnPreparedListener
            }
            val duration = max(1, mediaPlayer.duration)
            previewSeekBar.max = duration
            previewSeekBar.isEnabled = true
            captureFrameButton.isEnabled = true
            selectedPreviewMs = 0
            previewSeekBar.progress = 0
            startPreviewProgressUpdates()
            captureFrame(0)
        }

        videoPreview.setOnCompletionListener {
            selectedPreviewMs = 0
            previewSeekBar.progress = 0
        }

        compilationPreview.setOnPreparedListener { mediaPlayer ->
            val duration = max(1, mediaPlayer.duration)
            compilationPreviewSeekBar.max = duration
            compilationPreviewSeekBar.progress = 0
            compilationPreviewSeekBar.isEnabled = true
            selectedCompilationPreviewMs = 0
            compilationPreview.seekTo(0)
            compilationPreview.pause()
            playCompilationPreviewButton.text = "Play"
            startCompilationPreviewProgressUpdates()
        }

        compilationPreview.setOnCompletionListener {
            selectedCompilationPreviewMs = 0
            compilationPreviewSeekBar.progress = 0
            playCompilationPreviewButton.text = "Play"
        }

        compilationPreviewSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                isCompilationPreviewScrubbing = true
                selectedCompilationPreviewMs = progress
                compilationPreview.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isCompilationPreviewScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                selectedCompilationPreviewMs = seekBar.progress
                compilationPreview.seekTo(selectedCompilationPreviewMs)
                isCompilationPreviewScrubbing = false
            }
        })

        playCompilationPreviewButton.setOnClickListener {
            if (pendingCompilationFile == null) {
                emitTransientStatus("Build a compilation preview first.")
                return@setOnClickListener
            }
            if (compilationPreview.isPlaying) {
                compilationPreview.pause()
                playCompilationPreviewButton.text = "Play"
            } else {
                compilationPreview.start()
                playCompilationPreviewButton.text = "Pause"
                startCompilationPreviewProgressUpdates()
            }
        }

        saveCompilationButton.setOnClickListener {
            savePendingCompilation()
        }

        shareCompilationButton.setOnClickListener {
            sharePendingCompilation()
        }

        openCompilationButton.setOnClickListener {
            openPendingCompilation()
        }

        discardCompilationButton.setOnClickListener {
            clearPendingCompilationPreview(deleteFile = true)
            persistDraftState(CompilationPipelineState.READY, "Compilation preview discarded")
            emitTransientStatus("Compilation preview discarded.")
        }

        frameImage.setOnTouchListener { _, event ->
            if (hasActiveCompilation()) return@setOnTouchListener true
            if (previewBitmap == null) {
                return@setOnTouchListener true
            }

            val width = frameImage.width.toFloat()
            val height = frameImage.height.toFloat()
            if (width <= 0f || height <= 0f) {
                return@setOnTouchListener true
            }

            val window = readScanWindow()
            val boxW = max(1f, width * window.widthPercent)
            val boxH = max(1f, height * window.heightPercent)
            var left = width * window.xPercent
            var top = height * window.yPercent
            left = left.coerceIn(0f, max(0f, width - boxW))
            top = top.coerceIn(0f, max(0f, height - boxH))
            val right = left + boxW
            val bottom = top + boxH

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    roiTouchStartX = event.x
                    roiTouchStartY = event.y
                    roiStartXPercent = window.xPercent
                    roiStartYPercent = window.yPercent
                    roiStartWPercent = window.widthPercent
                    roiStartHPercent = window.heightPercent
                    roiTouchMode = when {
                        event.x >= right - roiCornerHitPx && event.y >= bottom - roiCornerHitPx && event.x <= right && event.y <= bottom ->
                            RoiTouchMode.RESIZE
                        event.x in left..right && event.y in top..bottom -> RoiTouchMode.MOVE
                        else -> RoiTouchMode.NONE
                    }
                    if (roiTouchMode == RoiTouchMode.NONE) {
                        setScanAreaFromPercents(
                            ((event.x - boxW / 2f) / width).coerceIn(0f, 1f),
                            ((event.y - boxH / 2f) / height).coerceIn(0f, 1f),
                            window.widthPercent,
                            window.heightPercent
                        )
                        updateRoiOverlay()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (roiTouchMode == RoiTouchMode.NONE) return@setOnTouchListener true
                    val dx = event.x - roiTouchStartX
                    val dy = event.y - roiTouchStartY
                    when (roiTouchMode) {
                        RoiTouchMode.MOVE -> {
                            val nextLeft = (roiStartXPercent * width + dx).coerceIn(0f, max(0f, width - boxW))
                            val nextTop = (roiStartYPercent * height + dy).coerceIn(0f, max(0f, height - boxH))
                            setScanAreaFromPercents(
                                nextLeft / width,
                                nextTop / height,
                                roiStartWPercent,
                                roiStartHPercent
                            )
                        }
                        RoiTouchMode.RESIZE -> {
                            val maxW = 1f - roiStartXPercent
                            val maxH = 1f - roiStartYPercent
                            val nextW = ((roiStartWPercent * width + dx) / width).coerceIn(0.01f, maxW)
                            val nextH = ((roiStartHPercent * height + dy) / height).coerceIn(0.01f, maxH)
                            setScanAreaFromPercents(
                                roiStartXPercent,
                                roiStartYPercent,
                                nextW,
                                nextH
                            )
                        }
                        RoiTouchMode.NONE -> {}
                    }
                    updateRoiOverlay()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    roiTouchMode = RoiTouchMode.NONE
                    true
                }
                else -> false
            }
        }
        wireScanAreaInputs()
        refreshRoiButton.setOnClickListener {
            if (hasActiveCompilation()) {
                emitTransientStatus("ROI controls are locked while compilation is active.")
                return@setOnClickListener
            }
            val window = readScanWindow()
            setScanAreaFromPercents(window.xPercent, window.yPercent, window.widthPercent, window.heightPercent)
            if (previewBitmap != null) {
                updateRoiOverlay()
                persistDraftState(CompilationPipelineState.READY, "ROI refreshed")
                emitRoiStatus("ROI refreshed from input values")
            } else {
                persistDraftState(CompilationPipelineState.ROI_SELECTION, "ROI values saved")
                emitRoiStatus("ROI values saved. Capture a frame to preview ROI box.")
            }
        }

        binding.processButton.setOnClickListener {
            if (isBusy || compilationStartInFlight) return@setOnClickListener
            val source = selectedVideoUri
            if (source == null) {
                Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val quality = qualityOptions[qualitySpinner.selectedItemPosition]
            val format = formatOptions[formatSpinner.selectedItemPosition]
            val transitionStyle = transitionStyleOptions[transitionStyleSpinner.selectedItemPosition.coerceIn(0, transitionStyleOptions.lastIndex)]
            compilationStartInFlight = true
            setUiBusy(true)
            if (selectedVideoUris.size > 1) {
                startCompilationBatch(selectedVideoUris.toList(), quality, format, transitionStyle)
            } else {
                startCompilationJob(source, quality, format, transitionStyle)
            }
        }
        checkUpdatesButton.setOnClickListener {
            checkForUpdates(force = true)
        }
        autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            updateRepository.autoDownloadEnabled = checked
            emitUpdateStatus(
                if (checked) {
                    "Verified updates will download automatically. Android still requires install approval."
                } else {
                    "Automatic update downloads are off."
                }
            )
            if (checked) checkForUpdates(force = false)
        }

        crashLogButton.setOnClickListener {
            showCrashLogDialog()
        }

        experimentalModeSwitch.setOnCheckedChangeListener { _, checked ->
            experimentalDownscaleSpinner.isEnabled = checked
            experimentalModeWarningText.visibility = if (checked) View.VISIBLE else View.GONE
        }

        backgroundStatusMode = if (backgroundStatusPrefs.getBoolean(KEY_CORE_STATUS_ENABLED, false)) {
            BackgroundStatusMode.CORE_ACTIVITY
        } else {
            BackgroundStatusMode.CONCISE
        }
        coreStatusSwitch.isChecked = backgroundStatusMode == BackgroundStatusMode.CORE_ACTIVITY
        coreStatusSwitch.setOnCheckedChangeListener { _, checked ->
            backgroundStatusMode = if (checked) {
                BackgroundStatusMode.CORE_ACTIVITY
            } else {
                BackgroundStatusMode.CONCISE
            }
            backgroundStatusPrefs.edit().putBoolean(KEY_CORE_STATUS_ENABLED, checked).apply()
            previousBackgroundStatus = null
            lastBackgroundFeedAtMs = 0L
            addStatusLine("Status display: ${backgroundStatusMode.label}")
        }

        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualityOptions.map { it.label },
        )

        formatSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formatOptions.map { it.label },
        )
        scanSpeedSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            checkpointProfiles.map { it.label },
        )
        scanSpeedSpinner.setSelection(0, false)
        experimentalDownscaleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            experimentalDownscaleOptions.map { "${it}x${it}" },
        )
        experimentalDownscaleSpinner.setSelection(2, false)
        experimentalModeSwitch.isChecked = false
        experimentalDownscaleSpinner.isEnabled = false
        transitionStyleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            transitionStyleOptions.map { it.label },
        )
        transitionStyleSpinner.setSelection(1, false)
        setScanAreaFromPercents(
            defaultScanWindow.xPercent,
            defaultScanWindow.yPercent,
            defaultScanWindow.widthPercent,
            defaultScanWindow.heightPercent
        )

        binding.progressBar.max = 100
        progressPercentText.text = "0%"
        clearStatusFeed("Ready")
        renderPictureInPictureMode(isInPictureInPictureMode)
        updatePictureInPictureParams(activeJob = false)
        if (readCrashReport(this) != null) {
            emitLogStatus("Crash log available. Tap Open crash log.")
        }
        restoreActiveCompilationWork()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.POST_NOTIFICATIONS
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty() && needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionRequestLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCompilationJob(
        uri: Uri,
        quality: ExportQuality,
        format: ExportFormat,
        transitionStyle: TransitionStyle
    ) {
        lifecycleScope.launch {
            try {
                startCompilationJobOnBackgroundThread(uri, quality, format, transitionStyle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordHandledWorkerFailure(
                    this@MainActivity,
                    logTag,
                    "Compilation enqueue failed",
                    failure
                )
                currentPipelineState = CompilationPipelineState.FAILED
                emitCompilationProgress(
                    "Unable to queue compilation: ${failure.message ?: failure::class.java.simpleName}",
                    100
                )
                setUiBusy(false)
                isBusy = false
            } finally {
                compilationStartInFlight = false
            }
        }
    }

    private suspend fun startCompilationJobOnBackgroundThread(
        uri: Uri,
        quality: ExportQuality,
        format: ExportFormat,
        transitionStyle: TransitionStyle
    ) {
        val previousRecord = compilationJobStore.load()
        val uniqueWorkInfos = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(compilationWorkName).get()
        }
        val preflightDecision = decideUniqueCompilationEnqueue(
            proposedWorkId = "pending-explicit-request",
            persistedWorkId = previousRecord?.workId,
            persistedWorkIsActive = previousRecord?.state?.isActive == true,
            uniqueWork = uniqueWorkInfos.map { workInfo ->
                UniqueCompilationWorkSnapshot(
                    workId = workInfo.id.toString(),
                    isActive = isActiveWorkManagerState(workInfo.state)
                )
            }
        )
        if (preflightDecision.action == CompilationEnqueueAction.ATTACH_EXISTING) {
            val existing = uniqueWorkInfos.firstOrNull {
                it.id.toString() == preflightDecision.workIdToPersistAndObserve
            } ?: throw IllegalStateException("Persisted active work disappeared during enqueue preflight")
            AppLog.i(
                this@MainActivity,
                logTag,
                "work UUID=${existing.id} unique work name=$compilationWorkName enqueue policy=KEEP " +
                    "previous job state=${existing.state} new job state=ATTACHED cancellation reason=none"
            )
            attachToCompilationWork(existing, previousRecord)
            emitTransientStatus("Compilation already active; reconnected to ${existing.id}")
            return
        }
        val replaceStaleWork = preflightDecision.action == CompilationEnqueueAction.REPLACE_STALE
        if (replaceStaleWork) {
            AppLog.w(
                this@MainActivity,
                logTag,
                "Explicit new compilation will REPLACE orphaned active unique work because no matching active durable record exists"
            )
        }

        val scanProfile = selectedCheckpointProfile()
        val experimentalMode = experimentalModeSwitch.isChecked
        val requestedScanMode = if (experimentalMode) ScanMode.Experimental else scanProfile.mode
        if (experimentalMode && !readScanWindow().let { it.widthPercent > 0.001f && it.heightPercent > 0.001f }) {
            emitRoiStatus("Invalid ROI. Capture a frame and choose a valid ROI before running experimental scan.")
            setUiBusy(false)
            isBusy = false
            return
        }

        clearPendingCompilationPreview(deleteFile = true)
        displayTransitionSummaries(
            clipPlanJson = "",
            emptyMessage = getString(R.string.transition_results_scanning)
        )
        setUiBusy(true)
        isBusy = true
        clearStatusFeed("Queued")

        val scanWindowJson = JSONObject().apply {
            put("xPercent", selectedScanWindow.xPercent)
            put("yPercent", selectedScanWindow.yPercent)
            put("widthPercent", selectedScanWindow.widthPercent)
            put("heightPercent", selectedScanWindow.heightPercent)
        }.toString()
        val expectedOutput = createExpectedCompilationOutput(format)

        val inputData = Data.Builder()
            .putString(CompilationWorker.KEY_SOURCE_URI, uri.toString())
            .putString(CompilationWorker.KEY_SCAN_WINDOW, scanWindowJson)
            .putInt(CompilationWorker.KEY_SCAN_MODE, requestedScanMode.ordinal)
            .putLong(CompilationWorker.KEY_CHECKPOINT_INTERVAL_MS, scanProfile.frameStepMs)
            .putString(CompilationWorker.KEY_SCANNER_PROFILE_ID, scanProfile.scannerProfileId)
            .putInt(CompilationWorker.KEY_EXPERIMENTAL_DOWNSCALE, selectedExperimentalDownscaleSize())
            .putInt(CompilationWorker.KEY_QUALITY_ORDINAL, quality.ordinal)
            .putInt(CompilationWorker.KEY_FORMAT_ORDINAL, format.ordinal)
            .putInt(CompilationWorker.KEY_TRANSITION_STYLE_ORDINAL, transitionStyle.ordinal)
            .putInt(CompilationWorker.KEY_VIDEO_ROTATION, selectedVideoRotationDegrees)
            .putString(CompilationJobContract.KEY_EXPECTED_OUTPUT_PATH, expectedOutput.absolutePath)
            .build()

        val request = OneTimeWorkRequestBuilder<CompilationWorker>()
            .setInputData(inputData)
            .build()
        val enqueuePolicy = if (replaceStaleWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

        val now = System.currentTimeMillis()
        val record = CompilationJobRecord(
            workId = request.id.toString(),
            uniqueWorkName = compilationWorkName,
            sourceUri = uri.toString(),
            expectedOutputPath = expectedOutput.absolutePath,
            state = CompilationPipelineState.QUEUED,
            stage = "queued",
            progressPercent = 0,
            progressMessage = "Compilation queued",
            createdAtMs = now,
            updatedAtMs = now,
            outputUri = compilationContentUri(expectedOutput).toString(),
            sourcePermissionPersisted = contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            },
            settings = CompilationJobSettings(
                scanWindowJson = scanWindowJson,
                scanModeOrdinal = requestedScanMode.ordinal,
                checkpointIntervalMs = scanProfile.frameStepMs,
                experimentalDownscale = selectedExperimentalDownscaleSize(),
                qualityOrdinal = quality.ordinal,
                formatOrdinal = format.ordinal,
                transitionStyleOrdinal = transitionStyle.ordinal,
                videoRotation = selectedVideoRotationDegrees
            )
        )
        if (!compilationJobStore.save(record)) {
            expectedOutput.delete()
            throw IllegalStateException("Could not persist compilation job before enqueue")
        }
        currentPipelineState = CompilationPipelineState.QUEUED
        emitCompilationProgress(
            "Starting ${if (requestedScanMode == ScanMode.Experimental) "Legacy Experimental change-map" else "PTS-aware OCR"} scan " +
                "(${scanProfile.label}) and preparing output...",
            0
        )

        val accepted = withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(compilationWorkName, enqueuePolicy, request).result.get()
            val unique = workManager.getWorkInfosForUniqueWork(compilationWorkName).get()
            unique.firstOrNull { isActiveWorkManagerState(it.state) }
                ?: workManager.getWorkInfoById(request.id).get()
        } ?: throw IllegalStateException("WorkManager did not retain the queued compilation")

        if (accepted.id != request.id) {
            expectedOutput.delete()
            val acceptedRecord = previousRecord?.takeIf { it.workId == accepted.id.toString() }
                ?: record.copy(
                    workId = accepted.id.toString(),
                    expectedOutputPath = "",
                    outputUri = "",
                    stage = "metadata recovery",
                    progressMessage = "Attached to existing unique work; original output metadata was unavailable",
                    errorStage = "metadata recovery",
                    errorMessage = "$enqueuePolicy retained a different WorkRequest"
                )
            compilationJobStore.save(acceptedRecord)
            AppLog.w(
                this@MainActivity,
                logTag,
                "KEEP attached existing UUID=${accepted.id}; generated UUID=${request.id} was not accepted"
            )
        }
        AppLog.i(
            this@MainActivity,
            logTag,
            "work UUID=${accepted.id} unique work name=$compilationWorkName enqueue policy=$enqueuePolicy " +
                "previous job state=${previousRecord?.state ?: "none"} new job state=${accepted.state} cancellation reason=none"
        )
        attachToCompilationWork(accepted, compilationJobStore.load())
    }

    private val selectedScanWindow: ScanWindow
        get() = readScanWindow()

    private fun selectedCheckpointProfile(): ScanProfile {
        val index = scanSpeedSpinner.selectedItemPosition.coerceIn(0, checkpointProfiles.lastIndex)
        return checkpointProfiles[index]
    }

    private fun selectedExperimentalDownscaleSize(): Int {
        val index = experimentalDownscaleSpinner.selectedItemPosition.coerceIn(0, experimentalDownscaleOptions.lastIndex)
        return experimentalDownscaleOptions[index]
    }

    private fun observeCompilationWork(workId: UUID) {
        dismissCompilationWorkObserver()
        activeWorkObserver = Observer { workInfo ->
            if (workInfo == null) {
                compilationJobStore.load()?.takeIf { it.workId == workId.toString() && it.state.isActive }?.let {
                    handleMissingWorkInfo(it)
                }
                return@Observer
            }
            handleCompilationWorkInfo(workInfo)
        }
        activeWorkInfoLiveData = workManager.getWorkInfoByIdLiveData(workId)
        activeWorkInfoLiveData?.observe(this, activeWorkObserver!!)
    }

    private fun attachToCompilationWork(workInfo: WorkInfo, savedRecord: CompilationJobRecord?) {
        val existing = savedRecord?.takeIf { it.workId == workInfo.id.toString() }
        val now = System.currentTimeMillis()
        val recovered = existing ?: CompilationJobRecord(
            workId = workInfo.id.toString(),
            uniqueWorkName = compilationWorkName,
            sourceUri = savedRecord?.sourceUri.orEmpty(),
            expectedOutputPath = savedRecord?.expectedOutputPath.orEmpty(),
            state = if (isActiveWorkManagerState(workInfo.state)) CompilationPipelineState.QUEUED else CompilationPipelineState.IDLE,
            stage = "work recovery",
            progressPercent = savedRecord?.progressPercent ?: 0,
            progressMessage = "Recovered WorkManager job ${workInfo.id}",
            createdAtMs = savedRecord?.createdAtMs?.takeIf { it > 0L } ?: now,
            updatedAtMs = now,
            settings = savedRecord?.settings ?: CompilationJobSettings(),
            errorStage = if (savedRecord == null) "metadata recovery" else "",
            errorMessage = if (savedRecord == null) "Persisted job metadata was missing; attached by unique work name" else ""
        )
        compilationJobStore.save(recovered)
        displayTransitionSummaries(
            recovered.clipPlanJson,
            getString(R.string.transition_results_scanning)
        )
        currentPipelineState = recovered.state
        compilationWorkId = workInfo.id
        latestWorkManagerState = workInfo.state
        clearProgressNotification()
        observeCompilationWork(workInfo.id)
        handleCompilationWorkInfo(workInfo)
    }

    private fun handleCompilationWorkInfo(workInfo: WorkInfo) {
        latestWorkManagerState = workInfo.state
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val data = workInfo.progress
                val phase = data.getString(CompilationWorker.KEY_PROGRESS_PHASE)
                    ?: if (workInfo.state == WorkInfo.State.BLOCKED) "queued" else "preparing"
                val percent = data.getInt(
                    CompilationWorker.KEY_PROGRESS_PERCENT,
                    compilationJobStore.load()?.progressPercent ?: 0
                )
                val elapsed = data.getLong(CompilationWorker.KEY_ELAPSED_MS, 0L)
                val message = data.getString(CompilationWorker.KEY_PROGRESS_MESSAGE)
                    ?: if (workInfo.state == WorkInfo.State.BLOCKED) {
                        "Compilation is waiting for WorkManager prerequisites"
                    } else {
                        "Compilation $phase"
                    }
                val explicitState = data.getString(CompilationJobContract.KEY_PIPELINE_STATE)?.let { raw ->
                    runCatching { CompilationPipelineState.valueOf(raw) }.getOrNull()
                }
                val nextState = explicitState ?: pipelineStateForProgressPhase(phase, currentPipelineState.takeIf { it.isActive }
                    ?: CompilationPipelineState.PREPARING)
                currentPipelineState = nextState
                renderTransitionResults(compilationJobStore.load())
                elapsedTimeText.text = "Elapsed: ${formatElapsedForUi(elapsed)}"
                emitCompilationProgress("$message • elapsed ${formatElapsedForUi(elapsed)}", percent, phase, nextState)
                isBusy = true
                setUiBusy(true)
            }
            WorkInfo.State.SUCCEEDED -> handleSucceededCompilation(workInfo)
            WorkInfo.State.FAILED -> handleFailedCompilation(workInfo)
            WorkInfo.State.CANCELLED -> handleCancelledCompilation(workInfo)
        }
    }

    private fun handleSucceededCompilation(workInfo: WorkInfo) {
        if (terminalHandlingWorkId == workInfo.id) return
        terminalHandlingWorkId = workInfo.id
        dismissCompilationWorkObserver()
        lifecycleScope.launch {
            val saved = compilationJobStore.load()
            val reportedTerminalState = workInfo.outputData
                .getString(CompilationJobContract.KEY_PIPELINE_STATE)
                ?.let { raw -> runCatching { CompilationPipelineState.valueOf(raw) }.getOrNull() }
            val previewClassification = workInfo.outputData
                .getString(CompilationJobContract.KEY_PREVIEW_CLASSIFICATION)
                ?.let { raw -> runCatching { CompilationPreviewClassification.valueOf(raw) }.getOrNull() }
                ?: saved?.previewClassification
                ?: CompilationPreviewClassification.NONE
            val terminalState = if (
                reportedTerminalState == CompilationPipelineState.PROVISIONAL_SUCCEEDED ||
                previewClassification == CompilationPreviewClassification.PROVISIONAL
            ) {
                CompilationPipelineState.PROVISIONAL_SUCCEEDED
            } else {
                CompilationPipelineState.SUCCEEDED
            }
            val completionMessage = if (terminalState == CompilationPipelineState.PROVISIONAL_SUCCEEDED) {
                "Provisional preview ready"
            } else {
                "Compilation complete"
            }
            val outputPath = workInfo.outputData.getString(CompilationWorker.KEY_OUTPUT_PATH)
                .orEmpty().ifBlank { saved?.expectedOutputPath.orEmpty() }
            val outputUri = workInfo.outputData.getString(CompilationJobContract.KEY_OUTPUT_URI)
                .orEmpty().ifBlank { saved?.outputUri.orEmpty() }
            val outputFormat = exportFormatFromOrdinal(
                workInfo.outputData.getInt(
                    CompilationWorker.KEY_FORMAT_ORDINAL,
                    saved?.settings?.formatOrdinal ?: ExportFormat.Mp4.ordinal
                )
            )
            val verified = withContext(Dispatchers.IO) { verifyCompilationOutput(outputPath, outputUri) }
            if (verified == null) {
                val message = "Worker reported success but the output is missing or not a readable video"
                compilationJobStore.update(workInfo.id.toString()) { record ->
                    record.copy(
                        state = CompilationPipelineState.FAILED,
                        stage = "output verification",
                        progressPercent = 100,
                        progressMessage = message,
                        completedAtMs = System.currentTimeMillis(),
                        errorStage = "output verification",
                        errorType = "InvalidOutput",
                        errorMessage = message,
                        previewAvailable = false
                    )
                }
                currentPipelineState = CompilationPipelineState.FAILED
                emitCompilationProgress("Compilation failed during output verification: $message", 100)
                finalizeCompilationWork(workInfo.id, clearBusy = true)
                return@launch
            }

            val completed = compilationJobStore.update(workInfo.id.toString()) { record ->
                record.copy(
                    state = terminalState,
                    stage = if (terminalState == CompilationPipelineState.PROVISIONAL_SUCCEEDED) {
                        "provisional succeeded"
                    } else {
                        "succeeded"
                    },
                    progressPercent = 100,
                    progressMessage = completionMessage,
                    completedAtMs = System.currentTimeMillis(),
                    outputUri = verified.uri.toString(),
                    outputPath = verified.file.absolutePath,
                    outputSizeBytes = verified.sizeBytes,
                    outputDurationMs = verified.durationMs,
                    previewAvailable = true,
                    previewClassification = previewClassification.takeIf {
                        it != CompilationPreviewClassification.NONE
                    } ?: CompilationPreviewClassification.CONFIRMED,
                    lastSuccessfulStage = "preview",
                    errorStage = "",
                    errorType = "",
                    errorMessage = ""
                )
            }
            currentPipelineState = terminalState
            showPendingCompilationPreview(verified.file, outputFormat, verified.uri)
            displayTransitionSummaries(
                completed?.clipPlanJson ?: saved?.clipPlanJson.orEmpty(),
                getString(R.string.transition_results_none_confirmed)
            )
            emitCompilationProgress(
                "$completionMessage: ${formatBytes(verified.sizeBytes)}, ${formatDurationMs(verified.durationMs)}",
                100
            )
            AppLog.i(this@MainActivity, logTag, "Restored output URI=${completed?.outputUri} size=${verified.sizeBytes} durationMs=${verified.durationMs}")
            finalizeCompilationWork(workInfo.id, clearBusy = true)
        }
    }

    private fun handleFailedCompilation(workInfo: WorkInfo) {
        val noResults = workInfo.outputData.getBoolean(CompilationWorker.KEY_NO_TRANSITIONS_DETECTED, false)
        val stage = workInfo.outputData.getString(CompilationJobContract.KEY_ERROR_STAGE) ?: "worker"
        val errorType = workInfo.outputData.getString(CompilationJobContract.KEY_ERROR_TYPE) ?: "CompilationFailure"
        val reason = workInfo.outputData.getString(CompilationWorker.KEY_ERROR_MESSAGE)
            ?: if (noResults) "No transitions detected before scan budget expired" else "Compilation failed"
        val terminalState = if (noResults) CompilationPipelineState.NO_RESULTS else CompilationPipelineState.FAILED
        currentPipelineState = terminalState
        compilationJobStore.update(workInfo.id.toString()) { record ->
            record.copy(
                state = terminalState,
                stage = stage,
                progressPercent = 100,
                progressMessage = reason,
                completedAtMs = System.currentTimeMillis(),
                errorStage = stage,
                errorType = errorType,
                errorMessage = reason,
                previewAvailable = false
            )
        }
        renderTransitionResults(compilationJobStore.load())
        emitCompilationProgress(
            if (noResults) "No results: $reason" else "Compilation failed at $stage: $reason",
            100
        )
        finalizeCompilationWork(workInfo.id, clearBusy = true)
    }

    private fun handleCancelledCompilation(workInfo: WorkInfo) {
        val expected = compilationJobStore.load()?.expectedOutputPath.orEmpty()
        if (expected.isNotBlank()) {
            val partial = File(expected)
            if (partial.exists()) {
                runCatching { check(partial.delete()) { "Unable to delete partial output ${partial.absolutePath}" } }
                    .onFailure { AppLog.w(this, logTag, "Cancelled-output cleanup failed", it) }
            }
        }
        AppLog.i(
            this,
            logTag,
            "work UUID=${workInfo.id} unique work name=$compilationWorkName enqueue policy=KEEP " +
                "previous job state=${compilationJobStore.load()?.state ?: "unknown"} new job state=CANCELLED cancellation reason=user/notification"
        )
        currentPipelineState = CompilationPipelineState.CANCELLED
        compilationJobStore.update(workInfo.id.toString()) { record ->
            record.copy(
                state = CompilationPipelineState.CANCELLED,
                stage = "cancelled",
                progressPercent = record.progressPercent,
                progressMessage = "Compilation cancelled",
                completedAtMs = System.currentTimeMillis(),
                errorStage = "",
                errorType = "",
                errorMessage = "",
                previewAvailable = false
            )
        }
        renderTransitionResults(compilationJobStore.load())
        emitCompilationProgress("Compilation cancelled", compilationJobStore.load()?.progressPercent ?: 0)
        finalizeCompilationWork(workInfo.id, clearBusy = true)
    }

    private fun finalizeCompilationWork(workId: UUID, clearBusy: Boolean) {
        dismissCompilationWorkObserver()
        if (clearBusy) {
            isBusy = false
            setUiBusy(false)
            clearProgressNotification()
        }
        if (compilationWorkId == workId) {
            compilationWorkId = null
        }
        terminalHandlingWorkId = null
    }

    private fun dismissCompilationWorkObserver() {
        activeWorkObserver?.let { observer ->
            activeWorkInfoLiveData?.removeObserver(observer)
        }
        activeWorkInfoLiveData = null
        activeWorkObserver = null
    }

    private fun restoreActiveCompilationWork() {
        if (compilationRestoreInFlight) return
        compilationRestoreInFlight = true
        lifecycleScope.launch {
            try {
                val saved = compilationJobStore.load() ?: compilationJobStore.loadLastSuccess()
                val parsed = saved?.workId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val status = withContext(Dispatchers.IO) {
                    val byId = parsed?.let { workManager.getWorkInfoById(it).get() }
                    byId ?: workManager.getWorkInfosForUniqueWork(compilationWorkName).get()
                        .firstOrNull { isActiveWorkManagerState(it.state) }
                }

                saved?.let { restoreSavedJobSelection(it) }
                when {
                    status != null -> attachToCompilationWork(status, saved)
                    saved == null -> {
                        currentPipelineState = CompilationPipelineState.IDLE
                        latestWorkManagerState = null
                        setUiBusy(false)
                    }
                    saved.state.isActive -> handleMissingWorkInfo(saved)
                    else -> restorePersistedTerminalOrDraft(saved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordHandledWorkerFailure(this@MainActivity, logTag, "Compilation state restoration failed", failure)
                emitTransientStatus("Could not restore compilation state: ${failure.message ?: failure::class.java.simpleName}")
            } finally {
                compilationRestoreInFlight = false
            }
        }
    }

    private fun restoreActiveBatchWork() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork("compilation-batch").get().firstOrNull { isActiveWorkManagerState(it.state) }
            } ?: return@launch
            batchWorkId = info.id
            compilationBatchStore.load()?.let { record ->
                binding.batchQueueText.text = record.items.mapIndexed { index, item -> "${index + 1}. ${item.label} — ${item.state}" }.joinToString("\n")
            }
            observeBatchWork(info.id)
            isBusy = true
            setUiBusy(true)
        }
    }

    private fun hasActiveCompilation(): Boolean {
        return isActiveWorkManagerState(latestWorkManagerState) ||
            currentPipelineState.isActive ||
            compilationJobStore.load()?.state?.isActive == true ||
            batchWorkId != null
    }

    private fun canInitializeRoiUi(): Boolean {
        return shouldInitializeRoi(compilationJobStore.load()?.state ?: currentPipelineState, latestWorkManagerState)
    }

    private fun currentJobSettings(): CompilationJobSettings {
        val profile = selectedCheckpointProfile()
        val mode = if (experimentalModeSwitch.isChecked) ScanMode.Experimental else profile.mode
        return CompilationJobSettings(
            scanWindowJson = JSONObject().apply {
                val window = readScanWindow()
                put("xPercent", window.xPercent)
                put("yPercent", window.yPercent)
                put("widthPercent", window.widthPercent)
                put("heightPercent", window.heightPercent)
            }.toString(),
            scanModeOrdinal = mode.ordinal,
            checkpointIntervalMs = profile.frameStepMs,
            experimentalDownscale = selectedExperimentalDownscaleSize(),
            qualityOrdinal = qualitySpinner.selectedItemPosition.coerceIn(0, qualityOptions.lastIndex),
            formatOrdinal = formatSpinner.selectedItemPosition.coerceIn(0, formatOptions.lastIndex),
            transitionStyleOrdinal = transitionStyleSpinner.selectedItemPosition.coerceIn(0, transitionStyleOptions.lastIndex),
            videoRotation = selectedVideoRotationDegrees
        )
    }

    private fun persistDraftState(state: CompilationPipelineState, message: String) {
        if (compilationJobStore.load()?.state?.isActive == true) return
        val now = System.currentTimeMillis()
        compilationJobStore.save(
            CompilationJobRecord(
                workId = "",
                uniqueWorkName = compilationWorkName,
                sourceUri = selectedVideoUri?.toString().orEmpty(),
                expectedOutputPath = "",
                state = state,
                stage = state.name.lowercase(),
                progressPercent = 0,
                progressMessage = message,
                createdAtMs = now,
                updatedAtMs = now,
                settings = currentJobSettings()
            )
        )
        currentPipelineState = state
        latestWorkManagerState = null
    }

    private fun createExpectedCompilationOutput(format: ExportFormat): File {
        val directory = File(filesDir, "compilations").apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("Could not create compilation output directory")
        }
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        return File(directory, "compilation-${System.currentTimeMillis()}-${UUID.randomUUID()}.${safeFormat.extension}")
    }

    private fun compilationContentUri(file: File): Uri = FileProvider.getUriForFile(
        this,
        "$packageName.updateprovider",
        file
    )

    private data class ActivityVerifiedOutput(
        val file: File,
        val uri: Uri,
        val sizeBytes: Long,
        val durationMs: Long
    )

    private fun verifyCompilationOutput(outputPath: String, outputUri: String): ActivityVerifiedOutput? {
        val file = outputPath.takeIf { it.isNotBlank() }?.let(::File)
            ?: outputUri.takeIf { it.startsWith("file:") }?.let { File(Uri.parse(it).path.orEmpty()) }
            ?: return null
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) return null
        val uri = runCatching {
            outputUri.takeIf { it.isNotBlank() }?.let(Uri::parse)?.takeIf { candidate ->
                candidate.scheme == ContentResolver.SCHEME_CONTENT && runCatching {
                    contentResolver.openInputStream(candidate).use { input -> input != null && input.read() >= 0 }
                }.getOrDefault(false)
            } ?: compilationContentUri(file)
        }.getOrNull() ?: return null
        val canOpen = runCatching {
            contentResolver.openInputStream(uri).use { input -> input != null && input.read() >= 0 }
        }.getOrDefault(false)
        if (!canOpen) return null

        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        val durationMs = try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            extractor.setDataSource(file.absolutePath)
            val hasVideo = (0 until extractor.trackCount).any { trackIndex ->
                extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (!hasVideo) return null
            duration
        } catch (failure: Exception) {
            recordHandledWorkerFailure(this, logTag, "Saved compilation output verification failed", failure)
            return null
        } finally {
            runCatching { retriever.release() }
                .onFailure { AppLog.w(this, logTag, "Output retriever cleanup failed", it) }
            runCatching { extractor.release() }
                .onFailure { AppLog.w(this, logTag, "Output extractor cleanup failed", it) }
        }
        if (durationMs <= 0L) return null
        return ActivityVerifiedOutput(file, uri, file.length(), durationMs)
    }

    private fun restoreSavedJobSelection(record: CompilationJobRecord) {
        val settings = record.settings
        if (record.sourceUri.isNotBlank()) {
            selectedVideoUri = runCatching { Uri.parse(record.sourceUri) }.getOrNull()
            binding.selectedVideo.text = record.sourceUri
        }
        selectedVideoRotationDegrees = settings.videoRotation
        qualitySpinner.setSelection(settings.qualityOrdinal.coerceIn(0, qualityOptions.lastIndex), false)
        formatSpinner.setSelection(settings.formatOrdinal.coerceIn(0, formatOptions.lastIndex), false)
        transitionStyleSpinner.setSelection(settings.transitionStyleOrdinal.coerceIn(0, transitionStyleOptions.lastIndex), false)
        checkpointProfiles.indexOfFirst {
            it.frameStepMs == settings.checkpointIntervalMs && it.mode.ordinal == settings.scanModeOrdinal
        }.takeIf { it >= 0 }?.let { scanSpeedSpinner.setSelection(it, false) }
        experimentalModeSwitch.isChecked = settings.scanModeOrdinal == ScanMode.Experimental.ordinal
        experimentalDownscaleOptions.indexOf(settings.experimentalDownscale).takeIf { it >= 0 }
            ?.let { experimentalDownscaleSpinner.setSelection(it, false) }
        runCatching {
            val json = JSONObject(settings.scanWindowJson)
            setScanAreaFromPercents(
                json.optDouble("xPercent", defaultScanWindow.xPercent.toDouble()).toFloat(),
                json.optDouble("yPercent", defaultScanWindow.yPercent.toDouble()).toFloat(),
                json.optDouble("widthPercent", defaultScanWindow.widthPercent.toDouble()).toFloat(),
                json.optDouble("heightPercent", defaultScanWindow.heightPercent.toDouble()).toFloat()
            )
        }
    }

    private fun restorePersistedTerminalOrDraft(record: CompilationJobRecord) {
        currentPipelineState = record.state
        latestWorkManagerState = null
        renderTransitionResults(record)
        when (record.state) {
            CompilationPipelineState.SUCCEEDED,
            CompilationPipelineState.PROVISIONAL_SUCCEEDED -> restoreSuccessfulRecord(record)
            CompilationPipelineState.FAILED -> {
                emitCompilationProgress(
                    "Compilation failed at ${record.errorStage.ifBlank { record.stage }}: ${record.errorMessage.ifBlank { record.progressMessage }}",
                    100
                )
                setUiBusy(false)
                isBusy = false
            }
            CompilationPipelineState.CANCELLED -> {
                emitCompilationProgress("Compilation cancelled", record.progressPercent)
                setUiBusy(false)
                isBusy = false
            }
            CompilationPipelineState.NO_RESULTS -> {
                emitCompilationProgress(
                    record.errorMessage.ifBlank { "No transitions detected before scan budget expired" },
                    100
                )
                setUiBusy(false)
                isBusy = false
            }
            else -> {
                setUiBusy(false)
                isBusy = false
                emitRoiStatus(record.progressMessage.ifBlank { "ROI setup ready" })
                val source = selectedVideoUri
                if (source != null && canInitializeRoiUi()) setupVideoPreview(source)
            }
        }
    }

    private fun restoreSuccessfulRecord(record: CompilationJobRecord) {
        displayTransitionSummaries(
            record.clipPlanJson,
            getString(R.string.transition_results_none_confirmed)
        )
        lifecycleScope.launch {
            val provisional = record.state == CompilationPipelineState.PROVISIONAL_SUCCEEDED ||
                record.previewClassification == CompilationPreviewClassification.PROVISIONAL
            val completionMessage = if (provisional) "Provisional preview ready" else "Compilation complete"
            val verified = withContext(Dispatchers.IO) {
                verifyCompilationOutput(record.outputPath.ifBlank { record.expectedOutputPath }, record.outputUri)
            }
            if (verified == null) {
                val message = "Saved compilation output is missing or unreadable"
                compilationJobStore.update(record.workId) { saved ->
                    saved.copy(
                        state = CompilationPipelineState.FAILED,
                        stage = "output restoration",
                        progressMessage = message,
                        errorStage = "output restoration",
                        errorType = "MissingOutput",
                        errorMessage = message,
                        previewAvailable = false,
                        completedAtMs = System.currentTimeMillis()
                    )
                }
                currentPipelineState = CompilationPipelineState.FAILED
                emitCompilationProgress("Compilation output unavailable: $message", 100)
                setUiBusy(false)
                return@launch
            }
            val format = exportFormatFromOrdinal(record.settings.formatOrdinal)
            showPendingCompilationPreview(verified.file, format, verified.uri)
            compilationJobStore.update(record.workId) { saved ->
                saved.copy(
                    outputPath = verified.file.absolutePath,
                    outputUri = verified.uri.toString(),
                    outputSizeBytes = verified.sizeBytes,
                    outputDurationMs = verified.durationMs,
                    previewAvailable = true,
                    previewClassification = if (provisional) {
                        CompilationPreviewClassification.PROVISIONAL
                    } else {
                        CompilationPreviewClassification.CONFIRMED
                    },
                    lastSuccessfulStage = "preview"
                )
            }
            emitCompilationProgress(
                "$completionMessage: ${formatBytes(verified.sizeBytes)}, ${formatDurationMs(verified.durationMs)}",
                100
            )
            setUiBusy(false)
            isBusy = false
        }
    }

    private fun handleMissingWorkInfo(record: CompilationJobRecord) {
        if (!record.state.isActive) return
        dismissCompilationWorkObserver()
        compilationWorkId = null
        latestWorkManagerState = null
        lifecycleScope.launch {
            val verified = withContext(Dispatchers.IO) {
                verifyCompilationOutput(record.outputPath.ifBlank { record.expectedOutputPath }, record.outputUri)
            }
            if (verified != null) {
                val recoveredState = if (
                    record.previewClassification == CompilationPreviewClassification.PROVISIONAL
                ) {
                    CompilationPipelineState.PROVISIONAL_SUCCEEDED
                } else {
                    CompilationPipelineState.SUCCEEDED
                }
                val recoveredMessage = if (recoveredState == CompilationPipelineState.PROVISIONAL_SUCCEEDED) {
                    "WorkManager history missing; verified provisional preview"
                } else {
                    "WorkManager history missing; verified completed output"
                }
                val recovered = record.copy(
                    state = recoveredState,
                    stage = "recovered output",
                    progressPercent = 100,
                    progressMessage = recoveredMessage,
                    completedAtMs = System.currentTimeMillis(),
                    outputPath = verified.file.absolutePath,
                    outputUri = verified.uri.toString(),
                    outputSizeBytes = verified.sizeBytes,
                    outputDurationMs = verified.durationMs,
                    previewAvailable = true,
                    lastSuccessfulStage = "preview"
                )
                compilationJobStore.save(recovered)
                currentPipelineState = recoveredState
                restoreSuccessfulRecord(recovered)
                return@launch
            }
            val reason = "WorkManager no longer has job ${record.workId}; persisted metadata was retained and no verified output exists"
            compilationJobStore.save(
                record.copy(
                    state = CompilationPipelineState.FAILED,
                    stage = "work lookup",
                    progressMessage = reason,
                    completedAtMs = System.currentTimeMillis(),
                    errorStage = "work lookup",
                    errorType = "MissingWorkInfo",
                    errorMessage = reason,
                    previewAvailable = false,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            currentPipelineState = CompilationPipelineState.FAILED
            emitCompilationProgress("Compilation state unavailable: $reason", 100)
            setUiBusy(false)
            isBusy = false
        }
    }

    private fun exportFormatFromOrdinal(ordinal: Int): ExportFormat = when (ordinal) {
        ExportFormat.Webm.ordinal -> ExportFormat.Webm
        ExportFormat.Mov.ordinal -> ExportFormat.Mov
        else -> ExportFormat.Mp4
    }

    private fun setupVideoPreview(uri: Uri) {
        if (!canInitializeRoiUi()) {
            emitRoiStatus("ROI preview is paused while compilation state is ${currentPipelineState.name}")
            return
        }
        frameContainer.visibility = View.VISIBLE
        roiOverlay.visibility = View.GONE
        previewBitmap?.recycle()
        previewBitmap = null
        frameImage.setImageDrawable(null)
        previewSeekBar.isEnabled = false
        captureFrameButton.isEnabled = false
        previewSeekBar.progress = 0
        selectedPreviewMs = 0
        stopPreviewProgressUpdates()
        videoPreview.setVideoURI(uri)
        videoPreview.requestFocus()
        videoPreview.start()
        videoPreview.pause()
    }

    private fun captureFrame(positionMs: Int) {
        if (!canInitializeRoiUi()) {
            emitRoiStatus("ROI frame capture skipped while compilation state is ${currentPipelineState.name}")
            return
        }
        val source = selectedVideoUri ?: return
        val duration = max(1, previewSeekBar.max)
        val safePosition = positionMs.coerceIn(0, duration)
        lifecycleScope.launch {
            if (!canInitializeRoiUi()) return@launch
            persistDraftState(CompilationPipelineState.ROI_SELECTION, "Capturing ROI frame")
            emitRoiStatus("Capturing frame for ROI at ${safePosition}ms")
            val frame = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                val targetWidth = max(640, frameImage.width).coerceAtMost(1280)
                val targetHeight = max(360, frameImage.height).coerceAtMost(720)
                try {
                    retriever.setDataSource(this@MainActivity, source)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            safePosition * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetWidth,
                            targetHeight
                        ) ?: retriever.getFrameAtTime(
                            safePosition * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } else {
                        retriever.getFrameAtTime(
                            safePosition * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                } finally {
                    retriever.release()
                }
            }
            if (!canInitializeRoiUi()) {
                frame?.recycle()
                AppLog.i(this@MainActivity, logTag, "ROI frame result discarded because compilation became active")
                return@launch
            }
            if (frame == null) {
                emitRoiStatus("Unable to capture frame for ROI")
                return@launch
            }
            val normalizedFrame = normalizeBitmapForRoi(frame, selectedVideoRotationDegrees)
            if (normalizedFrame !== frame) {
                frame.recycle()
            }
            previewBitmap?.recycle()
            previewBitmap = normalizedFrame
            frameImage.setImageBitmap(normalizedFrame)
            selectedPreviewMs = safePosition
            roiOverlay.visibility = View.VISIBLE
            updateRoiOverlay()
            persistDraftState(CompilationPipelineState.READY, "ROI frame captured")
            emitRoiStatus("Captured frame. Tap within frame to place ROI center.")
        }
    }

    private fun setScanAreaFromPercents(xPercent: Float, yPercent: Float, widthPercent: Float, heightPercent: Float) {
        isUpdatingRoiFields = true
        val cx = xPercent.coerceIn(0f, 1f)
        val cy = yPercent.coerceIn(0f, 1f)
        val cw = widthPercent.coerceIn(0.01f, 1f)
        val ch = heightPercent.coerceIn(0.01f, 1f)
        try {
            scanAreaX.setText(String.format(Locale.US, "%.2f", cx * 100f))
            scanAreaY.setText(String.format(Locale.US, "%.2f", cy * 100f))
            scanAreaWidth.setText(String.format(Locale.US, "%.2f", cw * 100f))
            scanAreaHeight.setText(String.format(Locale.US, "%.2f", ch * 100f))
        } finally {
            isUpdatingRoiFields = false
        }
        addStatusLine(
            "ROI set to X:${String.format(Locale.US, "%.1f", xPercent * 100f)}% Y:${String.format(Locale.US, "%.1f", yPercent * 100f)}% "
                + "W:${String.format(Locale.US, "%.1f", widthPercent * 100f)}% H:${String.format(Locale.US, "%.1f", heightPercent * 100f)}%"
        )
        persistCurrentRoi()
    }

    private fun wireScanAreaInputs() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingRoiFields || previewBitmap == null) return
                updateRoiOverlay()
            }
        }
        scanAreaX.addTextChangedListener(watcher)
        scanAreaY.addTextChangedListener(watcher)
        scanAreaWidth.addTextChangedListener(watcher)
        scanAreaHeight.addTextChangedListener(watcher)
    }

    private fun updateRoiOverlay() {
        val window = readScanWindow()
        updateRoiOverlay(window)
    }

    private fun updateRoiOverlay(window: ScanWindow) {
        frameImage.post {
            val width = frameImage.width.toFloat()
            val height = frameImage.height.toFloat()
            if (width <= 0f || height <= 0f) return@post

            val boxW = max(1f, width * window.widthPercent)
            val boxH = max(1f, height * window.heightPercent)
            val left = max(0f, min(width - 1f, width * window.xPercent))
            val top = max(0f, min(height - 1f, height * window.yPercent))
            val params = roiOverlay.layoutParams as FrameLayout.LayoutParams
            params.width = boxW.toInt().coerceAtLeast(1)
            params.height = boxH.toInt().coerceAtLeast(1)
            params.leftMargin = left.toInt().coerceAtLeast(0)
            params.topMargin = top.toInt().coerceAtLeast(0)
            roiOverlay.layoutParams = params
            roiOverlay.visibility = if (previewBitmap == null) View.GONE else View.VISIBLE
        }
    }

    private val previewProgressUpdater = object : Runnable {
        override fun run() {
            if (videoPreview.isPlaying && previewSeekBar.isEnabled && !isScrubbing) {
                val current = videoPreview.currentPosition
                if (current >= 0 && current <= previewSeekBar.max) {
                    selectedPreviewMs = current
                    if (!previewSeekBar.isPressed) {
                        previewSeekBar.progress = current
                    }
                }
            }
            previewHandler.postDelayed(this, 250L)
        }
    }

    private fun startPreviewProgressUpdates() {
        stopPreviewProgressUpdates()
        previewHandler.postDelayed(previewProgressUpdater, 250L)
    }

    private fun stopPreviewProgressUpdates() {
        previewHandler.removeCallbacks(previewProgressUpdater)
    }

    private val compilationPreviewProgressUpdater = object : Runnable {
        override fun run() {
            if (compilationPreview.isPlaying && compilationPreviewSeekBar.isEnabled && !isCompilationPreviewScrubbing) {
                val current = compilationPreview.currentPosition
                if (current >= 0 && current <= compilationPreviewSeekBar.max) {
                    selectedCompilationPreviewMs = current
                    if (!compilationPreviewSeekBar.isPressed) {
                        compilationPreviewSeekBar.progress = current
                    }
                }
            }
            previewHandler.postDelayed(this, 250L)
        }
    }

    private fun startCompilationPreviewProgressUpdates() {
        stopCompilationPreviewProgressUpdates()
        previewHandler.postDelayed(compilationPreviewProgressUpdater, 250L)
    }

    private fun stopCompilationPreviewProgressUpdates() {
        previewHandler.removeCallbacks(compilationPreviewProgressUpdater)
    }

    private fun showPendingCompilationPreview(
        file: File,
        format: ExportFormat,
        uri: Uri = compilationContentUri(file)
    ) {
        pendingCompilationFile = file
        pendingCompilationFormat = format
        pendingCompilationUri = uri
        binding.compilationPreviewContainer.visibility = View.VISIBLE
        compilationPreviewSeekBar.isEnabled = false
        compilationPreviewSeekBar.progress = 0
        selectedCompilationPreviewMs = 0
        playCompilationPreviewButton.text = "Play"
        playCompilationPreviewButton.isEnabled = true
        saveCompilationButton.isEnabled = true
        discardCompilationButton.isEnabled = true
        shareCompilationButton.isEnabled = true
        openCompilationButton.isEnabled = true
        compilationPreview.setVideoURI(uri)
        compilationPreview.seekTo(0)
        compilationPreview.pause()
    }

    private fun clearPendingCompilationPreview(deleteFile: Boolean) {
        stopCompilationPreviewProgressUpdates()
        runCatching { compilationPreview.stopPlayback() }
        if (deleteFile) {
            pendingCompilationFile?.delete()
        }
        pendingCompilationFile = null
        pendingCompilationFormat = null
        pendingCompilationUri = null
        selectedCompilationPreviewMs = 0
        compilationPreviewSeekBar.progress = 0
        compilationPreviewSeekBar.isEnabled = false
        playCompilationPreviewButton.text = "Play"
        shareCompilationButton.isEnabled = false
        openCompilationButton.isEnabled = false
        binding.compilationPreviewContainer.visibility = View.GONE
    }

    private fun sharePendingCompilation() {
        val uri = pendingCompilationUri
        val format = pendingCompilationFormat
        if (uri == null || format == null) {
            emitTransientStatus("No verified compilation is available to share.")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "Compilation", uri)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share compilation")) }
            .onFailure {
                AppLog.w(this@MainActivity, logTag, "Could not share compilation", it)
                emitTransientStatus("No app could share the compilation.")
            }
    }

    private fun openPendingCompilation() {
        val uri = pendingCompilationUri
        val format = pendingCompilationFormat
        if (uri == null || format == null) {
            emitTransientStatus("No verified compilation is available to open.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, format.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "Compilation", uri)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                AppLog.w(this@MainActivity, logTag, "Could not open compilation", it)
                emitTransientStatus("No video app could open the compilation.")
            }
    }

    private fun savePendingCompilation() {
        val output = pendingCompilationFile
        val format = pendingCompilationFormat
        if (output == null || format == null) {
            emitTransientStatus("No compilation preview is ready to save.")
            return
        }

        lifecycleScope.launch {
            isBusy = true
            setUiBusy(true)
            try {
                var savedDestination = ""
                val saveTiming = measureTimeMillis {
                    savedDestination = saveToPhoneStorage(output, format)
                }
                AppLog.i(this@MainActivity, logTag, "Saved compilation to $savedDestination in ${saveTiming}ms")
                emitTransientStatus("Compilation saved to Movies/CompilationMaker")
                Toast.makeText(this@MainActivity, "Export saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AppLog.e(this@MainActivity, logTag, "Save failed", e)
                emitTransientStatus("Save failed: ${e.message ?: "failed"}")
            } finally {
                isBusy = false
                setUiBusy(false)
            }
        }
    }

    private suspend fun saveToPhoneStorage(file: File, format: ExportFormat): String = withContext(Dispatchers.IO) {
        val filename = "compilation_${System.currentTimeMillis()}"
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$filename.${safeFormat.extension}")
            put(MediaStore.Video.Media.MIME_TYPE, safeFormat.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CompilationMaker")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver: ContentResolver = contentResolver
        fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream) {
            val buffer = ByteArray(1024 * 1024)
            var bytes = 0L
            var read: Int
            val sourceLength = file.length().coerceAtLeast(1L)
            var lastReportMs = 0L
            while (true) {
                read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                bytes += read
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportMs >= 300L) {
                    val savedPercent = ((bytes * 100L) / sourceLength).coerceIn(0L, 100L).toInt()
                    emitTransientStatus("Saving output: ${formatBytes(bytes)} / ${formatBytes(sourceLength)} ($savedPercent%)")
                    lastReportMs = now
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri, "w").use { out ->
                        file.inputStream().use { input ->
                            copyWithProgress(input, out ?: throw IllegalStateException("No output stream"))
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return@withContext uri.toString()
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }
        }

        val fallbackDir = Environment.getExternalStoragePublicDirectory("${Environment.DIRECTORY_MOVIES}/CompilationMaker")
        fallbackDir.mkdirs()
        val outFile = File(fallbackDir, "$filename.${safeFormat.extension}")
        runCatching {
            file.inputStream().use { input ->
                outFile.outputStream().use { out ->
                    copyWithProgress(input, out)
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(outFile.absolutePath), arrayOf(safeFormat.mimeType)) { p, uri ->
                    AppLog.d(this@MainActivity, logTag, "Scanned output: $p -> $uri")
                }
            }
            return@withContext outFile.absolutePath
        }

        val privateBase = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("No app output directory available")
        val privateDir = File(privateBase, "CompilationMaker").apply { mkdirs() }
        val privateOut = File(privateDir, "$filename.${safeFormat.extension}")
        privateOut.outputStream().use { out ->
            file.inputStream().use { input ->
                copyWithProgress(input, out)
            }
        }
        return@withContext privateOut.absolutePath
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPictureInPictureParams(activeJob: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setAutoEnterEnabled(activeJob)
                .setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    private fun startCompilationBatch(
        uris: List<Uri>,
        quality: ExportQuality,
        format: ExportFormat,
        transitionStyle: TransitionStyle
    ) {
        lifecycleScope.launch {
            try {
                val profile = selectedCheckpointProfile()
                val requestedScanMode = if (experimentalModeSwitch.isChecked) ScanMode.Experimental else profile.mode
                val scanWindowJson = JSONObject().apply {
                    put("xPercent", selectedScanWindow.xPercent)
                    put("yPercent", selectedScanWindow.yPercent)
                    put("widthPercent", selectedScanWindow.widthPercent)
                    put("heightPercent", selectedScanWindow.heightPercent)
                }.toString()
                val batchId = "batch-${System.currentTimeMillis()}"
                val itemJson = JSONArray().apply {
                    uris.forEach { uri -> put(JSONObject().apply { put("uri", uri.toString()); put("label", displayName(uri)) }) }
                }.toString()
                val input = Data.Builder()
                    .putString(BatchCompilationWorker.KEY_BATCH_ID, batchId)
                    .putString(BatchCompilationWorker.KEY_ITEMS_JSON, itemJson)
                    .putString(CompilationWorker.KEY_SCAN_WINDOW, scanWindowJson)
                    .putInt(CompilationWorker.KEY_SCAN_MODE, requestedScanMode.ordinal)
                    .putLong(CompilationWorker.KEY_CHECKPOINT_INTERVAL_MS, profile.frameStepMs)
                    .putString(CompilationWorker.KEY_SCANNER_PROFILE_ID, profile.scannerProfileId)
                    .putInt(CompilationWorker.KEY_EXPERIMENTAL_DOWNSCALE, selectedExperimentalDownscaleSize())
                    .putInt(CompilationWorker.KEY_QUALITY_ORDINAL, quality.ordinal)
                    .putInt(CompilationWorker.KEY_FORMAT_ORDINAL, format.ordinal)
                    .putInt(CompilationWorker.KEY_TRANSITION_STYLE_ORDINAL, transitionStyle.ordinal)
                    .putInt(CompilationWorker.KEY_VIDEO_ROTATION, selectedVideoRotationDegrees)
                    .build()
                clearPendingCompilationPreview(deleteFile = true)
                clearStatusFeed("Batch queued")
                emitCompilationProgress("Queued ${uris.size} videos; processing one at a time", 0)
                val request = OneTimeWorkRequestBuilder<BatchCompilationWorker>().setInputData(input).build()
                val now = System.currentTimeMillis()
                compilationBatchStore.save(CompilationBatchRecord(
                    batchId = batchId,
                    workId = request.id.toString(),
                    startedAtMs = now,
                    updatedAtMs = now,
                    items = uris.map { CompilationBatchItem(it.toString(), displayName(it)) }
                ))
                withContext(Dispatchers.IO) { workManager.enqueueUniqueWork("compilation-batch", ExistingWorkPolicy.REPLACE, request).result.get() }
                batchWorkId = request.id
                observeBatchWork(request.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordHandledWorkerFailure(this@MainActivity, logTag, "Batch enqueue failed", failure)
                emitCompilationProgress("Unable to queue batch: ${failure.message ?: failure::class.java.simpleName}", 100)
                setUiBusy(false)
                isBusy = false
            } finally {
                compilationStartInFlight = false
            }
        }
    }

    private fun observeBatchWork(workId: UUID) {
        activeBatchWorkObserver?.let { activeBatchWorkInfoLiveData?.removeObserver(it) }
        activeBatchWorkObserver = Observer { workInfo ->
            if (workInfo == null) return@Observer
            val progress = workInfo.progress
            val percent = progress.getInt(BatchCompilationWorker.KEY_PERCENT, 0)
            val elapsed = progress.getLong(BatchCompilationWorker.KEY_ELAPSED_MS, 0L)
            val status = progress.getString(BatchCompilationWorker.KEY_STATUS) ?: "Batch processing"
            when (workInfo.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    latestWorkManagerState = workInfo.state
                    isBusy = true
                    setUiBusy(true)
                    emitCompilationProgress("$status • elapsed ${formatElapsedForUi(elapsed)}", percent)
                }
                WorkInfo.State.SUCCEEDED -> {
                    latestWorkManagerState = workInfo.state
                    val record = compilationBatchStore.load()
                    emitCompilationProgress("Batch complete • total elapsed ${formatElapsedForUi(workInfo.outputData.getLong(BatchCompilationWorker.KEY_ELAPSED_MS, elapsed))}", 100)
                    binding.batchQueueText.text = record?.items?.mapIndexed { index, item ->
                        "${index + 1}. ${item.label} — ${item.state} (${formatElapsedForUi(item.elapsedMs)})"
                    }?.joinToString("\n") ?: binding.batchQueueText.text
                    isBusy = false
                    setUiBusy(false)
                    dismissBatchWorkObserver()
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    latestWorkManagerState = workInfo.state
                    val message = workInfo.outputData.getString(BatchCompilationWorker.KEY_ERROR) ?: "Batch stopped"
                    emitCompilationProgress("$message • elapsed ${formatElapsedForUi(elapsed)}", 100)
                    isBusy = false
                    setUiBusy(false)
                    dismissBatchWorkObserver()
                }
            }
        }
        activeBatchWorkInfoLiveData = workManager.getWorkInfoByIdLiveData(workId)
        activeBatchWorkInfoLiveData?.observe(this, activeBatchWorkObserver!!)
    }

    private fun dismissBatchWorkObserver() {
        activeBatchWorkObserver?.let { activeBatchWorkInfoLiveData?.removeObserver(it) }
        activeBatchWorkObserver = null
        activeBatchWorkInfoLiveData = null
        batchWorkId = null
    }

    private fun formatElapsedForUi(ms: Long): String = "%02d:%02d".format(ms / 60_000L, (ms / 1_000L) % 60L)

    private fun updatePictureInPictureParams(activeJob: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams(activeJob)) }
            .onFailure { AppLog.w(this, logTag, "Could not update picture-in-picture parameters", it) }
    }

    private fun renderPictureInPictureMode(inPictureInPicture: Boolean) {
        if (!::mainContent.isInitialized || !::pipStatusContainer.isInitialized) return
        mainContent.visibility = if (inPictureInPicture) View.GONE else View.VISIBLE
        pipStatusContainer.visibility = if (inPictureInPicture) View.VISIBLE else View.GONE
    }

    private fun queueCoreActivity(activity: CompilationCoreActivity) {
        if (backgroundStatusMode != BackgroundStatusMode.CORE_ACTIVITY) return
        val observedWorkId = compilationWorkId?.toString() ?: return
        if (activity.workId != observedWorkId) return
        synchronized(coreActivityLock) {
            pendingCoreActivity = activity
            if (coreActivityRenderScheduled) return
            coreActivityRenderScheduled = true
        }
        backgroundStatusHandler.postDelayed(coreActivityRenderer, CORE_STATUS_SAMPLE_INTERVAL_MS)
    }

    private fun renderPendingCoreActivity() {
        val activity = synchronized(coreActivityLock) {
            val latest = pendingCoreActivity
            pendingCoreActivity = null
            coreActivityRenderScheduled = false
            latest
        } ?: return
        if (backgroundStatusMode != BackgroundStatusMode.CORE_ACTIVITY) return

        val event = activity.event
        val timestampMs = event.actualFramePtsMs ?: event.requestedTimeMs
        val timestamp = timestampMs?.let(::formatTransitionTimestampMs)?.let { " @ $it" }.orEmpty()
        val phase = event.stage.name.lowercase(Locale.US).replace('_', ' ')
        val message = "${event.action}$timestamp | ${event.detail}"
        val snapshot = BackgroundStatusSnapshot(
            phase = phase,
            pipelineState = currentPipelineState,
            workManagerState = latestWorkManagerState?.name ?: "RUNNING",
            message = message,
            percent = lastProgressPercent.coerceAtLeast(0),
            observedAtMs = SystemClock.elapsedRealtime()
        )
        renderBackgroundStatus(snapshot, forceAppend = true)
    }

    private fun renderBackgroundStatus(snapshot: BackgroundStatusSnapshot, forceAppend: Boolean = false) {
        val coreStyle = backgroundStatusMode == BackgroundStatusMode.CORE_ACTIVITY
        pipPhaseText.text = snapshot.phase.ifBlank { snapshot.pipelineState.name }.replace('_', ' ')
        pipPercentText.text = "${snapshot.safePercent}%"
        pipProgressBar.progress = snapshot.safePercent
        pipStatusText.text = snapshot.message
        pipSignalText.text = "Foreground worker ${snapshot.workManagerState} | signal ${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}"
        backgroundStatusBanner.text = "Background processing: ${snapshot.safePercent}% • ${snapshot.message}"

        val append = forceAppend || shouldAppendBackgroundStatus(
            mode = backgroundStatusMode,
            previous = previousBackgroundStatus,
            current = snapshot,
            lastFeedAtMs = lastBackgroundFeedAtMs
        )
        if (append) {
            addStatusLine(formatBackgroundStatus(snapshot, coreStyle))
            lastBackgroundFeedAtMs = snapshot.observedAtMs
        }
        previousBackgroundStatus = snapshot
        renderPipLog()
    }

    private fun renderPipLog() {
        val lineCount = if (backgroundStatusMode == BackgroundStatusMode.CORE_ACTIVITY) 3 else 1
        pipLogText.text = statusFeedLines.toList().takeLast(lineCount).joinToString("\n")
            .ifBlank { "Waiting for worker signal" }
    }

    private fun setUiBusy(busy: Boolean) {
        runOnUiThread {
            binding.processButton.isEnabled = !busy
            binding.selectButton.isEnabled = !busy
            checkUpdatesButton.isEnabled = true
            qualitySpinner.isEnabled = !busy
            formatSpinner.isEnabled = !busy
            scanSpeedSpinner.isEnabled = !busy
            transitionStyleSpinner.isEnabled = !busy
            experimentalModeSwitch.isEnabled = !busy
            experimentalDownscaleSpinner.isEnabled = !busy && experimentalModeSwitch.isChecked
            saveCompilationButton.isEnabled = !busy && pendingCompilationFile != null
            discardCompilationButton.isEnabled = !busy && pendingCompilationFile != null
            playCompilationPreviewButton.isEnabled = !busy && pendingCompilationFile != null
            shareCompilationButton.isEnabled = !busy && pendingCompilationUri != null
            openCompilationButton.isEnabled = !busy && pendingCompilationUri != null
            val roiEnabled = !busy && canInitializeRoiUi()
            captureFrameButton.isEnabled = roiEnabled && selectedVideoUri != null
            refreshRoiButton.isEnabled = roiEnabled
            previewSeekBar.isEnabled = roiEnabled && previewSeekBar.max > 0
            scanAreaX.isEnabled = roiEnabled
            scanAreaY.isEnabled = roiEnabled
            scanAreaWidth.isEnabled = roiEnabled
            scanAreaHeight.isEnabled = roiEnabled
            binding.progressBar.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.INVISIBLE
            progressPercentText.visibility = View.VISIBLE
            statusFeedScroll.visibility = View.VISIBLE
            backgroundStatusBanner.visibility = if (busy) View.VISIBLE else View.GONE
            updatePictureInPictureParams(activeJob = busy)
        }
    }

    private fun displayTransitionSummaries(clipPlanJson: String, emptyMessage: String) {
        val summaries = transitionSummariesFromClipPlan(clipPlanJson)
        transitionResultsText.text = if (summaries.isEmpty()) {
            emptyMessage
        } else {
            summaries.joinToString(separator = "\n")
        }
    }

    private fun renderTransitionResults(record: CompilationJobRecord?) {
        val emptyMessage = when {
            record?.state?.isActive == true -> getString(R.string.transition_results_scanning)
            record?.state == CompilationPipelineState.SUCCEEDED ||
                record?.state == CompilationPipelineState.PROVISIONAL_SUCCEEDED ->
                getString(R.string.transition_results_none_confirmed)
            else -> getString(R.string.transition_results_empty)
        }
        displayTransitionSummaries(record?.clipPlanJson.orEmpty(), emptyMessage)
    }

    private fun emitCompilationProgress(
        message: String,
        percent: Int,
        phase: String? = null,
        pipelineState: CompilationPipelineState = currentPipelineState
    ) {
        val percentValue = percent.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        if (percentValue == lastProgressPercent && message == lastProgressText && now - lastProgressUpdateMs < 250L) return
        lastProgressText = message
        lastProgressPercent = percentValue
        lastProgressUpdateMs = now
        AppLog.i(this, logTag, "Progress $percentValue%: $message")
        runOnUiThread {
            val visibleMessage = phase?.let { "$it: $message" } ?: message
            binding.statusText.text = visibleMessage
            progressPercentText.text = "$percentValue%"
            binding.progressBar.progress = percentValue
            backgroundStatusBanner.visibility = View.VISIBLE
            renderBackgroundStatus(
                BackgroundStatusSnapshot(
                    phase = phase ?: pipelineState.name.lowercase(Locale.US),
                    pipelineState = pipelineState,
                    workManagerState = latestWorkManagerState?.name ?: if (pipelineState.isActive) "RUNNING" else "IDLE",
                    message = message,
                    percent = percentValue,
                    observedAtMs = now
                )
            )
        }
        updateProgressNotification(message, percentValue, percentValue < 100)
    }

    private fun emitRoiStatus(message: String) {
        emitUiChannelStatus(CompilationUiChannel.ROI, message)
    }

    private fun emitUpdateStatus(message: String) {
        emitUiChannelStatus(CompilationUiChannel.UPDATE, message)
    }

    private fun emitLogStatus(message: String) {
        emitUiChannelStatus(CompilationUiChannel.LOG, message)
    }

    private fun emitTransientStatus(message: String) {
        emitUiChannelStatus(CompilationUiChannel.TRANSIENT, message)
    }

    private fun emitUiChannelStatus(channel: CompilationUiChannel, message: String) {
        check(!shouldWriteCompilationProgress(channel)) { "Compilation status must use emitCompilationProgress" }
        AppLog.i(this, logTag, "${channel.name.lowercase()}: $message")
        runOnUiThread {
            when (channel) {
                CompilationUiChannel.ROI -> binding.roiStatusText.text = message
                CompilationUiChannel.UPDATE -> binding.updateStatusText.text = message
                CompilationUiChannel.LOG -> addStatusLine("Log: $message")
                CompilationUiChannel.TRANSIENT -> addStatusLine(message)
                CompilationUiChannel.COMPILATION -> Unit
            }
        }
    }

    private fun ensureProgressNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationChannelReady) return
        val channel = NotificationChannel(
            progressNotificationChannelId,
            "Compilation progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows export progress while compilations are running"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        notificationChannelReady = true
    }

    private fun updateProgressNotification(message: String, percent: Int, ongoing: Boolean) {
        if (compilationWorkId != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val shouldNotify = !ongoing || percent != lastNotificationProgressPercent || now - lastNotificationUpdateMs >= 750L
        if (!shouldNotify) return
        lastNotificationProgressPercent = percent
        lastNotificationUpdateMs = now

        ensureProgressNotificationChannel()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, progressNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                when {
                    message.startsWith("Error:", ignoreCase = true) -> "Compilation failed"
                    ongoing -> "Compilation in progress"
                    else -> "Compilation complete"
                }
            )
            .setContentText(message.take(90))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(progressNotificationId, notification)
        } catch (e: SecurityException) {
            AppLog.w(this@MainActivity, logTag, "Notification permission unavailable", e)
        }
    }

    private fun clearProgressNotification() {
        NotificationManagerCompat.from(this).cancel(progressNotificationId)
    }

    private fun formatDurationMs(totalMs: Long): String {
        val totalSec = max(0L, totalMs) / 1000
        val sec = totalSec % 60
        val min = totalSec / 60
        val ms = max(0L, totalMs) % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, ms)
    }

    private fun formatDurationUs(totalUs: Long): String {
        return formatDurationMs(totalUs / 1000L)
    }

    private fun formatBytes(totalBytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = totalBytes.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (idx == 0) {
            String.format(Locale.US, "%d%s", totalBytes, units[idx])
        } else {
            String.format(Locale.US, "%.2f%s", value, units[idx])
        }
    }

    private fun clearStatusFeed(initialMessage: String) {
        statusFeedLines.clear()
        previousBackgroundStatus = null
        lastBackgroundFeedAtMs = 0L
        addStatusLine(initialMessage)
        binding.statusText.text = initialMessage
        progressPercentText.text = "0%"
        binding.progressBar.progress = 0
    }

    private fun showCrashLogDialog() {
        val savedLog = readSavedLog(this)
        val message = savedLog?.text ?: "No saved log captured yet."
        val title = savedLog?.title ?: "Saved log"
        val logView = TextView(this).apply {
            setTextIsSelectable(true)
            setText(message)
            setPadding(32, 24, 32, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
        }
        val scrollView = ScrollView(this).apply {
            addView(logView)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CompilationMaker log", message))
                emitLogStatus("Log copied to clipboard.")
            }
            .setNeutralButton("Clear log") { _, _ ->
                clearCrashReport(this)
                emitLogStatus("Saved log cleared.")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun selectedScanProfile(): ScanProfile {
        return selectedCheckpointProfile()
    }

    private fun readScanWindow(): ScanWindow {
        val rawX = parsePercent(scanAreaX.text?.toString(), defaultScanWindow.xPercent)
        val rawY = parsePercent(scanAreaY.text?.toString(), defaultScanWindow.yPercent)
        val rawW = parsePercent(scanAreaWidth.text?.toString(), defaultScanWindow.widthPercent)
        val rawH = parsePercent(scanAreaHeight.text?.toString(), defaultScanWindow.heightPercent)

        val x = rawX.coerceIn(0f, 1f)
        val y = rawY.coerceIn(0f, 1f)
        val maxWidth = max(0.01f, 1f - x)
        val maxHeight = max(0.01f, 1f - y)
        val widthPercent = rawW.coerceIn(0.01f, maxWidth)
        val heightPercent = rawH.coerceIn(0.01f, maxHeight)

        return ScanWindow(x, y, widthPercent, heightPercent)
    }

    private fun parsePercent(raw: String?, fallback: Float): Float {
        if (raw.isNullOrBlank()) return fallback
        val value = raw.trim().toFloatOrNull() ?: return fallback
        return if (value > 1f) value / 100f else value
    }

    private fun addStatusLine(message: String) {
        val ts = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        statusFeedLines.addLast("[$ts] $message")
        while (statusFeedLines.size > 12) {
            statusFeedLines.removeFirst()
        }
        statusFeedText.text = statusFeedLines.joinToString("\n")
        statusFeedScroll.post { statusFeedScroll.fullScroll(View.FOCUS_DOWN) }
        if (::pipLogText.isInitialized) renderPipLog()
    }

    private fun roiStateKey(uri: Uri): String {
        return "roi:${Base64.encodeToString(uri.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)}"
    }

    private fun persistCurrentRoi() {
        val uri = selectedVideoUri ?: return
        val window = readScanWindow()
        roiPrefs.edit().putString(
            roiStateKey(uri),
            JSONObject().apply {
                put("uri", uri.toString())
                put("rotation", selectedVideoRotationDegrees)
                put("videoWidth", selectedVideoWidth)
                put("videoHeight", selectedVideoHeight)
                put("xPercent", window.xPercent)
                put("yPercent", window.yPercent)
                put("widthPercent", window.widthPercent)
                put("heightPercent", window.heightPercent)
                put("updatedAtMs", System.currentTimeMillis())
            }.toString()
        ).apply()
    }

    private fun restoreRoiState(uri: Uri) {
        val payload = roiPrefs.getString(roiStateKey(uri), null) ?: return
        try {
            val json = JSONObject(payload)
            val savedWindow = ScanWindow(
                (json.getDouble("xPercent")).toFloat(),
                (json.getDouble("yPercent")).toFloat(),
                (json.getDouble("widthPercent")).toFloat(),
                (json.getDouble("heightPercent")).toFloat()
            )
            val savedRotation = json.optInt("rotation")
            val restoredWindow = normalizeRoiWindowForCurrentRotation(savedWindow, savedRotation)
            setScanAreaFromPercents(
                restoredWindow.xPercent,
                restoredWindow.yPercent,
                restoredWindow.widthPercent,
                restoredWindow.heightPercent
            )
            emitRoiStatus(
                "Loaded saved ROI for video (storedRotation=${savedRotation}°, currentRotation=${selectedVideoRotationDegrees}°)",
            )
        } catch (e: Exception) {
            AppLog.w(this@MainActivity, logTag, "Failed to restore ROI metadata", e)
            emitRoiStatus("Saved ROI metadata was unavailable. Using defaults.")
        }
    }

    private fun normalizeRoiWindowForCurrentRotation(saved: ScanWindow, savedRotation: Int): ScanWindow {
        return rotateScanWindowForCurrentRotation(saved, savedRotation, selectedVideoRotationDegrees)
    }

    private fun ScanWindow.coerceInBounds(): ScanWindow {
        val x = xPercent.coerceIn(0f, 1f)
        val y = yPercent.coerceIn(0f, 1f)
        val w = widthPercent.coerceIn(0.01f, max(0.01f, 1f - x))
        val h = heightPercent.coerceIn(0.01f, max(0.01f, 1f - y))
        return ScanWindow(x, y, w, h)
    }

    private fun loadSelectedVideoMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            selectedVideoRotationDegrees = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0).let {
                ((it % 360) + 360) % 360
            }
            selectedVideoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            selectedVideoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            AppLog.w(this@MainActivity, logTag, "Could not read video metadata", e)
            selectedVideoRotationDegrees = 0
            selectedVideoWidth = 0
            selectedVideoHeight = 0
        } finally {
            retriever.release()
        }
    }

    private fun normalizeBitmapForRoi(source: Bitmap, rotationDegrees: Int): Bitmap {
        return when (((rotationDegrees % 360) + 360) % 360) {
            0 -> source
            else -> {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
        }
    }

    private fun checkForUpdates(force: Boolean = false) {
        lifecycleScope.launch {
            performUpdateCheck(force)
        }
    }

    private fun startForegroundUpdateChecks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    performUpdateCheck(force = false)
                    delay(UpdateScheduler.FOREGROUND_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun performUpdateCheck(force: Boolean) {
        if (force) emitUpdateStatus("Checking the verified update feed...")
        when (val check = updateRepository.checkForUpdate()) {
            is UpdateCheckResult.UpToDate -> if (force) {
                emitUpdateStatus("CompilationMaker is up to date.")
            }
            is UpdateCheckResult.Failed -> if (force) {
                emitUpdateStatus(check.message)
            }
            is UpdateCheckResult.Available -> handleAvailableUpdate(check.info, force)
        }
    }

    private suspend fun handleAvailableUpdate(info: UpdateInfo, force: Boolean) {
        emitUpdateStatus("Verified update ${info.versionName} is available.")
        val activeCompilation = compilationStartInFlight || hasActiveCompilation()
        if (!updateRepository.autoDownloadEnabled) {
            notifyUpdateOnce(info, readyToInstall = false)
            if (force) showUpdateAvailableDialog(info)
            return
        }

        if (!shouldAutomaticallyDownloadUpdate(updateRepository.autoDownloadEnabled, activeCompilation)) {
            emitUpdateStatus("Update ${info.versionName} is available. Automatic download will wait for the active compilation.")
            notifyUpdateOnce(info, readyToInstall = false)
            return
        }

        emitUpdateStatus("Downloading and verifying update ${info.versionName}...")
        when (val download = updateRepository.downloadAndVerify(info)) {
            is UpdateDownloadResult.Failed -> {
                emitUpdateStatus(download.message)
                notifyUpdateOnce(info, readyToInstall = false)
            }
            is UpdateDownloadResult.Ready -> {
                emitUpdateStatus("Update ${info.versionName} is verified. Android approval is required to install.")
                notifyUpdateOnce(info, readyToInstall = true)
                if (force) showUpdateReadyDialog(info)
            }
        }
    }

    private fun notifyUpdateOnce(info: UpdateInfo, readyToInstall: Boolean) {
        if (!updateRepository.shouldNotify(info, readyToInstall)) return
        if (UpdateNotifier.notify(this, info, readyToInstall)) {
            updateRepository.markNotified(info, readyToInstall)
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateInfo) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Update ${info.versionName} available")
                .setMessage(
                    if (info.releaseNotes.isNotBlank()) {
                        "${info.releaseNotes}\n\nThe APK will be verified before Android asks you to approve installation."
                    } else {
                        "The APK will be verified before Android asks you to approve installation."
                    }
                )
                .setPositiveButton("Download") { _, _ ->
                    downloadAndInstallUpdate(info)
                }
                .setNegativeButton("Later", null)
                .show()
            emitUpdateStatus("Update ${info.versionName} available.")
        }
    }

    private fun showUpdateReadyDialog(info: UpdateInfo) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Update ${info.versionName} ready")
                .setMessage("The APK passed its hash, package, version, and signing-certificate checks. Android must approve installation.")
                .setPositiveButton("Continue") { _, _ -> installVerifiedUpdate(info.versionCode) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun downloadAndInstallUpdate(info: UpdateInfo) {
        lifecycleScope.launch {
            emitUpdateStatus("Downloading and verifying update ${info.versionName}...")
            when (val download = updateRepository.downloadAndVerify(info)) {
                is UpdateDownloadResult.Failed -> emitUpdateStatus(download.message)
                is UpdateDownloadResult.Ready -> {
                    emitUpdateStatus("Update verified. Opening Android's installer for approval.")
                    installVerifiedUpdate(info.versionCode)
                }
            }
        }
    }

    private fun installVerifiedUpdate(versionCode: Long) {
        lifecycleScope.launch {
            if (compilationStartInFlight || hasActiveCompilation()) {
                emitUpdateStatus("Finish or cancel the active compilation before installing an update.")
                return@launch
            }
            val apk = updateRepository.verifiedDownloadedFile(versionCode)
            if (apk == null) {
                emitUpdateStatus("Verified update file is missing or no longer valid. Check for updates again.")
                return@launch
            }
            installDownloadedApk(apk)
        }
    }

    private fun handleUpdateIntent(sourceIntent: Intent?) {
        sourceIntent ?: return
        val action = sourceIntent.getStringExtra(UpdateContract.EXTRA_ACTION) ?: return
        val versionCode = sourceIntent.getLongExtra(UpdateContract.EXTRA_VERSION_CODE, -1L)
        sourceIntent.removeExtra(UpdateContract.EXTRA_ACTION)
        sourceIntent.removeExtra(UpdateContract.EXTRA_VERSION_CODE)
        when (action) {
            UpdateContract.ACTION_DOWNLOAD -> checkForUpdates(force = true)
            UpdateContract.ACTION_INSTALL -> if (versionCode > 0L) installVerifiedUpdate(versionCode)
        }
    }

    private fun installDownloadedApk(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showInstallPermissionDialog()
            return
        }

        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.updateprovider",
            apk
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("verified CompilationMaker update", apkUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { resolveInfo ->
                grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    apkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        try {
            startActivity(installIntent)
        } catch (e: Exception) {
            AppLog.w(this@MainActivity, logTag, "Unable to open Android package installer", e)
            emitUpdateStatus("Could not open Android installer for downloaded update.")
        }
    }

    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Allow app updates")
            .setMessage("Android requires you to trust CompilationMaker as an update source. Enable Allow from this source, return to the app, and tap Check for updates again.")
            .setPositiveButton("Open settings") { _, _ ->
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(settingsIntent)
            }
            .setNegativeButton("Cancel", null)
            .show()
        emitUpdateStatus("Android install-source approval is required before continuing.")
    }

}

class VideoCompilationEngine(private val context: Context) : AutoCloseable {

    private val digitRecognizer: DigitRecognizer = MlKitDigitRecognizer(context)
    private val tag = "CompilationEngine"
    var latestScanReportPath: String? = null
        private set

    private companion object {
        const val DECODER_STALL_TIMEOUT_MS = 30_000L
        const val CANDIDATE_MERGE_TIMEOUT_MS = 10_000L
        const val CLIP_PLAN_TIMEOUT_MS = 10_000L
        const val SOURCE_PREPARATION_TIMEOUT_MS = 30_000L
        const val EXPORT_TIMEOUT_MS = 30L * 60L * 1000L
        const val OUTPUT_DURATION_TOLERANCE_MS = 2_000L
        const val VERIFY_TIMEOUT_MS = 30_000L
    }

    suspend fun findNumberTransitionSegments(
        sourceUri: Uri,
        frameStepMs: Long,
        scanMode: ScanMode,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        scanProfileLabel: String,
        transitionStyle: TransitionStyle,
        experimentalDownscaleSize: Int = 0,
        fallbackUsed: Boolean = false,
        progress: (CompilationPipelineState, String, Int) -> Unit
    ): ScanFindResult = withContext(Dispatchers.IO) {
        AppLog.d(context, tag, "Starting scan for number transitions: $sourceUri")
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sourceUri)
        } catch (failure: Exception) {
            runCatching { retriever.release() }
                .onFailure { AppLog.w(context, tag, "Source retriever cleanup failed", it) }
            recordHandledWorkerFailure(context, tag, "[preparing] source setup failed", failure)
            throw failure
        }
        val sourceWidth = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val sourceHeight = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val retrieverFrameProvider = RetrieverFrameProvider(
            retriever = retriever,
            sourceRotationDegrees = sourceRotationDegrees,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
        if (BuildConfig.DEBUG) {
            AppLog.d(context, tag, "Scan source video=${sourceWidth}x${sourceHeight} rotation=${sourceRotationDegrees} roi=${scanWindow.xPercent},${scanWindow.yPercent},${scanWindow.widthPercent},${scanWindow.heightPercent} quality=$scanProfileLabel mode=$scanMode")
        }
        val timing = ScanTimingSummary()
            val scanConfig = when (scanMode) {
                ScanMode.StableCheckpoint -> ChangeMapConfig(
                sampleStepMs = frameStepMs.coerceAtLeast(250L),
                signatureScaleWidthPx = 220,
                candidatePadMs = 2_500L,
                candidateMergeGapMs = 900L,
                changeThreshold = 10.5f,
                periodicProbeMs = 7_000L,
                neighborhoodStepMs = 300L,
                refineWindowMs = 2_200L,
                refineIterations = 8,
                dedupeMs = 900L,
                prefirstProbeMs = 2_000L,
                maxVisualSamples = 4_200,
                // The one-minute profile is a fixed 61-point state timeline, not a best-effort
                // visual sweep.  Allow it to complete its required evidence collection.
                totalScanBudgetMs = if (frameStepMs >= 60_000L) 600_000L else 110_000L,
                maxCandidateWindows = 64,
                ocrFrameWidthPx = 640,
                stableMaxStepMs = 2_500L,
                stableRampSamples = 4,
                denseRefineStepMs = 250L,
                baselineStepMs = frameStepMs.coerceAtLeast(250L)
            )
            ScanMode.Experimental -> ChangeMapConfig(
                sampleStepMs = frameStepMs.coerceAtLeast(125L),
                signatureScaleWidthPx = experimentalDownscaleSize.coerceIn(8, 64).coerceAtLeast(16),
                candidatePadMs = 2_000L,
                candidateMergeGapMs = 1_100L,
                changeThreshold = 8.5f,
                periodicProbeMs = 5_000L,
                neighborhoodStepMs = 250L,
                refineWindowMs = 2_500L,
                refineIterations = 10,
                dedupeMs = 700L,
                prefirstProbeMs = 1_500L,
                maxVisualSamples = 7_500,
                totalScanBudgetMs = 120_000L,
                maxCandidateWindows = 140,
                ocrFrameWidthPx = max(640, experimentalDownscaleSize),
                stableMaxStepMs = 2_000L,
                stableRampSamples = 2,
                denseRefineStepMs = 200L,
                baselineStepMs = frameStepMs.coerceAtLeast(125L)
            )
        }

        var frameProvider: FrameProvider = retrieverFrameProvider
        var frameProviderFallbackReason: String? = null

        try {
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Could not read duration")
            if (durationMs <= 0L) return@withContext ScanFindResult(emptyList(), timing, emptyList(), ScanMetrics.empty(), 0L)

            val frameProviderSelection = selectFrameProvider(
                retrieverFrameProvider = retrieverFrameProvider,
                sourceUri = sourceUri,
                sourceRotationDegrees = sourceRotationDegrees,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                durationMs = durationMs
            )
            frameProvider = frameProviderSelection.provider
            frameProviderFallbackReason = frameProviderSelection.fallbackReason
            if (frameProviderSelection.fallbackReason != null) {
                progress(CompilationPipelineState.COARSE_SCAN, "Frame provider fallback: ${frameProviderSelection.fallbackReason}", 8)
            }

            val scanStartRealtime = SystemClock.elapsedRealtime()
            val preflightFrame = frameProvider.frameAt(0L, scanConfig.ocrFrameWidthPx)
                ?: throw IllegalStateException("OCR model unavailable: unable to retrieve preflight frame")
            try {
                val roi = cropToWindow(preflightFrame.bitmap, scanWindow)
                try {
                    digitRecognizer.recognize(roi, "preflight")
                    AppLog.i(context, "OCR", "[ocr preflight] completed successfully")
                } finally {
                    roi.recycle()
                }
            } finally {
                preflightFrame.bitmap.recycle()
            }
            val adaptiveStepMs = if (scanConfig.maxVisualSamples > 0) {
                max(
                    scanConfig.sampleStepMs,
                    max(350L, durationMs / scanConfig.maxVisualSamples.toLong())
                )
            } else {
                scanConfig.sampleStepMs
            }
            val effectivePeriodicProbeMs = max(scanConfig.periodicProbeMs, adaptiveStepMs * 2L)
            val candidateWindows = ArrayList<TransitionCandidateWindow>()
            var coarseFramesSeen = 0
            var stopCoarseScan = false
            var scanBudgetReached = false
            val coarseDecodeStart = SystemClock.elapsedRealtime()
            var previousSignature: RoiSignature? = null
            var previousCheckpointMs: Long? = null
            var msSinceLastCandidate = Long.MAX_VALUE
            var consecutiveChangedSamples = 0
            var pendingChangeStartMs: Long? = null
            var pendingPeakScore = 0f
            val currentStepMs = adaptiveStepMs
            var stableSamples = 0
            var visualSamples = 0
            var skippedStableMs = 0L
            var ocrCalls = 0
            var acceptedTransitions = 0
            var rejectedCandidates = 0
            var candidateTimeouts = 0
            val transitionMarks = ArrayList<TransitionMark>()
            val checkpointTimeline = ArrayList<CheckpointTimelineEntry>()
            val recursiveStateEvidence = ArrayList<StateTimelineEvidenceEntry>()
            try {
                retrieverFrameProvider.forEachFrameInWindow(0L, durationMs, adaptiveStepMs, scanConfig.signatureScaleWidthPx) { decoded ->
                    if (stopCoarseScan) {
                        decoded.bitmap.recycle()
                        return@forEachFrameInWindow FrameIterationDecision.STOP
                    }
                    if (SystemClock.elapsedRealtime() - scanStartRealtime >= scanConfig.totalScanBudgetMs) {
                        scanBudgetReached = true
                        stopCoarseScan = true
                        decoded.bitmap.recycle()
                        return@forEachFrameInWindow FrameIterationDecision.STOP
                    }
                    if (!coroutineContext.isActive) {
                        decoded.bitmap.recycle()
                        coroutineContext.ensureActive()
                    }

                    val cursorMs = decoded.timeMs.coerceIn(0L, durationMs)
                    val percent = ((cursorMs.toFloat() / durationMs.toFloat()) * 100f).toInt().coerceIn(0, 100)
                    val elapsedSeconds = String.format(Locale.US, "%.1f", cursorMs / 1000f)
                    val totalSeconds = String.format(Locale.US, "%.1f", durationMs / 1000f)
                    progress(
                        CompilationPipelineState.COARSE_SCAN,
                        "Scanning timeline ${elapsedSeconds}s / ${totalSeconds}s (${percent}%, step ${currentStepMs}ms)",
                        8 + (percent * 35 / 100)
                    )

                    val frame = decoded.bitmap
                    try {
                        visualSamples++
                        val signature = computeRoiSignature(frame, scanWindow, sourceRotationDegrees)
                        val isDirectCheckpointScan = frameStepMs >= 60_000L
                        if (isDirectCheckpointScan) {
                            val state = detectStableCheckpointState(
                                retrieverFrameProvider, cursorMs, scanWindow, sourceRotationDegrees,
                                640, durationMs, timing
                            )
                            ocrCalls += state.calls
                            checkpointTimeline += CheckpointTimelineEntry(cursorMs, signature, state.state)
                        }
                        val signatureDelta = previousSignature?.let { roiDifference(it, signature) } ?: 0f
                        val rawVisualChange = signatureDelta >= scanConfig.changeThreshold
                        if (rawVisualChange) {
                            consecutiveChangedSamples += 1
                            if (pendingChangeStartMs == null) {
                                pendingChangeStartMs = cursorMs
                            }
                            pendingPeakScore = max(pendingPeakScore, signatureDelta)
                        } else {
                            consecutiveChangedSamples = 0
                            pendingChangeStartMs = null
                            pendingPeakScore = 0f
                        }

                        val visualChangeDetected = rawVisualChange &&
                            (isDirectCheckpointScan || consecutiveChangedSamples >= 2)
                        val shouldProbe = visualChangeDetected
                        val probeSeedMs = if (isDirectCheckpointScan) {
                            ((previousCheckpointMs ?: cursorMs) + cursorMs) / 2L
                        } else if (visualChangeDetected) {
                            pendingChangeStartMs ?: cursorMs
                        } else {
                            cursorMs
                        }
                        val probePeakScore = if (visualChangeDetected && pendingPeakScore > 0f) {
                            pendingPeakScore
                        } else {
                            signatureDelta
                        }
                        if (shouldProbe && !isDirectCheckpointScan) {
                            val padStart = if (isDirectCheckpointScan) {
                                previousCheckpointMs ?: cursorMs
                            } else {
                                max(0L, probeSeedMs - scanConfig.candidatePadMs)
                            }
                            val padEnd = if (isDirectCheckpointScan) cursorMs else min(durationMs, probeSeedMs + scanConfig.candidatePadMs)
                            val seedMs = probeSeedMs
                            val reason = when {
                                visualChangeDetected -> CandidateReason.VisualChange
                                else -> CandidateReason.VisualChange
                            }
                            if (!isDirectCheckpointScan && candidateWindows.isNotEmpty()) {
                                val last = candidateWindows.last()
                                if (padStart <= last.endMs + scanConfig.candidateMergeGapMs) {
                                    candidateWindows[candidateWindows.lastIndex] = last.copy(
                                        startMs = min(last.startMs, padStart),
                                        endMs = max(last.endMs, padEnd),
                                        seedMs = (last.seedMs + seedMs) / 2L,
                                        peakScore = max(last.peakScore, probePeakScore),
                                        reason = if (last.reason == CandidateReason.VisualChange || reason == CandidateReason.VisualChange) CandidateReason.VisualChange else last.reason,
                                        sampleCount = last.sampleCount + 1
                                    )
                                } else {
                                    candidateWindows.add(
                                        TransitionCandidateWindow(
                                            startMs = padStart,
                                            endMs = padEnd,
                                            seedMs = seedMs,
                                            peakScore = probePeakScore,
                                            reason = reason,
                                            sampleCount = 1
                                        )
                                    )
                                }
                            } else {
                                candidateWindows.add(
                                    TransitionCandidateWindow(
                                        startMs = padStart,
                                        endMs = padEnd,
                                        seedMs = seedMs,
                                        peakScore = probePeakScore,
                                        reason = reason,
                                        sampleCount = 1
                                    )
                                )
                            }
                            msSinceLastCandidate = 0L
                            pendingChangeStartMs = null
                            pendingPeakScore = 0f
                            consecutiveChangedSamples = 0
                            stableSamples = 0
                        } else {
                            msSinceLastCandidate += adaptiveStepMs
                            stableSamples++
                        }
                        previousSignature = signature
                        previousCheckpointMs = cursorMs
                    } finally {
                        frame.recycle()
                    }

                    coarseFramesSeen++
                    FrameIterationDecision.CONTINUE
                }
                timing.decodeMs += SystemClock.elapsedRealtime() - coarseDecodeStart
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (frameProvider !== retrieverFrameProvider) {
                    AppLog.w(context, tag, "MediaCodec coarse scan failed; falling back", e)
                    frameProviderFallbackReason = "MediaCodec coarse scan failed: ${e.message ?: e::class.java.simpleName}"
                    progress(CompilationPipelineState.COARSE_SCAN, "Frame provider fallback: $frameProviderFallbackReason", 10)
                    frameProvider = retrieverFrameProvider
                    coarseFramesSeen = 0
                    previousSignature = null
                    previousCheckpointMs = null
                    checkpointTimeline.clear()
                    recursiveStateEvidence.clear()
                    val retryDecodeStart = SystemClock.elapsedRealtime()
                    retrieverFrameProvider.forEachFrameInWindow(0L, durationMs, adaptiveStepMs, scanConfig.signatureScaleWidthPx) { decoded ->
                        if (SystemClock.elapsedRealtime() - scanStartRealtime >= scanConfig.totalScanBudgetMs) {
                            scanBudgetReached = true
                            stopCoarseScan = true
                            decoded.bitmap.recycle()
                            return@forEachFrameInWindow FrameIterationDecision.STOP
                        }
                        if (!coroutineContext.isActive) {
                            decoded.bitmap.recycle()
                            coroutineContext.ensureActive()
                        }
                        if (stopCoarseScan) {
                            decoded.bitmap.recycle()
                            return@forEachFrameInWindow FrameIterationDecision.STOP
                        }
                        val cursorMs = decoded.timeMs.coerceIn(0L, durationMs)
                        val percent = ((cursorMs.toFloat() / durationMs.toFloat()) * 100f).toInt().coerceIn(0, 100)
                        val elapsedSeconds = String.format(Locale.US, "%.1f", cursorMs / 1000f)
                        val totalSeconds = String.format(Locale.US, "%.1f", durationMs / 1000f)
                        progress(
                            CompilationPipelineState.COARSE_SCAN,
                            "Scanning timeline ${elapsedSeconds}s / ${totalSeconds}s (${percent}%, step ${currentStepMs}ms)",
                            8 + (percent * 35 / 100)
                        )
                        val frame = decoded.bitmap
                        try {
                            visualSamples++
                            val signature = computeRoiSignature(frame, scanWindow, sourceRotationDegrees)
                            val isDirectCheckpointScan = frameStepMs >= 60_000L
                            if (isDirectCheckpointScan) {
                                val state = detectStableCheckpointState(
                                    retrieverFrameProvider, cursorMs, scanWindow, sourceRotationDegrees,
                                    640, durationMs, timing
                                )
                                ocrCalls += state.calls
                                checkpointTimeline += CheckpointTimelineEntry(cursorMs, signature, state.state)
                            }
                            val signatureDelta = previousSignature?.let { roiDifference(it, signature) } ?: 0f
                            val rawVisualChange = signatureDelta >= scanConfig.changeThreshold
                            if (rawVisualChange) {
                                consecutiveChangedSamples += 1
                                if (pendingChangeStartMs == null) {
                                    pendingChangeStartMs = cursorMs
                                }
                                pendingPeakScore = max(pendingPeakScore, signatureDelta)
                            } else {
                                consecutiveChangedSamples = 0
                                pendingChangeStartMs = null
                                pendingPeakScore = 0f
                            }
                            val visualChangeDetected = rawVisualChange &&
                                (isDirectCheckpointScan || consecutiveChangedSamples >= 2)
                            val shouldProbe = visualChangeDetected
                            val probeSeedMs = if (isDirectCheckpointScan) {
                                ((previousCheckpointMs ?: cursorMs) + cursorMs) / 2L
                            } else if (visualChangeDetected) {
                                pendingChangeStartMs ?: cursorMs
                            } else {
                                cursorMs
                            }
                            val probePeakScore = if (visualChangeDetected && pendingPeakScore > 0f) pendingPeakScore else signatureDelta
                            if (shouldProbe && !isDirectCheckpointScan) {
                                val padStart = if (isDirectCheckpointScan) {
                                    previousCheckpointMs ?: cursorMs
                                } else {
                                    max(0L, probeSeedMs - scanConfig.candidatePadMs)
                                }
                                val padEnd = if (isDirectCheckpointScan) cursorMs else min(durationMs, probeSeedMs + scanConfig.candidatePadMs)
                                val seedMs = probeSeedMs
                                val reason = when {
                                    visualChangeDetected -> CandidateReason.VisualChange
                                    else -> CandidateReason.VisualChange
                                }
                                if (!isDirectCheckpointScan && candidateWindows.isNotEmpty()) {
                                    val last = candidateWindows.last()
                                    if (padStart <= last.endMs + scanConfig.candidateMergeGapMs) {
                                        candidateWindows[candidateWindows.lastIndex] = last.copy(
                                            startMs = min(last.startMs, padStart),
                                            endMs = max(last.endMs, padEnd),
                                            seedMs = (last.seedMs + seedMs) / 2L,
                                            peakScore = max(last.peakScore, probePeakScore),
                                            reason = if (last.reason == CandidateReason.VisualChange || reason == CandidateReason.VisualChange) CandidateReason.VisualChange else last.reason,
                                            sampleCount = last.sampleCount + 1
                                        )
                                    } else {
                                        candidateWindows.add(TransitionCandidateWindow(padStart, padEnd, seedMs, probePeakScore, reason, 1))
                                    }
                                } else {
                                    candidateWindows.add(TransitionCandidateWindow(padStart, padEnd, seedMs, probePeakScore, reason, 1))
                                }
                                msSinceLastCandidate = 0L
                                pendingChangeStartMs = null
                                pendingPeakScore = 0f
                                consecutiveChangedSamples = 0
                                stableSamples = 0
                            } else {
                                msSinceLastCandidate += adaptiveStepMs
                                stableSamples++
                            }
                            previousSignature = signature
                            previousCheckpointMs = cursorMs
                        } finally {
                            frame.recycle()
                        }
                        coarseFramesSeen++
                        FrameIterationDecision.CONTINUE
                    }
                    timing.decodeMs += SystemClock.elapsedRealtime() - retryDecodeStart
                } else {
                    throw e
                }
            }

            if (frameStepMs >= 60_000L && checkpointTimeline.size >= 2) {
                candidateWindows.clear()
                val adjacentScores = checkpointTimeline.zipWithNext { previous, current ->
                    roiDifference(previous.signature, current.signature)
                }
                val threshold = adaptiveVisualThreshold(adjacentScores)
                checkpointTimeline.zipWithNext().forEachIndexed { index, (previous, current) ->
                    val visualScore = adjacentScores[index]
                    val stateChanged = previous.state.stable && current.state.stable &&
                        previous.state.value != current.state.value
                    if (stateChanged || visualScore >= threshold.threshold) {
                        candidateWindows += TransitionCandidateWindow(
                            startMs = previous.timeMs,
                            endMs = current.timeMs,
                            seedMs = (previous.timeMs + current.timeMs) / 2L,
                            peakScore = visualScore,
                            reason = CandidateReason.VisualChange,
                            sampleCount = 1,
                            fromNumber = previous.state.value,
                            toNumber = current.state.value,
                            fromStateStable = previous.state.stable,
                            toStateStable = current.state.stable
                        )
                    }
                }
                AppLog.i(
                    context,
                    tag,
                    "[timeline] checkpoints=${checkpointTimeline.size} median=${threshold.median} mad=${threshold.mad} threshold=${threshold.threshold} candidates=${candidateWindows.size}"
                )
            }

            val candidateTimestampLog = candidateWindows.joinToString(",") { it.seedMs.toString() }
            if (candidateWindows.isEmpty()) {
                AppLog.i(context, tag, "[finalize] candidate count=0 thread=${Thread.currentThread().name}")
                AppLog.i(context, tag, "[finalize] candidate timestamps= thread=${Thread.currentThread().name}")
                val noResultsMessage = if (scanBudgetReached) {
                    "No transitions detected before scan budget expired"
                } else {
                    "No transition candidates were detected"
                }
                progress(CompilationPipelineState.NO_RESULTS, noResultsMessage, 45)
                AppLog.i(context, tag, "[worker] no-results: $noResultsMessage thread=${Thread.currentThread().name}")
                return@withContext ScanFindResult(
                    emptyList(),
                    timing,
                    emptyList(),
                    ScanMetrics.empty(),
                    durationMs,
                    candidateCount = 0,
                    candidateTimestampsMs = emptyList(),
                    scanBudgetReached = scanBudgetReached
                )
            }
            if (scanBudgetReached) {
                progress(CompilationPipelineState.FINALIZING, "Scan budget reached. Finalizing from collected candidates", 43)
            }
            AppLog.i(context, tag, "[finalize] candidate count=${candidateWindows.size} thread=${Thread.currentThread().name}")
            AppLog.i(context, tag, "[finalize] candidate timestamps=$candidateTimestampLog thread=${Thread.currentThread().name}")
            progress(CompilationPipelineState.FINALIZING, "Finalizing ${candidateWindows.size} candidates", 44)
            AppLog.i(context, tag, "[finalize] beginning candidate merge thread=${Thread.currentThread().name}")
            val candidateMergeStarted = SystemClock.elapsedRealtime()
            val mergedCandidateWindows = try {
                withTimeout(CANDIDATE_MERGE_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.Default) {
                        if (candidateWindows.size <= scanConfig.maxCandidateWindows) {
                            candidateWindows.toList()
                        } else {
                            val stride = max(1, candidateWindows.size / scanConfig.maxCandidateWindows)
                            candidateWindows.filterIndexed { index, _ -> index % stride == 0 || index == candidateWindows.lastIndex }
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                val failure = IllegalStateException("Candidate merge timed out after ${CANDIDATE_MERGE_TIMEOUT_MS}ms", timeout)
                recordHandledWorkerFailure(context, tag, "[finalize] candidate merge failed", failure)
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordHandledWorkerFailure(context, tag, "[finalize] candidate merge failed", failure)
                throw failure
            }
            AppLog.i(
                context,
                tag,
                "[finalize] candidate merge completed in ${SystemClock.elapsedRealtime() - candidateMergeStarted} ms thread=${Thread.currentThread().name}"
            )
            if (mergedCandidateWindows.size != candidateWindows.size) {
                progress(
                    CompilationPipelineState.FINALIZING,
                    "Speed cap: refining ${mergedCandidateWindows.size}/${candidateWindows.size} candidate windows",
                    45
                )
            }

            val candidateWindowsForRefine = if (frameStepMs >= 60_000L) {
                val investigated = investigateCheckpointCandidateIntervals(
                    candidates = mergedCandidateWindows,
                    frameProvider = retrieverFrameProvider,
                    scanWindow = scanWindow,
                    sourceRotationDegrees = sourceRotationDegrees,
                    durationMs = durationMs,
                    ocrFrameWidthPx = scanConfig.ocrFrameWidthPx,
                    timing = timing
                )
                ocrCalls += investigated.calls
                recursiveStateEvidence += investigated.stateEvidence
                AppLog.i(
                    context,
                    tag,
                    "[timeline] recursive probes=${investigated.probes} semantic leaves=${investigated.candidates.size}"
                )
                investigated.candidates
            } else {
                mergedCandidateWindows
            }

            var hasCapturedFirstOne = false
            val confirmedLedger = IncrementalTransitionLedger(scanConfig.dedupeMs)
            val candidateConfirmationEvidence = ArrayList<CandidateConfirmationEvidence>()
            val ocrPassStart = SystemClock.elapsedRealtime()
            val globalConfirmationBudgetMs = ConfirmationTimeoutPolicy.overallMs(candidateWindowsForRefine.size)
            val globalConfirmationDeadlineMs = ocrPassStart + globalConfirmationBudgetMs
            AppLog.i(context, tag, "[finalize] beginning number confirmation thread=${Thread.currentThread().name}")
            for ((index, candidate) in candidateWindowsForRefine.withIndex()) {
                currentCoroutineContext().ensureActive()
                val isCheckpointInterval = frameStepMs >= 60_000L &&
                    candidate.fromStateStable && candidate.toStateStable
                val plannedWorkUnits = if (isCheckpointInterval) {
                    ConfirmationTimeoutPolicy.CHECKPOINT_BOUNDARY_WORK_UNITS
                } else {
                    ConfirmationTimeoutPolicy.GENERIC_CANDIDATE_WORK_UNITS
                }
                val candidateLimitMs = ConfirmationTimeoutPolicy.candidateMs(plannedWorkUnits)
                val candidateStartMonotonicMs = SystemClock.elapsedRealtime()
                val deadlineTracker = ConfirmationDeadlineTracker(
                    globalStartMs = ocrPassStart,
                    globalBudgetMs = globalConfirmationBudgetMs,
                    localStartMs = candidateStartMonotonicMs,
                    localBudgetMs = candidateLimitMs,
                    clock = MonotonicTimeSource { SystemClock.elapsedRealtime() }
                )
                var completedWorkUnits = 0
                var currentWork = "candidate-start"
                var innerTimeoutCount = 0
                var innerFailureCount = 0
                var partialEvidenceCollected = candidate.fromStateStable || candidate.toStateStable
                var candidateDisposition = "inconclusive"
                var timeoutSource = "none"
                var exceptionType = "none"
                var cancellationCause = "none"
                var beforeObserved: Int? = candidate.fromNumber.takeIf { candidate.fromStateStable }
                var afterObserved: Int? = candidate.toNumber.takeIf { candidate.toStateStable }
                var dispositionReason = "strict confirmation did not complete"
                try {
                    // A timeout is evidence about this interval only.  Never let it discard later
                    // semantic intervals that have already been retained by the checkpoint timeline.
                    withTimeout(candidateLimitMs) {
                val confirmationPercent = 46 + ((index + 1) * 7 / candidateWindowsForRefine.size.coerceAtLeast(1))
                progress(CompilationPipelineState.REFINING, "Confirming transition ${index + 1} of ${candidateWindowsForRefine.size}", confirmationPercent)
                val candidateStart = max(0L, candidate.startMs)
                val candidateEnd = min(durationMs, candidate.endMs)
                val sampleStart = max(0L, candidateStart - scanConfig.prefirstProbeMs)
                val sampleEnd = min(durationMs, candidateEnd + scanConfig.prefirstProbeMs)
                val centerMs = candidate.seedMs.coerceIn(sampleStart, sampleEnd)
                if (isCheckpointInterval && candidate.fromStateStable && candidate.toStateStable) {
                    val semantic = classifyTransition(candidate.fromNumber, candidate.toNumber)
                    if (!semantic.accepted || !semantic.sequential) {
                        rejectedCandidates++
                        candidateDisposition = "rejected"
                        dispositionReason = "non-sequential stable endpoints ${semantic.label}"
                        AppLog.i(context, tag, "[ocr candidate] index=$index timestamp=${candidate.seedMs} disposition=rejected-non-sequential state=${semantic.label}")
                        return@withTimeout
                    }
                }

                currentWork = "before-state"
                val beforeNumber = if (candidate.fromStateStable) {
                    TimedDetection(candidate.fromNumber, 0, candidateStart)
                } else if (isCheckpointInterval) {
                    detectStableNumberAt(
                        frameProvider, candidateStart, scanWindow, sourceRotationDegrees,
                        scanConfig.ocrFrameWidthPx, durationMs, timing
                    )
                } else detectNumberInRange(
                    frameProvider, sampleStart, centerMs, scanConfig.neighborhoodStepMs,
                    scanWindow, sourceRotationDegrees, scanConfig.ocrFrameWidthPx, timing
                )
                ocrCalls += beforeNumber.calls
                completedWorkUnits += beforeNumber.calls.coerceAtLeast(1)
                innerTimeoutCount += beforeNumber.timeoutCount
                innerFailureCount += beforeNumber.failureCount
                beforeObserved = beforeNumber.value
                partialEvidenceCollected = partialEvidenceCollected || beforeNumber.calls > 0 || beforeNumber.value != null
                currentWork = "after-state"
                val afterNumber = if (candidate.toStateStable) {
                    TimedDetection(candidate.toNumber, 0, candidateEnd)
                } else if (isCheckpointInterval) {
                    detectStableNumberAt(
                        frameProvider, candidateEnd, scanWindow, sourceRotationDegrees,
                        scanConfig.ocrFrameWidthPx, durationMs, timing
                    )
                } else detectNumberInRange(
                    frameProvider, centerMs, sampleEnd, scanConfig.neighborhoodStepMs,
                    scanWindow, sourceRotationDegrees, scanConfig.ocrFrameWidthPx, timing
                )
                ocrCalls += afterNumber.calls
                completedWorkUnits += afterNumber.calls.coerceAtLeast(1)
                innerTimeoutCount += afterNumber.timeoutCount
                innerFailureCount += afterNumber.failureCount
                afterObserved = afterNumber.value
                partialEvidenceCollected = partialEvidenceCollected || afterNumber.calls > 0 || afterNumber.value != null
                var transitionMs: Long? = null
                var transitionLabel = "Transition"
                var transitionTargetNumber: Int? = afterNumber.value
                val transitionEvidence = ArrayList<String>(4)
                transitionEvidence.add("Visual pass reason=${candidate.reason}")

                if (!hasCapturedFirstOne && afterNumber.value == 1 && beforeNumber.value != 1) {
                    currentWork = "refine-first-one"
                    val firstOneMs = if (isCheckpointInterval) {
                        refineCheckpointTransitionBoundary(
                            frameProvider, candidateStart, candidateEnd, 1,
                            scanWindow, sourceRotationDegrees, durationMs,
                            scanConfig.ocrFrameWidthPx, timing
                        )
                    } else {
                        locateFirstNumberAppearance(
                            frameProvider = frameProvider,
                            startMs = sampleStart,
                            hitMs = sampleEnd,
                            scanWindow = scanWindow,
                            sourceRotationDegrees = sourceRotationDegrees,
                            targetNumber = 1,
                            stepMs = min(1_000L, scanConfig.sampleStepMs),
                            ocrFrameWidthPx = scanConfig.ocrFrameWidthPx,
                            timing = timing
                        )
                    }
                    ocrCalls += firstOneMs.calls
                    completedWorkUnits += firstOneMs.calls
                    innerTimeoutCount += firstOneMs.timeoutCount
                    innerFailureCount += firstOneMs.failureCount
                    if (firstOneMs.timeMs != null) {
                        transitionMs = firstOneMs.timeMs
                        transitionLabel = "First 1"
                        transitionTargetNumber = 1
                        transitionEvidence.add("Detected first overlay 1")
                        hasCapturedFirstOne = true
                    } else {
                        transitionEvidence.add("Could not locate first overlay 1")
                    }
                }

                if (transitionMs == null && beforeNumber.value != null && afterNumber.value != null && beforeNumber.value != afterNumber.value) {
                    currentWork = "refine-number-change"
                    val refined = if (isCheckpointInterval) {
                        refineCheckpointTransitionBoundary(
                            frameProvider, candidateStart, candidateEnd, afterNumber.value,
                            scanWindow, sourceRotationDegrees, durationMs,
                            scanConfig.ocrFrameWidthPx, timing
                        )
                    } else refineTransitionBoundary(
                        frameProvider, sampleStart, sampleEnd, beforeNumber.value,
                        afterNumber.value, scanWindow, sourceRotationDegrees, timing,
                        scanConfig.denseRefineStepMs, scanConfig.refineIterations,
                        scanConfig.refineWindowMs, durationMs, scanConfig.ocrFrameWidthPx
                    )
                    ocrCalls += refined.calls
                    completedWorkUnits += refined.calls
                    innerTimeoutCount += refined.timeoutCount
                    innerFailureCount += refined.failureCount
                    if (refined.timeMs != null) {
                        transitionMs = refined.timeMs
                        transitionLabel = "Number ${beforeNumber.value} -> ${afterNumber.value}"
                        transitionTargetNumber = afterNumber.value
                        transitionEvidence.add("Refined transition between OCR values")
                    }
                }

                if (transitionMs == null && beforeNumber.value == null && afterNumber.value != null) {
                    currentWork = "refine-first-appearance"
                    val firstAppearMs = locateFirstNumberAppearance(
                        frameProvider = frameProvider,
                        startMs = sampleStart,
                        hitMs = sampleEnd,
                        scanWindow = scanWindow,
                        sourceRotationDegrees = sourceRotationDegrees,
                        targetNumber = afterNumber.value,
                        stepMs = min(1_000L, scanConfig.sampleStepMs),
                        ocrFrameWidthPx = scanConfig.ocrFrameWidthPx,
                        timing = timing
                    )
                    ocrCalls += firstAppearMs.calls
                    completedWorkUnits += firstAppearMs.calls
                    innerTimeoutCount += firstAppearMs.timeoutCount
                    innerFailureCount += firstAppearMs.failureCount
                    if (firstAppearMs.timeMs != null) {
                        transitionMs = firstAppearMs.timeMs
                        transitionLabel = "Start ${afterNumber.value}"
                        transitionTargetNumber = afterNumber.value
                        transitionEvidence.add("Detected first appearance from unknown baseline")
                    } else {
                        transitionEvidence.add("Could not bracket start timestamp")
                    }
                }

                if (transitionMs == null) {
                    rejectedCandidates++
                    transitionEvidence.add("No valid OCR timestamp found")
                    candidateDisposition = if (innerTimeoutCount > 0 && partialEvidenceCollected) "partial_evidence" else "ocr_no_text"
                    timeoutSource = if (innerTimeoutCount > 0) "inner_frame_or_ocr" else "none"
                    dispositionReason = "no strict timestamp; innerTimeouts=$innerTimeoutCount innerFailures=$innerFailureCount"
                    AppLog.i(context, tag, "[ocr candidate] index=$index timestamp=${candidate.seedMs} disposition=$candidateDisposition timeoutSource=$timeoutSource")
                    return@withTimeout
                }
                val toNumber = transitionTargetNumber
                if (toNumber == null) {
                    rejectedCandidates++
                    transitionEvidence.add("No confirmed target number")
                    candidateDisposition = "inconclusive"
                    dispositionReason = "no confirmed target number"
                    AppLog.i(context, tag, "[ocr candidate] index=$index timestamp=${candidate.seedMs} disposition=rejected-no-target")
                    return@withTimeout
                }
                val transitionAtMs = transitionMs
                val fromNumber = beforeNumber.value
                val semantic = classifyTransition(fromNumber, toNumber)
                if (!semantic.sequential) {
                    rejectedCandidates++
                    transitionEvidence.add("Rejected non-sequential transition ${semantic.label}")
                    candidateDisposition = "rejected"
                    dispositionReason = "non-sequential refined transition ${semantic.label}"
                    AppLog.i(context, tag, "[ocr candidate] index=$index timestamp=${candidate.seedMs} disposition=rejected-non-sequential state=${semantic.label}")
                    return@withTimeout
                }
                confirmedLedger.confirm(transitionAtMs)

                val cutStartMs = max(0L, transitionAtMs - 10_000L)
                val cutEndMs = min(durationMs, transitionAtMs + 30_000L)
                transitionEvidence.add("Semantic transition ${semantic.label}")
                acceptedTransitions++
                candidateDisposition = "confirmed"
                dispositionReason = "semantic transition ${semantic.label}"
                currentWork = "complete"
                transitionMarks.add(
                    TransitionMark(
                        eventBoundaryMs = transitionAtMs,
                        fromNumber = fromNumber,
                        toNumber = toNumber,
                        confidence = if (fromNumber != null && toNumber == fromNumber + 1) 1.0f else 0.7f,
                        requestedCutStartMs = cutStartMs,
                        requestedCutEndMs = cutEndMs,
                        candidateReason = candidate.reason.name,
                        candidatePeakScore = candidate.peakScore,
                        evidence = transitionEvidence
                    )
                )
                val progressPercent = confirmationPercent
                progress(
                    CompilationPipelineState.REFINING,
                    "$transitionLabel (candidate ${index + 1}/${candidateWindowsForRefine.size}) at ${formatMs(transitionAtMs)}; cut ${formatMs(cutStartMs)} -> ${formatMs(cutEndMs)}",
                    progressPercent
                )
                AppLog.d(
                    context,
                    tag,
                    "[ocr candidate] index=$index timestamp=${candidate.seedMs} refined=$transitionAtMs score=${candidate.peakScore} before=$beforeNumber after=$transitionTargetNumber disposition=confirmed @${formatMs(transitionAtMs)}"
                )
                    }
                } catch (timeout: TimeoutCancellationException) {
                    candidateTimeouts++
                    val timeoutSnapshot = deadlineTracker.snapshot()
                    candidateDisposition = if (partialEvidenceCollected) "partial_evidence" else "local_timeout"
                    timeoutSource = propagatedConfirmationTimeoutSource(timeoutSnapshot)
                    exceptionType = timeout::class.java.name
                    dispositionReason = "strict confirmation exceeded candidate wall guard"
                    AppLog.w(context, tag, "[ocr candidate] index=$index timestamp=${candidate.seedMs} disposition=$candidateDisposition timeoutSource=$timeoutSource; continuing with later intervals and preserving ${confirmedLedger.size} confirmations")
                } catch (cancelled: CancellationException) {
                    candidateDisposition = "canceled"
                    timeoutSource = "none"
                    exceptionType = cancelled::class.java.name
                    cancellationCause = cancelled.message ?: "unspecified cancellation"
                    dispositionReason = "parent or user cancellation"
                    throw cancelled
                } catch (failure: Exception) {
                    candidateDisposition = if (failure::class.java.name.contains("ocr", ignoreCase = true) ||
                        failure::class.java.name.contains("mlkit", ignoreCase = true)
                    ) "ocr_failure" else "decoder_failure"
                    exceptionType = failure::class.java.name
                    dispositionReason = failure.message ?: failure::class.java.simpleName
                    innerFailureCount++
                    AppLog.w(context, tag, "[ocr candidate] index=$index timestamp=${candidate.seedMs} disposition=$candidateDisposition type=${failure::class.java.simpleName}", failure)
                } finally {
                    val deadline = deadlineTracker.snapshot()
                    val evidence = CandidateConfirmationEvidence(
                        candidateIndex = index,
                        candidateTimestampMs = candidate.seedMs,
                        candidateStartMonotonicMs = candidateStartMonotonicMs,
                        globalConfirmationStartMonotonicMs = ocrPassStart,
                        globalDeadlineMonotonicMs = globalConfirmationDeadlineMs,
                        localDeadlineMonotonicMs = deadline.localDeadlineMs,
                        localBudgetMs = candidateLimitMs,
                        remainingGlobalBudgetMs = deadline.remainingGlobalMs,
                        elapsedCandidateMs = deadline.elapsedLocalMs,
                        elapsedGlobalMs = deadline.elapsedGlobalMs,
                        currentWork = currentWork,
                        plannedWorkUnits = plannedWorkUnits,
                        completedWorkUnits = completedWorkUnits,
                        timeoutSource = timeoutSource,
                        exceptionType = exceptionType,
                        cancellationCause = cancellationCause,
                        partialEvidenceCollected = partialEvidenceCollected,
                        innerTimeoutCount = innerTimeoutCount,
                        innerFailureCount = innerFailureCount,
                        beforeNumber = beforeObserved,
                        afterNumber = afterObserved,
                        disposition = candidateDisposition,
                        reason = dispositionReason
                    )
                    candidateConfirmationEvidence += evidence
                    AppLog.i(
                        context,
                        tag,
                        "[confirmation evidence] candidateIndex=$index timestampMs=${candidate.seedMs} " +
                            "candidateStartMonotonicMs=$candidateStartMonotonicMs globalStartMonotonicMs=$ocrPassStart " +
                            "globalDeadlineMonotonicMs=$globalConfirmationDeadlineMs localDeadlineMonotonicMs=${deadline.localDeadlineMs} " +
                            "localBudgetMs=$candidateLimitMs remainingGlobalBudgetMs=${deadline.remainingGlobalMs} " +
                            "elapsedCandidateMs=${deadline.elapsedLocalMs} elapsedGlobalMs=${deadline.elapsedGlobalMs} " +
                            "currentWork=$currentWork plannedWorkUnits=$plannedWorkUnits completedWorkUnits=$completedWorkUnits " +
                            "timeoutSource=$timeoutSource exceptionType=$exceptionType cancellationCause=$cancellationCause " +
                            "partialEvidence=$partialEvidenceCollected innerTimeouts=$innerTimeoutCount innerFailures=$innerFailureCount " +
                            "disposition=$candidateDisposition reason=${dispositionReason.take(160)}"
                    )
                }
            }
            AppLog.i(
                context,
                tag,
                "[finalize] number confirmation completed in ${SystemClock.elapsedRealtime() - ocrPassStart} ms thread=${Thread.currentThread().name}"
            )
            val uniqueTransitions = confirmedLedger.snapshot()
            AppLog.i(context, tag, "[finalize] confirmed transitions=${uniqueTransitions.size} thread=${Thread.currentThread().name}")

            val confirmationByIndex = candidateConfirmationEvidence.associateBy { it.candidateIndex }
            fun provisionalEvidenceFor(
                candidates: List<TransitionCandidateWindow>,
                includeConfirmationEvidence: Boolean
            ): List<ProvisionalTransitionEvidence> = candidates.mapIndexed { index, candidate ->
                val confirmation = confirmationByIndex[index].takeIf { includeConfirmationEvidence }
                ProvisionalTransitionEvidence(
                    timestampMs = candidate.seedMs,
                    visualScore = candidate.peakScore,
                    fromNumber = candidate.fromNumber,
                    toNumber = candidate.toNumber,
                    fromStateStable = candidate.fromStateStable,
                    toStateStable = candidate.toStateStable,
                    partialEvidence = confirmation?.partialEvidenceCollected == true,
                    timeoutCount = (confirmation?.innerTimeoutCount ?: 0) +
                        if (confirmation?.timeoutSource == "candidate_local") 1 else 0,
                    reason = confirmation?.let { "${it.disposition}: ${it.reason}" }
                        ?: "retained visual candidate without strict confirmation record"
                )
            }
            val provisionalEvidence = provisionalEvidenceFor(
                candidateWindowsForRefine,
                includeConfirmationEvidence = true
            )
            val confirmedCandidateIndices = candidateConfirmationEvidence
                .filter { it.disposition == "confirmed" }
                .map { it.candidateIndex }
                .toSet()
            val transitionPlanSelection = selectTransitionPlanWithBaselineFallback(
                confirmedTimestampsMs = uniqueTransitions,
                refinedEvidence = provisionalEvidence,
                baselineEvidence = provisionalEvidenceFor(
                    mergedCandidateWindows,
                    includeConfirmationEvidence = false
                ),
                dedupeToleranceMs = scanConfig.dedupeMs,
                confirmedCandidateIndices = confirmedCandidateIndices
            )
            val transitionPlanPoints = transitionPlanSelection.points
            val baselineVisualFallbackUsed = transitionPlanSelection.baselineFallbackUsed
            if (baselineVisualFallbackUsed) {
                AppLog.w(
                    context,
                    tag,
                    "[clip plan] semantic refinement produced no usable points; restored " +
                        "${transitionPlanPoints.size} provisional point(s) from preserved baseline candidates"
                )
            }
            val provisionalTransitions = transitionPlanPoints.filter {
                it.classification != TransitionPlanClassification.CONFIRMED
            }
            AppLog.i(
                context,
                tag,
                "[finalize] transition plan confirmed=${uniqueTransitions.size} " +
                    "provisionalHigh=${provisionalTransitions.count { it.classification == TransitionPlanClassification.PROVISIONAL_HIGH }} " +
                    "provisionalMedium=${provisionalTransitions.count { it.classification == TransitionPlanClassification.PROVISIONAL_MEDIUM }} " +
                    "total=${transitionPlanPoints.size}"
            )

            AppLog.i(context, tag, "[clip plan] generating clips thread=${Thread.currentThread().name}")
            val visualFallbackTransitions = provisionalTransitions.map { it.timestampMs }
            val clipTransitions = transitionPlanPoints.map { it.timestampMs }
            val clipSourceLabel = when {
                visualFallbackTransitions.isEmpty() -> "OCR-confirmed"
                uniqueTransitions.isEmpty() -> "provisional visual/OCR"
                else -> "confirmed plus provisional"
            }
            if (visualFallbackTransitions.isNotEmpty()) {
                AppLog.w(
                    context,
                    tag,
                    "[clip plan] provisional preview enabled confirmed=${uniqueTransitions.size} " +
                        "provisional=${visualFallbackTransitions.size}; primary confirmed-only QA remains ineligible"
                )
            }
            progress(CompilationPipelineState.BUILDING_CLIP_PLAN, "Building clip plan from ${clipTransitions.size} $clipSourceLabel transitions", 54)
            val clipPlanStarted = SystemClock.elapsedRealtime()
            val merged = try {
                withTimeout(CLIP_PLAN_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.Default) {
                        planExactTransitionSegments(clipTransitions, durationMs)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                val failure = IllegalStateException("Clip-plan generation timed out after ${CLIP_PLAN_TIMEOUT_MS}ms", timeout)
                recordHandledWorkerFailure(context, tag, "[clip plan] generation failed", failure)
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordHandledWorkerFailure(context, tag, "[clip plan] generation failed", failure)
                throw failure
            }
            timing.mergeMs = SystemClock.elapsedRealtime() - clipPlanStarted
            AppLog.i(context, tag, "[clip plan] generated ${merged.size} clips in ${timing.mergeMs} ms thread=${Thread.currentThread().name}")
            AppLog.i(
                context,
                tag,
                "[clip plan] clip start/end timestamps=${merged.joinToString(",") { "${it.startMs}-${it.endMs}" }} thread=${Thread.currentThread().name}"
            )
            val wallClockMs = SystemClock.elapsedRealtime() - scanStartRealtime
            val baselineSamplesEstimate = (durationMs / scanConfig.baselineStepMs).toInt().coerceAtLeast(1)
            val ocrReductionPercent = if (baselineSamplesEstimate > 0) {
                (100 - (ocrCalls * 100 / baselineSamplesEstimate)).coerceIn(0, 100)
            } else {
                0
            }
            val throughput = if (wallClockMs > 0L) durationMs.toFloat() / wallClockMs.toFloat() else 0f
            progress(
                CompilationPipelineState.BUILDING_CLIP_PLAN,
                "Scanner metrics: ${String.format(Locale.US, "%.2f", throughput)}x realtime, visual=$visualSamples baseline=$baselineSamplesEstimate OCR=$ocrCalls (-$ocrReductionPercent%)",
                54
            )
            val report = ScanFindReport(
                sourceUri = sourceUri.toString(),
                profileLabel = scanProfileLabel,
                scannerMode = scanMode,
                frameProvider = frameProvider::class.simpleName ?: "FrameProvider",
                frameProviderFallbackReason = frameProviderFallbackReason,
                scanWindow = scanWindow,
                frameStepMs = frameStepMs,
                checkpointIntervalMs = frameStepMs,
                experimentalDownscaleSize = if (scanMode == ScanMode.Experimental) experimentalDownscaleSize else 0,
                videoDurationMs = durationMs,
                wallClockScanMs = wallClockMs,
                scanSpeedMultiple = throughput,
                candidateWindows = candidateWindows.size,
                coarseSampleCount = coarseFramesSeen,
                candidatesFound = candidateWindows.size,
                mode = scanMode.name,
                durationMs = durationMs,
                transitionsFound = merged.size,
                timing = timing,
                acceptedTransitions = acceptedTransitions,
                rejectedCandidates = rejectedCandidates,
                fallbackUsed = fallbackUsed || visualFallbackTransitions.isNotEmpty(),
                failureReason = if (visualFallbackTransitions.isNotEmpty()) {
                    "Provisional preview includes ${visualFallbackTransitions.size} non-confirmed transition(s); " +
                        "baselineFallback=$baselineVisualFallbackUsed; confirmed-only fixture QA is ineligible"
                } else null,
                metrics = ScanMetrics(
                    scannerVersion = "v3-stable-state-topology-ocr",
                    wallClockMs = wallClockMs,
                    baselineSamplesEstimate = baselineSamplesEstimate,
                    visualSamples = visualSamples,
                    ocrCalls = ocrCalls,
                    ocrReductionPercent = ocrReductionPercent,
                    throughputVideoToWall = throughput,
                    skippedStableMs = skippedStableMs,
                    acceptedTransitions = acceptedTransitions,
                    rejectedCandidates = rejectedCandidates,
                    candidateTimeouts = candidateTimeouts
                ),
                transitionMarks = transitionMarks,
                candidates = candidateWindows,
                checkpointTimeline = checkpointTimeline.toList(),
                recursiveStateEvidence = recursiveStateEvidence.toList(),
                plannedSegments = merged,
                candidateConfirmations = candidateConfirmationEvidence.toList(),
                transitionPlanPoints = transitionPlanPoints
            )
            latestScanReportPath = try {
                writeScanReport(report)
            } catch (failure: Exception) {
                recordHandledWorkerFailure(context, tag, "[finalize] scan report persistence failed", failure)
                throw failure
            }
            return@withContext ScanFindResult(
                merged,
                timing,
                transitionMarks,
                report.metrics,
                durationMs,
                candidateCount = candidateWindows.size,
                candidateTimestampsMs = candidateWindows.map { it.seedMs },
                scanBudgetReached = scanBudgetReached,
                visualFallbackUsed = visualFallbackTransitions.isNotEmpty(),
                confirmedTransitionCount = uniqueTransitions.size,
                provisionalTransitionCount = provisionalTransitions.size,
                rejectedTransitionCount = rejectedCandidates,
                completedCheckpointCount = checkpointTimeline.size,
                recursiveProbeCount = recursiveStateEvidence.size,
                semanticLeafCount = candidateWindowsForRefine.size,
                previewClassification = when {
                    provisionalTransitions.isNotEmpty() -> CompilationPreviewClassification.PROVISIONAL
                    uniqueTransitions.isNotEmpty() -> CompilationPreviewClassification.CONFIRMED
                    else -> CompilationPreviewClassification.NONE
                }
            )
        } finally {
            runCatching { frameProvider.close() }
                .onFailure { AppLog.w(context, tag, "Frame provider cleanup failed", it) }
            runCatching { retriever.release() }
                .onFailure { AppLog.w(context, tag, "Metadata retriever cleanup failed", it) }
        }
    }

    override fun close() {
        runCatching { digitRecognizer.close() }
            .onFailure { AppLog.w(context, tag, "Digit recognizer cleanup failed", it) }
    }

    private suspend fun detectNumberInRange(
        frameProvider: FrameProvider,
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        ocrFrameWidthPx: Int,
        timing: ScanTimingSummary
    ): TimedDetection {
        if (startMs > endMs) return TimedDetection(null, 0, null)
        val safeStepMs = stepMs.coerceAtLeast(100L)
        var cursor = startMs
        var calls = 0
        var singleHit: Int? = null
        var singleHitMs: Long? = null
        var timeoutCount = 0
        var failureCount = 0
        while (cursor <= endMs) {
            currentCoroutineContext().ensureActive()
            val evidence = detectNumberEvidenceAt(
                frameProvider = frameProvider,
                cursorMs = cursor,
                scanWindow = scanWindow,
                sourceRotationDegrees = sourceRotationDegrees,
                ocrFrameWidthPx = ocrFrameWidthPx,
                timing = timing
            )
            val value = evidence.value
            timeoutCount += evidence.timeoutCount
            failureCount += evidence.failureCount
            calls++
            if (value != null) {
                if (singleHit == value) {
                    return TimedDetection(value, calls, cursor, timeoutCount, failureCount)
                }
                if (singleHit == null || singleHit != value) {
                    singleHit = value
                    singleHitMs = cursor
                }
            }
            cursor += safeStepMs
        }
        return TimedDetection(singleHit, calls, singleHitMs, timeoutCount, failureCount)
    }

    /**
     * A checkpoint endpoint is a state sample, not a miniature candidate window.  Require a
     * majority across the endpoint and its immediate neighbours so a single OCR misread cannot
     * manufacture a transition.
     */
    private suspend fun detectStableNumberAt(
        frameProvider: FrameProvider,
        centerMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        ocrFrameWidthPx: Int,
        durationMs: Long,
        timing: ScanTimingSummary
    ): TimedDetection {
        val state = detectStableCheckpointState(
            frameProvider, centerMs, scanWindow, sourceRotationDegrees,
            ocrFrameWidthPx, durationMs, timing
        )
        return TimedDetection(
            state.state.value,
            state.calls,
            centerMs.takeIf { state.state.stable },
            state.timeoutCount,
            state.failureCount
        )
    }

    private suspend fun detectStableCheckpointState(
        frameProvider: FrameProvider,
        centerMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        ocrFrameWidthPx: Int,
        durationMs: Long,
        timing: ScanTimingSummary
    ): TimelineStateDetection {
        val sampleTimes = listOf(-1_000L, -500L, 0L, 500L, 1_000L)
            .map { (centerMs + it).coerceIn(0L, durationMs) }
            .distinct()
        val votes = ArrayList<StableStateVote>(sampleTimes.size)
        var calls = 0
        var timeoutCount = 0
        var failureCount = 0
        for (sampleMs in sampleTimes) {
            currentCoroutineContext().ensureActive()
            val evidence = detectNumberEvidenceAt(
                frameProvider, sampleMs, scanWindow, sourceRotationDegrees, ocrFrameWidthPx, timing
            )
            votes += StableStateVote(
                timestampMs = sampleMs,
                value = evidence.value,
                decodedTimestampMs = evidence.decodedTimestampMs,
                status = evidence.status,
                mlKitValue = evidence.mlKitValue,
                mlKitConfidence = evidence.mlKitConfidence,
                rawText = evidence.rawText,
                preprocessingBranch = evidence.preprocessingBranch,
                mlKitStructure = evidence.mlKitStructure,
                topology = evidence.topology,
                cropMs = evidence.cropMs,
                preprocessMs = evidence.preprocessMs,
                ocrMs = evidence.ocrMs
            )
            timeoutCount += evidence.timeoutCount
            failureCount += evidence.failureCount
            calls++
        }
        return TimelineStateDetection(
            state = classifyStableNumberState(votes),
            calls = calls,
            timeoutCount = timeoutCount,
            failureCount = failureCount
        )
    }

    private suspend fun investigateCheckpointCandidateIntervals(
        candidates: List<TransitionCandidateWindow>,
        frameProvider: FrameProvider,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        durationMs: Long,
        ocrFrameWidthPx: Int,
        timing: ScanTimingSummary
    ): CheckpointCandidateInvestigation {
        val output = ArrayList<TransitionCandidateWindow>()
        val cache = HashMap<Long, StableNumberState>()
        val stateEvidence = ArrayList<StateTimelineEvidenceEntry>()
        var calls = 0
        var probes = 0

        suspend fun stateAt(timeMs: Long): NumberStatePoint {
            val safeTimeMs = timeMs.coerceIn(0L, durationMs)
            val state = cache[safeTimeMs] ?: detectStableCheckpointState(
                frameProvider,
                safeTimeMs,
                scanWindow,
                sourceRotationDegrees,
                ocrFrameWidthPx,
                durationMs,
                timing
            ).also { detection ->
                calls += detection.calls
                cache[safeTimeMs] = detection.state
                stateEvidence += StateTimelineEvidenceEntry(
                    timeMs = safeTimeMs,
                    source = "recursive-candidate-probe",
                    state = detection.state
                )
            }.state
            return NumberStatePoint(safeTimeMs, state.value, state.stable)
        }

        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            // Endpoint states were already classified by the five-sample coarse timeline. Reusing
            // them avoids repeating up to ten decoded OCR frames for every visual-only interval.
            val left = NumberStatePoint(
                candidate.startMs,
                candidate.fromNumber,
                candidate.fromStateStable
            )
            val right = NumberStatePoint(
                candidate.endMs,
                candidate.toNumber,
                candidate.toStateStable
            )

            // Stable-equal endpoints cannot contain a valid monotonic Video A transition.
            if (left.stable && right.stable && left.value == right.value) continue
            val investigation = investigateStateInterval(
                left = left,
                right = right,
                minLeafMs = 2_000L,
                maxDepth = 6,
                maxProbes = checkpointInvestigationProbeLimit(
                    left.stable,
                    right.stable,
                    left.value,
                    right.value
                ),
                sample = { timeMs -> stateAt(timeMs) }
            )
            probes += investigation.probes
            investigation.intervals.forEach { interval ->
                output += candidate.copy(
                    startMs = interval.startMs,
                    endMs = interval.endMs,
                    seedMs = interval.startMs + (interval.endMs - interval.startMs) / 2L,
                    fromNumber = interval.fromNumber,
                    toNumber = interval.toNumber,
                    fromStateStable = true,
                    toStateStable = true
                )
            }

            // If an unstable midpoint prevented narrowing an already valid semantic pair, retain
            // the original complete interval for the exact-boundary pass.
            if (investigation.intervals.isEmpty()) {
                val semantic = classifyTransition(left.value.takeIf { left.stable }, right.value.takeIf { right.stable })
                if (left.stable && right.stable && semantic.sequential) {
                    output += candidate.copy(
                        fromNumber = left.value,
                        toNumber = right.value,
                        fromStateStable = true,
                        toStateStable = true
                    )
                }
            }
        }

        return CheckpointCandidateInvestigation(
            candidates = output.distinctBy { listOf(it.startMs, it.endMs, it.fromNumber, it.toNumber) }
                .sortedBy { it.startMs },
            calls = calls,
            probes = probes,
            stateEvidence = stateEvidence.sortedBy { it.timeMs }
        )
    }

    /** Refines a confirmed direct-checkpoint interval without decoding every intervening frame. */
    private suspend fun refineCheckpointTransitionBoundary(
        frameProvider: FrameProvider,
        startMs: Long,
        endMs: Long,
        afterNumber: Int,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        durationMs: Long,
        ocrFrameWidthPx: Int,
        timing: ScanTimingSummary
    ): TimedDetection {
        val boundary = refinePersistentStateBoundary(
            startMs = startMs,
            endMs = endMs,
            targetNumber = afterNumber,
            durationMs = durationMs,
            sample = { sampleMs -> detectNumberAt(
                frameProvider, sampleMs, scanWindow, sourceRotationDegrees, ocrFrameWidthPx, timing
            ) }
        )
        return TimedDetection(
            value = afterNumber.takeIf { boundary.timeMs != null },
            calls = boundary.samples,
            timeMs = boundary.timeMs
        )
    }

    private suspend fun refineTransitionBoundary(
        frameProvider: FrameProvider,
        startMs: Long,
        endMs: Long,
        beforeNumber: Int,
        afterNumber: Int,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        timing: ScanTimingSummary,
        sampleStepMs: Long,
        iterations: Int,
        windowMs: Long,
        durationMs: Long,
        ocrFrameWidthPx: Int
    ): TimedDetection {
        if (startMs >= endMs) return TimedDetection(null, 0, null)
        val lowMs = max(0L, startMs - windowMs)
        val highMs = min(durationMs, endMs + windowMs)
        val denseStepMs = sampleStepMs.coerceAtLeast(100L)
        val frames = frameProvider.decodeWindow(lowMs, highMs, denseStepMs, ocrFrameWidthPx)
        var calls = 0
        var earliestAfter: Long? = null
        var lastBefore: Long? = null

        try {
            for (decoded in frames) {
                currentCoroutineContext().ensureActive()
                try {
                    val detection = detectCornerNumberWithTimings(decoded.bitmap, scanWindow, sourceRotationDegrees)
                    calls++
                    timing.cropMs += detection.cropMs
                    timing.preprocessMs += detection.preprocessMs
                    timing.ocrMs += detection.ocrMs
                    when (detection.value) {
                        beforeNumber -> lastBefore = decoded.timeMs
                        afterNumber -> {
                            earliestAfter = decoded.timeMs
                            break
                        }
                    }
                } finally {
                    decoded.bitmap.recycle()
                }
            }
        } finally {
            frames.forEach { decoded ->
                if (!decoded.bitmap.isRecycled) decoded.bitmap.recycle()
            }
        }

        if (earliestAfter != null) return TimedDetection(afterNumber, calls, earliestAfter)

        var binaryCalls = calls
        var binaryLowMs = lastBefore ?: lowMs
        var binaryHighMs = highMs
        val fallbackMs = max(denseStepMs, (binaryHighMs - binaryLowMs).coerceAtLeast(1L) / 48L)
        repeat(iterations.coerceIn(4, 20)) {
            currentCoroutineContext().ensureActive()
            if (binaryHighMs - binaryLowMs <= fallbackMs) return@repeat
            val midMs = binaryLowMs + (binaryHighMs - binaryLowMs) / 2L
            val leftNumber = detectNumberAt(
                frameProvider = frameProvider,
                cursorMs = midMs,
                scanWindow = scanWindow,
                sourceRotationDegrees = sourceRotationDegrees,
                ocrFrameWidthPx = ocrFrameWidthPx,
                timing = timing
            )
            binaryCalls++
            if (leftNumber == afterNumber) {
                binaryHighMs = midMs
            } else {
                binaryLowMs = midMs
            }
        }
        return TimedDetection(afterNumber, binaryCalls, binaryHighMs)
    }

    private suspend fun locateFirstNumberAppearance(
        frameProvider: FrameProvider,
        startMs: Long,
        hitMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        targetNumber: Int,
        stepMs: Long,
        ocrFrameWidthPx: Int,
        timing: ScanTimingSummary
    ): TimedDetection {
        val safeStartMs = startMs.coerceAtLeast(0L)
        val safeHitMs = hitMs.coerceAtLeast(safeStartMs)
        val safeStepMs = stepMs.coerceAtLeast(250L)
        var cursorMs = safeStartMs
        var lastNotTargetMs = safeStartMs
        var calls = 0

        while (cursorMs <= safeHitMs) {
            currentCoroutineContext().ensureActive()
            val detected = detectNumberAt(
                frameProvider = frameProvider,
                cursorMs = cursorMs,
                scanWindow = scanWindow,
                sourceRotationDegrees = sourceRotationDegrees,
                ocrFrameWidthPx = ocrFrameWidthPx,
                timing = timing
            )
            calls++
            if (detected == targetNumber) {
                val refined = refineNumberAppearanceBetween(
                    frameProvider,
                    lastNotTargetMs,
                    cursorMs,
                    scanWindow,
                    sourceRotationDegrees,
                    targetNumber,
                    ocrFrameWidthPx,
                    timing
                )
                return TimedDetection(targetNumber, calls + refined.calls, refined.timeMs)
            }
            lastNotTargetMs = cursorMs
            val nextCursorMs = min(safeHitMs, cursorMs + safeStepMs)
            if (nextCursorMs == cursorMs) break
            cursorMs = nextCursorMs
        }
        return TimedDetection(null, calls, null)
    }

    private suspend fun refineNumberAppearanceBetween(
        frameProvider: FrameProvider,
        lowerBoundMs: Long,
        upperBoundMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        targetNumber: Int,
        ocrFrameWidthPx: Int,
        timing: ScanTimingSummary
    ): TimedDetection {
        var lowerMs = lowerBoundMs
        var upperMs = upperBoundMs
        var calls = 0

        repeat(8) {
            currentCoroutineContext().ensureActive()
            if (upperMs - lowerMs <= 250L) return@repeat
            val midMs = lowerMs + (upperMs - lowerMs) / 2L
            val detected = detectNumberAt(
                frameProvider = frameProvider,
                cursorMs = midMs,
                scanWindow = scanWindow,
                sourceRotationDegrees = sourceRotationDegrees,
                ocrFrameWidthPx = ocrFrameWidthPx,
                timing = timing
            )
            calls++
            if (detected == targetNumber) {
                upperMs = midMs
            } else {
                lowerMs = midMs
            }
        }
        return TimedDetection(targetNumber, calls, upperMs)
    }

    private suspend fun detectNumberAt(
        frameProvider: FrameProvider,
        cursorMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        ocrFrameWidthPx: Int = 0,
        timing: ScanTimingSummary
    ): Int? = detectNumberEvidenceAt(
        frameProvider, cursorMs, scanWindow, sourceRotationDegrees, ocrFrameWidthPx, timing
    ).value

    private suspend fun detectNumberEvidenceAt(
        frameProvider: FrameProvider,
        cursorMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        ocrFrameWidthPx: Int = 0,
        timing: ScanTimingSummary
    ): NumberDetectionEvidence {
        var decoded: DecodedFrame?
        val requestedFrameWidth = ocrFrameWidthPx.coerceAtLeast(0)
        val decodeStartedMs = SystemClock.elapsedRealtime()
        decoded = try {
            withTimeout(ConfirmationTimeoutPolicy.FRAME_EXTRACTION_MS) {
                frameProvider.frameAt(cursorMs, requestedFrameWidth)
            }
        } catch (timeout: TimeoutCancellationException) {
            val elapsedMs = SystemClock.elapsedRealtime() - decodeStartedMs
            timing.decodeMs += elapsedMs
            AppLog.w(
                context,
                tag,
                "[frame] requestedTimestampMs=$cursorMs requestedWidth=$requestedFrameWidth " +
                    "disposition=frame_extraction_timeout timeoutSource=frame_extraction " +
                    "limitMs=${ConfirmationTimeoutPolicy.FRAME_EXTRACTION_MS} elapsedMs=$elapsedMs"
            )
            return NumberDetectionEvidence(
                value = null,
                decodedTimestampMs = null,
                status = OcrCandidateStatus.FRAME_TIMEOUT.name,
                timeoutCount = 1
            )
        }
        timing.decodeMs += SystemClock.elapsedRealtime() - decodeStartedMs
        val localDecoded = decoded ?: return NumberDetectionEvidence(
            value = null,
            decodedTimestampMs = null,
            status = OcrCandidateStatus.INVALID_FRAME.name,
            failureCount = 1
        )
        val localFrame = localDecoded.bitmap
        var selectedDecodedTimestampMs = localDecoded.timeMs

        suspend fun detectWithFrameWidth(candidateFrame: Bitmap): NumberDetectionResult {
            val detection = detectCornerNumberWithTimings(candidateFrame, scanWindow, sourceRotationDegrees)
            timing.cropMs += detection.cropMs
            timing.preprocessMs += detection.preprocessMs
            timing.ocrMs += detection.ocrMs
            return detection
        }

        return try {
            var detection = detectWithFrameWidth(localFrame)
            var nestedTimeoutCount = 0
            if (
                detection.value == null ||
                (detection.confidence < 0.85f && requestedFrameWidth in 1..480)
            ) {
                val retryWidth = if (requestedFrameWidth == 0) 480 else min(requestedFrameWidth * 2, 640)
                // At the maximum OCR width a retry would be the same seek and the same pixels;
                // it only burns the direct-timeline budget without adding evidence.
                if (retryWidth > requestedFrameWidth) {
                    val fallbackDecodeStartedMs = SystemClock.elapsedRealtime()
                    decoded = try {
                        withTimeout(ConfirmationTimeoutPolicy.FRAME_EXTRACTION_MS) {
                            frameProvider.frameAt(cursorMs, retryWidth)
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        val elapsedMs = SystemClock.elapsedRealtime() - fallbackDecodeStartedMs
                        nestedTimeoutCount++
                        AppLog.w(
                            context,
                            tag,
                            "[frame] requestedTimestampMs=$cursorMs requestedWidth=$retryWidth " +
                                "disposition=frame_retry_timeout timeoutSource=frame_extraction " +
                                "limitMs=${ConfirmationTimeoutPolicy.FRAME_EXTRACTION_MS} elapsedMs=$elapsedMs partialEvidence=true"
                        )
                        null
                    }
                    timing.decodeMs += SystemClock.elapsedRealtime() - fallbackDecodeStartedMs
                    val fallbackFrame = decoded?.bitmap
                    if (fallbackFrame != null && fallbackFrame !== localFrame) {
                        val fallbackDetection = try {
                            detectWithFrameWidth(fallbackFrame)
                        } finally {
                            fallbackFrame.recycle()
                        }
                        if (numberDetectionScore(fallbackDetection) >= numberDetectionScore(detection)) {
                            detection = fallbackDetection
                            selectedDecodedTimestampMs = decoded?.timeMs ?: selectedDecodedTimestampMs
                        }
                    }
                }
            }
            NumberDetectionEvidence(
                value = detection.value,
                decodedTimestampMs = selectedDecodedTimestampMs,
                status = detection.status.name,
                mlKitValue = detection.mlKitValue,
                mlKitConfidence = detection.confidence,
                rawText = detection.rawText,
                preprocessingBranch = detection.branch,
                mlKitStructure = detection.mlKitStructure,
                topology = detection.topology,
                cropMs = detection.cropMs,
                preprocessMs = detection.preprocessMs,
                ocrMs = detection.ocrMs,
                timeoutCount = nestedTimeoutCount + if (
                    detection.status == OcrCandidateStatus.OCR_TIMEOUT
                ) 1 else 0,
                failureCount = if (
                    detection.status == OcrCandidateStatus.OCR_UNAVAILABLE ||
                    detection.status == OcrCandidateStatus.INVALID_FRAME
                ) 1 else 0
            )
        } finally {
            localFrame.recycle()
        }
    }

    suspend fun getDurationMs(sourceUri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever().apply {
            setDataSource(context, sourceUri)
        }
        return@withContext try {
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    suspend fun renderCompilation(
        sourceUri: Uri,
        segments: List<SegmentWindow>,
        quality: ExportQuality,
        format: ExportFormat,
        transitionStyle: TransitionStyle,
        outputFile: File? = null,
        quickMode: Boolean = false,
        progress: (String, Int) -> Unit
    ): VerifiedCompilationOutput = withContext(Dispatchers.IO) {
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format

        if (segments.isEmpty()) throw IllegalStateException("No segments to assemble")
        val output = outputFile ?: File(
            File(context.filesDir, "compilations").apply { mkdirs() },
            "compilation_${System.currentTimeMillis()}.${safeFormat.extension}"
        )
        output.parentFile?.mkdirs()
        val exportStarted = SystemClock.elapsedRealtime()
        AppLog.i(context, tag, "[export] beginning thread=${Thread.currentThread().name}")
        AppLog.i(context, tag, "[export] source URI=$sourceUri thread=${Thread.currentThread().name}")
        AppLog.i(context, tag, "[export] destination URI/path=${output.absolutePath} thread=${Thread.currentThread().name}")
        try {
            progress("Using ${quality.label} profile with exact semantic cuts", 56)
            progress("Preparing exact Media3 composition", 57)
            val exactSegments = mergeOverlapping(segments.sortedBy { it.startMs })
            val expectedDurationMs = expectedCompilationDurationMs(exactSegments)
            AppLog.i(
                context,
                tag,
                "[export] exporter=media3-transformer-1.9.3 style=${transitionStyle.label} stylePaddingApplied=false " +
                    "segments=${exactSegments.size} expectedDurationMs=$expectedDurationMs thread=${Thread.currentThread().name}"
            )
            try {
                withTimeout(EXPORT_TIMEOUT_MS) {
                    Media3CompilationExporter(context).export(sourceUri, exactSegments, output, quickMode) { message, percent ->
                        AppLog.i(context, tag, "[export] progress $percent%: $message thread=${Thread.currentThread().name}")
                        progress(message, percent)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("Export timed out after ${EXPORT_TIMEOUT_MS}ms", timeout)
            }
            AppLog.i(
                context,
                tag,
                "[export] completed in ${SystemClock.elapsedRealtime() - exportStarted} ms thread=${Thread.currentThread().name}"
            )
            val verified = verifyCompilationOutput(output)
            val durationDeltaMs = kotlin.math.abs(verified.durationMs - expectedDurationMs)
            check(durationDeltaMs <= OUTPUT_DURATION_TOLERANCE_MS) {
                "Export duration ${verified.durationMs}ms differs from exact clip plan ${expectedDurationMs}ms by ${durationDeltaMs}ms"
            }
            progress("Compilation ready to save", 94)
            return@withContext verified
        } catch (cancelled: CancellationException) {
            if (output.exists()) {
                runCatching { check(output.delete()) { "Unable to delete partial output ${output.absolutePath}" } }
                    .onFailure { AppLog.w(context, tag, "Cancellation cleanup failed", it) }
            }
            throw cancelled
        } catch (e: Exception) {
            recordHandledWorkerFailure(context, tag, "[export] failed after ${SystemClock.elapsedRealtime() - exportStarted} ms", e)
            if (output.exists()) {
                runCatching { output.delete() }
                    .onFailure { AppLog.w(context, tag, "Partial output cleanup failed: ${output.absolutePath}", it) }
            }
            throw e
        }
    }

    suspend fun verifyCompilationOutput(output: File): VerifiedCompilationOutput = withContext(Dispatchers.IO) {
        val verifyStarted = SystemClock.elapsedRealtime()
        AppLog.i(context, tag, "[verify] beginning thread=${Thread.currentThread().name}")
        AppLog.i(context, tag, "[verify] output exists=${output.exists()} thread=${Thread.currentThread().name}")
        AppLog.i(context, tag, "[verify] output size=${if (output.exists()) output.length() else 0L} bytes thread=${Thread.currentThread().name}")
        try {
            val verified = withTimeout(VERIFY_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) { validateExportOutput(output) }
            }
            AppLog.i(context, tag, "[verify] output duration=${verified.durationMs} ms thread=${Thread.currentThread().name}")
            AppLog.i(
                context,
                tag,
                "[verify] completed in ${SystemClock.elapsedRealtime() - verifyStarted} ms thread=${Thread.currentThread().name}"
            )
            verified
        } catch (timeout: TimeoutCancellationException) {
            val failure = IllegalStateException("Output verification timed out after ${VERIFY_TIMEOUT_MS}ms", timeout)
            recordHandledWorkerFailure(context, tag, "[verify] failed", failure)
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordHandledWorkerFailure(context, tag, "[verify] failed", failure)
            throw failure
        }
    }

    private suspend fun detectCornerNumber(frame: Bitmap, scanWindow: ScanWindow): Int? {
        val corner = try {
            if (frame.width <= 0 || frame.height <= 0) {
                return null
            }
            cropToWindowForOcr(frame, scanWindow)
        } catch (e: Exception) {
            AppLog.w(context, tag, "Failed to crop scan area", e)
            null
        } ?: return null

        val scaled = scaleBitmapForOcr(corner)
        val gray = grayscaleBitmap(scaled)
        val threshold = binarizeForDigitRecognition(gray, invert = false)
        val inverted = binarizeForDigitRecognition(gray, invert = true)
        return try {
            val attempts = listOf(
                scaled to "raw",
                gray to "grayscale",
                threshold to "threshold",
                inverted to "inverted-threshold"
            )
            var best = DigitRecognition(null, "", 0f, "raw")
            for ((bitmap, branch) in attempts) {
                val detection = digitRecognizer.recognize(bitmap, branch)
                if (detection.value != null && detection.confidence >= best.confidence) {
                    best = detection
                } else if (best.value == null && detection.value != null) {
                    best = detection
                } else if (best.value == null && detection.confidence > best.confidence) {
                    best = detection
                }
                if (best.value != null && best.confidence >= 0.95f) {
                    break
                }
            }
            best.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: OcrRecognitionTimeoutException) {
            recordHandledWorkerFailure(context, tag, "[finalize] OCR recognition timed out", timeout)
            throw timeout
        } catch (e: Exception) {
            AppLog.w(context, tag, "Skipping OCR frame after vision input failure: ${e::class.java.simpleName}: ${e.message}", e)
            null
        } finally {
            if (scaled != corner) {
                scaled.recycle()
            }
            if (gray !== scaled) {
                gray.recycle()
            }
            if (threshold !== gray) {
                threshold.recycle()
            }
            if (inverted !== gray) {
                inverted.recycle()
            }
            corner.recycle()
        }
    }

    private suspend fun detectCornerNumberWithTimings(
        frame: Bitmap,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int
    ): NumberDetectionResult {
        val normalizedFrame = if (sourceRotationDegrees == 0) frame else normalizeBitmapForOcr(frame, sourceRotationDegrees)
        val startedCropMs = System.currentTimeMillis()
        val corner = try {
            if (normalizedFrame.width <= 0 || normalizedFrame.height <= 0) return NumberDetectionResult(null, 0L, 0L, 0L, 0f)
            cropToWindowForOcr(normalizedFrame, scanWindow)
        } catch (e: Exception) {
            AppLog.w(context, tag, "Failed to crop scan area", e)
            if (normalizedFrame !== frame) {
                normalizedFrame.recycle()
            }
            return NumberDetectionResult(null, 0L, 0L, 0L, 0f)
        }
        val cropMs = System.currentTimeMillis() - startedCropMs

        val preprocessStartMs = System.currentTimeMillis()
        val scaled = scaleBitmapForOcr(corner)
        var preprocessMs = System.currentTimeMillis() - preprocessStartMs
        val temporaries = ArrayList<Bitmap>(3)

        return try {
            val attempts = listOf<(Bitmap) -> Pair<Bitmap, String>>(
                { source -> source to "raw" },
                { source -> grayscaleBitmap(source).also(temporaries::add) to "grayscale" },
                { source -> binarizeForDigitRecognition(source, invert = false).also(temporaries::add) to "threshold" },
                { source -> binarizeForDigitRecognition(source, invert = true).also(temporaries::add) to "inverted-threshold" }
            )
            var best: NumberDetectionResult? = null
            var totalOcrMs = 0L
            for ((attemptIndex, prepare) in attempts.withIndex()) {
                val prepareStarted = System.currentTimeMillis()
                val (bitmap, branch) = prepare(scaled)
                preprocessMs += System.currentTimeMillis() - prepareStarted
                AppLog.d(context, tag, "[ocr] prepared dimensions=${bitmap.width}x${bitmap.height} variant=$branch")
                val branchStart = System.currentTimeMillis()
                val detection = digitRecognizer.recognize(bitmap, branch)
                val branchDuration = System.currentTimeMillis() - branchStart
                totalOcrMs += branchDuration
                val adjudication = adjudicateDigitRecognition(detection, scaled)
                val attemptResult = NumberDetectionResult(
                    value = adjudication.value,
                    cropMs = cropMs,
                    preprocessMs = preprocessMs,
                    ocrMs = branchDuration,
                    confidence = detection.confidence,
                    rawText = detection.rawText,
                    branch = detection.branch,
                    status = adjudication.status,
                    mlKitValue = detection.value,
                    mlKitStructure = detection.structure,
                    topology = adjudication.topology
                )
                AppLog.d(
                    context,
                    tag,
                    "[ocr] variant=$branch durationMs=$branchDuration mlKit=${detection.value ?: "none"} confidence=${detection.confidence} topology=${adjudication.topology?.decision ?: SixNineDecision.NOT_APPLICABLE} final=${attemptResult.value ?: "none"} status=${attemptResult.status}"
                )
                val currentBest = best
                if (currentBest == null || numberDetectionScore(attemptResult) > numberDetectionScore(currentBest)) {
                    best = attemptResult
                }
                val chosen = best ?: attemptResult
                if (
                    attemptResult.value != null &&
                    (detection.value == 6 || detection.value == 9) &&
                    adjudication.topology?.confidence?.let { it >= SixNineTopologyPolicy.MIN_OVERRIDE_CONFIDENCE } == true
                ) break
                val tryNext = attemptResult.status == OcrCandidateStatus.AMBIGUOUS_TOPOLOGY ||
                    shouldTryNextOcrVariant(attemptIndex, detection)
                if (!tryNext) break
                if (attemptIndex == 2 && chosen.value != null) break
            }
            (best ?: NumberDetectionResult(null, cropMs, preprocessMs, totalOcrMs, 0f)).copy(
                preprocessMs = preprocessMs,
                ocrMs = totalOcrMs
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: OcrRecognitionTimeoutException) {
            AppLog.w(context, tag, "[ocr] candidate OCR timed out after controlled retry; skipping candidate", timeout)
            NumberDetectionResult(null, cropMs, preprocessMs, 0L, 0f, "", "timeout", OcrCandidateStatus.OCR_TIMEOUT)
        } catch (e: Exception) {
            AppLog.w(context, tag, "Skipping OCR frame after vision input failure: ${e::class.java.simpleName}: ${e.message}", e)
            NumberDetectionResult(null, cropMs, preprocessMs, 0L, 0f, "", "error", OcrCandidateStatus.OCR_UNAVAILABLE)
        } finally {
            if (scaled != corner) {
                scaled.recycle()
            }
            temporaries.asReversed().forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            corner.recycle()
            if (normalizedFrame !== frame) {
                normalizedFrame.recycle()
            }
        }
    }

    private fun adjudicateDigitRecognition(
        recognition: DigitRecognition,
        topologySource: Bitmap
    ): OcrAdjudication {
        val mlKitValue = recognition.value
        if (mlKitValue == null) {
            val status = when (recognition.status) {
                DigitRecognitionStatus.NO_TEXT -> OcrCandidateStatus.NO_TEXT
                DigitRecognitionStatus.NO_VALID_INTEGER -> OcrCandidateStatus.INVALID_INTEGER
                DigitRecognitionStatus.TIMEOUT -> OcrCandidateStatus.OCR_TIMEOUT
                DigitRecognitionStatus.ML_KIT_FAILURE -> OcrCandidateStatus.OCR_UNAVAILABLE
                DigitRecognitionStatus.PARSED -> OcrCandidateStatus.INVALID_INTEGER
            }
            return OcrAdjudication(null, status, null)
        }
        if (!shouldAdjudicateSixOrNine(mlKitValue)) {
            return OcrAdjudication(mlKitValue, OcrCandidateStatus.CONFIRMED_TRANSITION, null)
        }

        val plane = extractTopologyLuma(topologySource, recognition.structure?.primaryGlyphBounds())
        val topology = plane?.let { classifySixOrNine(it.values, it.width, it.height) }
        val finalValue = adjudicateSixOrNineValue(mlKitValue, topology)
        return if (finalValue != null) {
            OcrAdjudication(finalValue, OcrCandidateStatus.CONFIRMED_TRANSITION, topology)
        } else {
            OcrAdjudication(null, OcrCandidateStatus.AMBIGUOUS_TOPOLOGY, topology)
        }
    }

    private fun numberDetectionScore(result: NumberDetectionResult): Float {
        val disposition = when (result.status) {
            OcrCandidateStatus.CONFIRMED_TRANSITION -> 2f
            OcrCandidateStatus.AMBIGUOUS_TOPOLOGY -> 1f
            OcrCandidateStatus.INVALID_INTEGER -> 0.4f
            OcrCandidateStatus.NO_TEXT, OcrCandidateStatus.NO_TRANSITION -> 0.2f
            OcrCandidateStatus.OCR_UNAVAILABLE,
            OcrCandidateStatus.OCR_TIMEOUT,
            OcrCandidateStatus.FRAME_TIMEOUT,
            OcrCandidateStatus.INVALID_FRAME -> 0f
        }
        return disposition + result.confidence.coerceIn(0f, 1f) * 0.25f +
            (result.topology?.confidence ?: 0f) * 0.25f
    }

    private fun createSafeInputImage(bitmap: Bitmap, label: String): InputImage {
        require(bitmap.width >= MIN_INPUT_IMAGE_DIMENSION && bitmap.height >= MIN_INPUT_IMAGE_DIMENSION) {
            "$label bitmap ${bitmap.width}x${bitmap.height} is smaller than ML Kit minimum ${MIN_INPUT_IMAGE_DIMENSION}x${MIN_INPUT_IMAGE_DIMENSION}"
        }
        return InputImage.fromBitmap(bitmap, 0)
    }

    private fun normalizeBitmapForOcr(source: Bitmap, rotationDegrees: Int): Bitmap {
        return when (((rotationDegrees % 360) + 360) % 360) {
            0 -> source
            else -> {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
        }
    }

    private fun getAnalysisFrameAtTime(
        retriever: MediaMetadataRetriever,
        cursorMs: Long,
        targetWidthPx: Int = 0,
        sourceRotationDegrees: Int = 0,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0
    ): Bitmap? {
        return try {
            val timeUs = cursorMs * 1000L
            val decodeWidth = targetWidthPx.coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && decodeWidth > 0) {
                val decodeHeight = scaledHeightForWidth(decodeWidth, sourceRotationDegrees, sourceWidth, sourceHeight)
                if (decodeHeight > 0) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        decodeWidth,
                        decodeHeight
                    ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } else {
                    null
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        640,
                        360
                    ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            }
        } catch (e: Exception) {
            AppLog.w(context, tag, "Frame fetch failed at ${cursorMs}ms", e)
            null
        }
    }

    private inner class RetrieverFrameProvider(
        private val retriever: MediaMetadataRetriever,
        private val sourceRotationDegrees: Int,
        private val sourceWidth: Int,
        private val sourceHeight: Int
    ) : FrameProvider {
        override suspend fun frameAt(timeMs: Long, targetWidthPx: Int): DecodedFrame? {
            return try {
                val bitmap = getAnalysisFrameAtTime(
                    retriever = retriever,
                    cursorMs = timeMs,
                    targetWidthPx = targetWidthPx,
                    sourceRotationDegrees = sourceRotationDegrees,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight
                ) ?: return null
                DecodedFrame(timeMs, bitmap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.w(context, tag, "Frame decode failed at $timeMs", e)
                null
            }
        }

        override suspend fun decodeWindow(
            startMs: Long,
            endMs: Long,
            sampleEveryMs: Long,
            targetWidthPx: Int
        ): List<DecodedFrame> {
            val frames = ArrayList<DecodedFrame>()
            forEachFrameInWindow(startMs, endMs, sampleEveryMs, targetWidthPx) { frame ->
                frames.add(frame)
                FrameIterationDecision.CONTINUE
            }
            return frames
        }

        override suspend fun forEachFrameInWindow(
            startMs: Long,
            endMs: Long,
            sampleEveryMs: Long,
            targetWidthPx: Int,
            onFrame: suspend (DecodedFrame) -> FrameIterationDecision
        ) {
            if (startMs > endMs) return
            val safeStepMs = sampleEveryMs.coerceAtLeast(50L)
            val checkpoints = generateCheckpointTimestamps(endMs.coerceAtLeast(startMs), safeStepMs)
                .map { (startMs.coerceAtLeast(0L) + it).coerceAtMost(endMs) }
                .distinct()
            check(checkpoints.size <= ((endMs - startMs).coerceAtLeast(0L) / safeStepMs) + 2) {
                "Coarse checkpoint plan enumerated intervening frames"
            }
            checkpoints.forEachIndexed { index, cursorMs ->
                currentCoroutineContext().ensureActive()
                val checkpointStarted = SystemClock.elapsedRealtime()
                val decoded = frameAt(cursorMs, targetWidthPx)
                if (decoded != null) {
                    val actualStarted = SystemClock.elapsedRealtime()
                    val decision = onFrame(decoded)
                    AppLog.d(context, tag, "checkpoint ${index + 1} of ${checkpoints.size} requested timestamp=${cursorMs} actual frame timestamp=${decoded.timeMs} seek duration=${actualStarted - checkpointStarted} ms decode duration=${SystemClock.elapsedRealtime() - actualStarted} ms ROI crop duration=logged by caller signature duration=logged by caller total checkpoint duration=${SystemClock.elapsedRealtime() - checkpointStarted} ms")
                    if (shouldStopFrameIteration(decision)) return
                }
            }
        }

        override fun close() = Unit
    }

    private fun selectFrameProvider(
        retrieverFrameProvider: RetrieverFrameProvider,
        sourceUri: Uri,
        sourceRotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        durationMs: Long
    ): FrameProviderSelection {
        return try {
            val codecProvider = MediaCodecFrameProvider(
                sourceUri = sourceUri,
                sourceRotationDegrees = sourceRotationDegrees,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                durationMs = durationMs
            )
            FrameProviderSelection(codecProvider, codecProvider::class.simpleName ?: "MediaCodecFrameProvider")
        } catch (e: Exception) {
            AppLog.w(context, tag, "MediaCodec provider unavailable; using retriever fallback", e)
            FrameProviderSelection(
                provider = retrieverFrameProvider,
                providerLabel = retrieverFrameProvider::class.simpleName ?: "RetrieverFrameProvider",
                fallbackReason = e.message ?: e::class.java.simpleName
            )
        }
    }

    private inner class MediaCodecFrameProvider(
        private val sourceUri: Uri,
        private val sourceRotationDegrees: Int,
        private val sourceWidth: Int,
        private val sourceHeight: Int,
        private val durationMs: Long
    ) : FrameProvider {
        override suspend fun frameAt(timeMs: Long, targetWidthPx: Int): DecodedFrame? {
            val windowStart = max(0L, timeMs - 750L)
            val windowEnd = timeMs + 750L
            val frames = decodeWindow(
                windowStart,
                windowEnd,
                max(125L, targetWidthPx.takeIf { it > 0 }?.let { 250L } ?: 250L),
                targetWidthPx
            )
            val selected = frames.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
            frames.forEach { decoded ->
                if (decoded !== selected) decoded.bitmap.recycle()
            }
            return selected
        }

        override suspend fun forEachFrameInWindow(
            startMs: Long,
            endMs: Long,
            sampleEveryMs: Long,
            targetWidthPx: Int,
            onFrame: suspend (DecodedFrame) -> FrameIterationDecision
        ) {
            if (startMs > endMs) return
            val safeStartUs = max(0L, startMs) * 1000L
            val safeEndUs = max(safeStartUs, endMs * 1000L)
            val safeStepUs = max(50L, sampleEveryMs.coerceAtLeast(50L)) * 1000L
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var imageReader: ImageReader? = null
            try {
                extractor.setDataSource(context, sourceUri, null)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IllegalStateException("No video track found")
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Missing video MIME")
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val outputWidth = if (targetWidthPx > 0) targetWidthPx else width
                val outputHeight = if (targetWidthPx > 0 && width > 0) {
                    max(1, (height.toFloat() * (outputWidth.toFloat() / width.toFloat())).toInt())
                } else {
                    height
                }
                extractor.selectTrack(trackIndex)
                extractor.seekTo(safeStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, imageReader.surface, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var endOfWindowSampleQueued = false
                var nextSampleUs = safeStartUs
                var lastCodecProgressMs = SystemClock.elapsedRealtime()

                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() - lastCodecProgressMs >= DECODER_STALL_TIMEOUT_MS) {
                        throw IllegalStateException(
                            "MediaCodec produced no input/output progress for ${DECODER_STALL_TIMEOUT_MS}ms " +
                                "while decoding ${startMs}ms..${endMs}ms"
                        )
                    }
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer == null) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inputBuffer.clear()
                                if (endOfWindowSampleQueued) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                    val sampleTimeUs = if (sampleSize >= 0) extractor.sampleTime else -1L
                                    when (decoderInputAction(false, sampleSize >= 0, sampleTimeUs, safeEndUs)) {
                                        DecoderInputAction.QUEUE_END_OF_STREAM -> {
                                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            inputDone = true
                                        }
                                        DecoderInputAction.QUEUE_SAMPLE,
                                        DecoderInputAction.QUEUE_BOUNDARY_SAMPLE -> {
                                            codec.queueInputBuffer(
                                                inputIndex,
                                                0,
                                                sampleSize,
                                                sampleTimeUs,
                                                extractorSampleFlagsToBufferFlags(extractor.sampleFlags)
                                            )
                                            extractor.advance()
                                            if (sampleTimeUs >= safeEndUs) {
                                                endOfWindowSampleQueued = true
                                            }
                                        }
                                    }
                                }
                                lastCodecProgressMs = SystemClock.elapsedRealtime()
                                if (inputDone) {
                                    // The output loop now has a real EOS buffer to drain.
                                }
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outputIndex >= 0 -> {
                            val render = info.size > 0
                            codec.releaseOutputBuffer(outputIndex, render)
                            if (render) {
                                val image = imageReader.acquireLatestImage()
                            if (image != null) {
                                try {
                                    val presentationUs = info.presentationTimeUs
                                    if (presentationUs in safeStartUs..safeEndUs && presentationUs >= nextSampleUs) {
                                        val bitmap = imageToBitmap(image)
                                            val normalized = if (sourceRotationDegrees == 0) bitmap else normalizeBitmapForOcr(bitmap, sourceRotationDegrees)
                                            if (normalized !== bitmap) {
                                                bitmap.recycle()
                                            }
                                            val finalBitmap = if (targetWidthPx > 0 && normalized.width > targetWidthPx) {
                                                val scaled = Bitmap.createScaledBitmap(normalized, targetWidthPx, max(1, outputHeight), true)
                                            if (scaled !== normalized) {
                                                normalized.recycle()
                                            }
                                            scaled
                                        } else {
                                            normalized
                                        }
                                            val decision = onFrame(DecodedFrame(presentationUs / 1000L, finalBitmap, presentationUs))
                                            nextSampleUs = presentationUs + safeStepUs
                                            if (shouldStopFrameIteration(decision)) {
                                                outputDone = true
                                            }
                                        } else {
                                            val bitmap = imageToBitmap(image)
                                            if (bitmap.width > 0 && bitmap.height > 0) {
                                                bitmap.recycle()
                                            }
                                        }
                                    } finally {
                                        image.close()
                                    }
                                }
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                            lastCodecProgressMs = SystemClock.elapsedRealtime()
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            lastCodecProgressMs = SystemClock.elapsedRealtime()
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // A finite watchdog above handles codecs that never return the queued EOS.
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.w(context, tag, "MediaCodec frame provider failed; will fall back to retriever", e)
                throw e
            } finally {
                runCatching { codec?.stop() }
                    .onFailure { AppLog.w(context, tag, "Decoder stop cleanup failed", it) }
                runCatching { codec?.release() }
                    .onFailure { AppLog.w(context, tag, "Decoder release cleanup failed", it) }
                runCatching { imageReader?.close() }
                    .onFailure { AppLog.w(context, tag, "Decoder image reader cleanup failed", it) }
                runCatching { extractor.release() }
                    .onFailure { AppLog.w(context, tag, "Decoder extractor cleanup failed", it) }
            }
        }

        override fun close() = Unit
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val stream = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 100, stream)
        val bytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Unable to decode YUV image")
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)
        val planes = image.planes

        var outputOffset = 0
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            yBuffer.position(rowStart)
            val length = minOf(yRowStride, yBuffer.remaining())
            yBuffer.get(yRow, 0, length)
            var col = 0
            while (col < width) {
                nv21[outputOffset++] = yRow[col * yPixelStride]
                col++
            }
        }

        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            uBuffer.position(uRowStart)
            vBuffer.position(vRowStart)
            val uLength = minOf(uRowStride, uBuffer.remaining())
            val vLength = minOf(vRowStride, vBuffer.remaining())
            uBuffer.get(uRow, 0, uLength)
            vBuffer.get(vRow, 0, vLength)
            var col = 0
            while (col < chromaWidth) {
                val u = uRow[col * uPixelStride]
                val v = vRow[col * vPixelStride]
                nv21[outputOffset++] = v
                nv21[outputOffset++] = u
                col++
            }
        }
        return nv21
    }

    private fun computeRoiSignature(frame: Bitmap, scanWindow: ScanWindow, sourceRotationDegrees: Int): RoiSignature {
        if (frame.width <= 0 || frame.height <= 0) return RoiSignature(IntArray(256), 0, 0f, 0f, 0f, 0L)
        val normalizedFrame = if (sourceRotationDegrees == 0) frame else normalizeBitmapForOcr(frame, sourceRotationDegrees)
        return try {
            if (scanWindow.widthPercent <= 0f || scanWindow.heightPercent <= 0f) return RoiSignature(IntArray(256), 0, 0f, 0f, 0f, 0L)
            val startX = (normalizedFrame.width * scanWindow.xPercent).roundToInt().coerceIn(0, normalizedFrame.width - 1)
            val startY = (normalizedFrame.height * scanWindow.yPercent).roundToInt().coerceIn(0, normalizedFrame.height - 1)
            val roiWidth = (normalizedFrame.width * scanWindow.widthPercent).roundToInt().coerceIn(1, normalizedFrame.width - startX)
            val roiHeight = (normalizedFrame.height * scanWindow.heightPercent).roundToInt().coerceIn(1, normalizedFrame.height - startY)
            val gridSize = 16
            val grid = IntArray(gridSize * gridSize)
            var total = 0
            var totalLuma = 0f
            var index = 0
            for (gy in 0 until gridSize) {
                val y = (startY + (gy + 0.5f) * roiHeight / gridSize.toFloat()).roundToInt().coerceIn(0, normalizedFrame.height - 1)
                for (gx in 0 until gridSize) {
                    val x = (startX + (gx + 0.5f) * roiWidth / gridSize.toFloat()).roundToInt().coerceIn(0, normalizedFrame.width - 1)
                    val pixel = normalizedFrame.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    val luma = (r * 30 + g * 59 + b * 11) / 100
                    grid[index++] = luma
                    total += luma
                    totalLuma += luma.toFloat()
                }
            }
            val average = total / grid.size
            var contrast = 0f
            var edge = 0f
            var occupancy = 0f
            var hash = 0L
            val avgFloat = totalLuma / max(1, grid.size).toFloat()
            val occupancyThreshold = avgFloat + 15f
            for (i in grid.indices) {
                contrast += kotlin.math.abs(grid[i] - avgFloat)
                if (grid[i] > occupancyThreshold) {
                    occupancy += 1f
                }
                val x = i % gridSize
                val y = i / gridSize
                if (x + 1 < gridSize) {
                    edge += kotlin.math.abs(grid[i] - grid[i + 1]).toFloat()
                }
                if (y + 1 < gridSize) {
                    edge += kotlin.math.abs(grid[i] - grid[i + gridSize]).toFloat()
                }
                if (i < 64) {
                    if (grid[i] > avgFloat) {
                        hash = hash or (1L shl i)
                    }
                }
            }
            val contrastScore = if (grid.isNotEmpty()) contrast / grid.size.toFloat() else 0f
            val edgeScore = if (grid.isNotEmpty()) edge / max(1f, (gridSize * (gridSize - 1) * 2f)) else 0f
            RoiSignature(grid, average, contrastScore, edgeScore, occupancy / grid.size.toFloat(), hash)
        } catch (e: Exception) {
            AppLog.w(context, tag, "Could not build ROI signature", e)
            RoiSignature(IntArray(256), 0, 0f, 0f, 0f, 0L)
        } finally {
            if (normalizedFrame !== frame) {
                normalizedFrame.recycle()
            }
        }
    }

    private fun scaledHeightForWidth(
        targetWidthPx: Int,
        sourceRotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Int {
        val width = targetWidthPx.coerceAtLeast(1)
        val sourceW = if (sourceRotationDegrees == 90 || sourceRotationDegrees == 270) sourceHeight else sourceWidth
        val sourceH = if (sourceRotationDegrees == 90 || sourceRotationDegrees == 270) sourceWidth else sourceHeight
        if (sourceW <= 0 || sourceH <= 0) return 0
        if (width >= sourceW) return 0
        val scale = width.toFloat() / sourceW.toFloat()
        return (sourceH.toFloat() * scale).toInt().coerceAtLeast(1)
    }

    private fun roiDifference(previous: RoiSignature, current: RoiSignature): Float {
        var sampleScore = 0f
        val sampleCount = min(previous.samples.size, current.samples.size)
        val loopEnd = min(sampleCount, 64)
        for (i in 0 until loopEnd) {
            sampleScore += kotlin.math.abs(previous.samples[i] - current.samples[i]).toFloat() / 255f
        }
        val sampleDelta = if (loopEnd > 0) sampleScore / loopEnd.toFloat() * 100f else 0f
        val avgDelta = kotlin.math.abs(previous.average - current.average).toFloat()
        val contrastDelta = kotlin.math.abs(previous.contrast - current.contrast) * 100f
        val edgeDelta = kotlin.math.abs(previous.edgeEnergy - current.edgeEnergy) * 100f
        val occupancyDelta = kotlin.math.abs(previous.occupancy - current.occupancy) * 100f
        val hashDelta = java.lang.Long.bitCount(previous.hash xor current.hash).toFloat() / 64f * 100f

        return sampleDelta * 0.52f +
            avgDelta * 0.06f +
            contrastDelta * 0.12f +
            edgeDelta * 0.15f +
            occupancyDelta * 0.10f +
            hashDelta * 0.20f
    }

    private inline fun <T> measureTimeMillisWithResult(block: () -> T): TimedResult<T> {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        return TimedResult(result, SystemClock.elapsedRealtime() - start)
    }

    private fun writeScanReport(report: ScanFindReport): String {
        val reportDir = File(context.filesDir, "scan_reports")
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }
        val output = File(reportDir, "scan-${System.currentTimeMillis()}.json")
        val payload = JSONObject().apply {
            put("sourceUri", report.sourceUri)
            put("profileLabel", report.profileLabel)
            put("scannerMode", report.scannerMode.name)
            put("frameProvider", report.frameProvider)
            put("frameProviderFallbackReason", report.frameProviderFallbackReason)
            put("checkpointIntervalMs", report.checkpointIntervalMs)
            putFinite("scanSpeedMultiple", report.scanSpeedMultiple)
            put("experimentalDownscaleSize", report.experimentalDownscaleSize)
            put("videoDurationMs", report.videoDurationMs)
            put("wallClockScanMs", report.wallClockScanMs)
            put("candidateWindows", report.candidateWindows)
            put("coarseSampleCount", report.coarseSampleCount)
            put("acceptedTransitions", report.acceptedTransitions)
            put("rejectedCandidates", report.rejectedCandidates)
            put("fallbackUsed", report.fallbackUsed)
            put("failureReason", report.failureReason)
            put("ocrCallCount", report.metrics.ocrCalls)
            put("frameStepMs", report.frameStepMs)
            put("candidatesFound", report.candidatesFound)
            put("mode", report.mode)
            put("durationMs", report.durationMs)
            put("transitionsFound", report.transitionsFound)
            put("scannerVersion", report.metrics.scannerVersion)
            put("metrics", JSONObject().apply {
                put("wallClockMs", report.metrics.wallClockMs)
                put("baselineSamplesEstimate", report.metrics.baselineSamplesEstimate)
                put("visualSamples", report.metrics.visualSamples)
                put("ocrCalls", report.metrics.ocrCalls)
                put("ocrReductionPercent", report.metrics.ocrReductionPercent)
                putFinite("throughputVideoToWall", report.metrics.throughputVideoToWall)
                put("skippedStableMs", report.metrics.skippedStableMs)
                put("acceptedTransitions", report.metrics.acceptedTransitions)
                put("rejectedCandidates", report.metrics.rejectedCandidates)
                put("candidateTimeouts", report.metrics.candidateTimeouts)
                put("scanRate20xGateMet", report.metrics.throughputVideoToWall >= 20f)
                put("scanRate30xGateMet", report.metrics.throughputVideoToWall >= 30f)
                put("ocrReductionGateMet", report.metrics.ocrReductionPercent >= 90)
            })
            put("candidates", JSONArray().apply {
                report.candidates.forEach { candidate ->
                    put(JSONObject().apply {
                        put("startMs", candidate.startMs)
                        put("endMs", candidate.endMs)
                        put("seedMs", candidate.seedMs)
                        putFinite("peakScore", candidate.peakScore)
                        put("reason", candidate.reason.name)
                        put("sampleCount", candidate.sampleCount)
                    })
                }
            })
            put("clipPlan", JSONObject().apply {
                put("policy", "exact-semantic-windows-v1")
                put("resultClassification", if (report.transitionPlanPoints.any {
                        it.classification != TransitionPlanClassification.CONFIRMED
                    }) "PROVISIONAL" else "CONFIRMED")
                put("clipCount", report.plannedSegments.size)
                put("totalDurationMs", expectedCompilationDurationMs(report.plannedSegments))
                put("transitionPoints", JSONArray().apply {
                    report.transitionPlanPoints.forEach { point ->
                        put(JSONObject().apply {
                            put("timestampMs", point.timestampMs)
                            put("classification", point.classification.name)
                            putFinite("confidence", point.confidence)
                            putFinite("visualScore", point.visualScore)
                            put("reason", point.reason)
                        })
                    }
                })
                put("segments", JSONArray().apply {
                    report.plannedSegments.forEach { segment ->
                        put(JSONObject().apply {
                            put("startMs", segment.startMs)
                            put("endMs", segment.endMs)
                            put("durationMs", segment.endMs - segment.startMs)
                        })
                    }
                })
            })
            put("candidateConfirmations", JSONArray().apply {
                report.candidateConfirmations.forEach { evidence ->
                    put(JSONObject().apply {
                        put("candidateIndex", evidence.candidateIndex)
                        put("candidateTimestampMs", evidence.candidateTimestampMs)
                        put("candidateStartMonotonicMs", evidence.candidateStartMonotonicMs)
                        put("globalConfirmationStartMonotonicMs", evidence.globalConfirmationStartMonotonicMs)
                        put("globalDeadlineMonotonicMs", evidence.globalDeadlineMonotonicMs)
                        put("localDeadlineMonotonicMs", evidence.localDeadlineMonotonicMs)
                        put("localBudgetMs", evidence.localBudgetMs)
                        put("remainingGlobalBudgetMs", evidence.remainingGlobalBudgetMs)
                        put("elapsedCandidateMs", evidence.elapsedCandidateMs)
                        put("elapsedGlobalMs", evidence.elapsedGlobalMs)
                        put("currentWork", evidence.currentWork)
                        put("plannedWorkUnits", evidence.plannedWorkUnits)
                        put("completedWorkUnits", evidence.completedWorkUnits)
                        put("timeoutSource", evidence.timeoutSource)
                        put("exceptionType", evidence.exceptionType)
                        put("cancellationCause", evidence.cancellationCause)
                        put("partialEvidenceCollected", evidence.partialEvidenceCollected)
                        put("innerTimeoutCount", evidence.innerTimeoutCount)
                        put("innerFailureCount", evidence.innerFailureCount)
                        put("beforeNumber", evidence.beforeNumber ?: JSONObject.NULL)
                        put("afterNumber", evidence.afterNumber ?: JSONObject.NULL)
                        put("disposition", evidence.disposition)
                        put("reason", evidence.reason)
                    })
                }
            })
            put("checkpointTimeline", JSONArray().apply {
                report.checkpointTimeline.forEach { checkpoint ->
                    put(stableStateEvidenceJson(checkpoint.timeMs, "coarse-checkpoint", checkpoint.state))
                }
            })
            put("recursiveStateEvidence", JSONArray().apply {
                report.recursiveStateEvidence.forEach { evidence ->
                    put(stableStateEvidenceJson(evidence.timeMs, evidence.source, evidence.state))
                }
            })
            put("transitionMarks", JSONArray().apply {
                report.transitionMarks.forEach { mark ->
                    put(JSONObject().apply {
                        put("eventBoundaryMs", mark.eventBoundaryMs)
                        put("fromNumber", mark.fromNumber)
                        put("toNumber", mark.toNumber)
                        putFinite("confidence", mark.confidence)
                        put("requestedCutStartMs", mark.requestedCutStartMs)
                        put("requestedCutEndMs", mark.requestedCutEndMs)
                        put("candidateReason", mark.candidateReason)
                        putFinite("candidatePeakScore", mark.candidatePeakScore)
                        put("evidence", JSONArray().apply {
                            mark.evidence.forEach { value -> put(value) }
                        })
                    })
                }
            })
            put("scanWindow", JSONObject().apply {
                putFinite("xPercent", report.scanWindow.xPercent)
                putFinite("yPercent", report.scanWindow.yPercent)
                putFinite("widthPercent", report.scanWindow.widthPercent)
                putFinite("heightPercent", report.scanWindow.heightPercent)
            })
            put("timing", JSONObject().apply {
                put("decodeMs", report.timing.decodeMs)
                put("cropMs", report.timing.cropMs)
                put("preprocessMs", report.timing.preprocessMs)
                put("ocrMs", report.timing.ocrMs)
                put("mergeMs", report.timing.mergeMs)
            })
            put("savedAtMs", System.currentTimeMillis())
        }
        val temporary = File(reportDir, ".${output.name}.tmp")
        if (temporary.exists()) temporary.delete()
        temporary.writeText(payload.toString(2))
        check(temporary.renameTo(output)) {
            "Could not atomically publish scan report ${output.absolutePath}"
        }
        return output.absolutePath
    }

    private fun stableStateEvidenceJson(
        timeMs: Long,
        source: String,
        state: StableNumberState
    ): JSONObject = JSONObject().apply {
        put("timeMs", timeMs)
        put("source", source)
        put("stable", state.stable)
        put("number", state.value ?: JSONObject.NULL)
        put("votes", JSONArray().apply {
            state.votes.forEach { vote ->
                put(JSONObject().apply {
                    put("timestampMs", vote.timestampMs)
                    put("decodedTimestampMs", vote.decodedTimestampMs ?: JSONObject.NULL)
                    put("number", vote.value ?: JSONObject.NULL)
                    put("status", vote.status)
                    put("mlKitValue", vote.mlKitValue ?: JSONObject.NULL)
                    putFiniteNullable("mlKitConfidence", vote.mlKitConfidence)
                    put("rawText", vote.rawText)
                    put("preprocessingBranch", vote.preprocessingBranch)
                    put("mlKitStructure", vote.mlKitStructure?.let(::mlKitStructureJson) ?: JSONObject.NULL)
                    put(
                        "topology",
                        vote.topology?.let(::glyphTopologyJson) ?: JSONObject().apply {
                            put("decision", SixNineDecision.NOT_APPLICABLE.name)
                            put("reason", "complete ML Kit integer was not adjudicated as 6/9")
                        }
                    )
                    put("timing", JSONObject().apply {
                        put("cropMs", vote.cropMs)
                        put("preprocessMs", vote.preprocessMs)
                        put("ocrMs", vote.ocrMs)
                    })
                })
            }
        })
    }

    private fun mlKitStructureJson(evidence: MlKitRecognitionEvidence): JSONObject = JSONObject().apply {
        put("parsedValue", evidence.parsedValue)
        put("matchedText", evidence.matchedText)
        put("blockIndex", evidence.blockIndex)
        put("lineIndex", evidence.lineIndex)
        put("matchedElementIndex", evidence.matchedElementIndex ?: JSONObject.NULL)
        putFiniteNullable("lineConfidence", evidence.lineConfidence)
        putFinite("aggregateConfidence", evidence.aggregateConfidence)
        put("confidenceSource", evidence.confidenceSource)
        put("lineBounds", evidence.lineBounds?.let(::recognitionBoundsJson) ?: JSONObject.NULL)
        put("lineCornerPoints", recognitionPointsJson(evidence.lineCornerPoints))
        putFinite("lineAngleDegrees", evidence.lineAngleDegrees)
        put("elements", JSONArray().apply {
            evidence.elements.forEach { element ->
                put(JSONObject().apply {
                    put("text", element.text)
                    putFiniteNullable("confidence", element.confidence)
                    put("bounds", element.bounds?.let(::recognitionBoundsJson) ?: JSONObject.NULL)
                    put("cornerPoints", recognitionPointsJson(element.cornerPoints))
                    putFinite("angleDegrees", element.angleDegrees)
                    put("symbols", JSONArray().apply {
                        element.symbols.forEach { symbol ->
                            put(JSONObject().apply {
                                put("text", symbol.text)
                                putFiniteNullable("confidence", symbol.confidence)
                                put("bounds", symbol.bounds?.let(::recognitionBoundsJson) ?: JSONObject.NULL)
                                put("cornerPoints", recognitionPointsJson(symbol.cornerPoints))
                                putFinite("angleDegrees", symbol.angleDegrees)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun recognitionBoundsJson(bounds: RecognitionBounds): JSONObject = JSONObject().apply {
        put("left", bounds.left)
        put("top", bounds.top)
        put("right", bounds.right)
        put("bottom", bounds.bottom)
    }

    private fun recognitionPointsJson(points: List<RecognitionPoint>): JSONArray = JSONArray().apply {
        points.forEach { point -> put(JSONObject().apply { put("x", point.x); put("y", point.y) }) }
    }

    private fun glyphTopologyJson(evidence: GlyphTopologyEvidence): JSONObject = JSONObject().apply {
        put("decision", evidence.decision.name)
        put("thresholdMethod", evidence.thresholdMethod)
        put("thresholdValue", evidence.thresholdValue)
        put("polarity", evidence.polarity)
        put("componentArea", evidence.componentArea)
        put("componentBounds", evidence.componentBounds?.let(::rectLikeJson) ?: JSONObject.NULL)
        put("holeCount", evidence.holeCount)
        put("dominantHoleArea", evidence.dominantHoleArea)
        putFiniteNullable("dominantHoleCentroidYNormalized", evidence.dominantHoleCentroidYNormalized)
        putFinite("confidence", evidence.confidence)
        put("reason", evidence.reason)
        put("variants", JSONArray().apply {
            evidence.variants.forEach { variant ->
                put(JSONObject().apply {
                    put("decision", variant.decision.name)
                    put("thresholdMethod", variant.thresholdMethod)
                    put("thresholdValue", variant.thresholdValue)
                    put("polarity", variant.polarity)
                    put("componentArea", variant.componentArea)
                    put("componentBounds", variant.componentBounds?.let(::rectLikeJson) ?: JSONObject.NULL)
                    put("holeCount", variant.holeCount)
                    put("dominantHoleArea", variant.dominantHoleArea)
                    putFiniteNullable("dominantHoleCentroidYNormalized", variant.dominantHoleCentroidYNormalized)
                    putFinite("confidence", variant.confidence)
                    put("structurallyValid", variant.structurallyValid)
                    put("reason", variant.reason)
                })
            }
        })
    }

    private fun rectLikeJson(bounds: RectLike): JSONObject = JSONObject().apply {
        put("left", bounds.left)
        put("top", bounds.top)
        put("right", bounds.right)
        put("bottom", bounds.bottom)
    }

    private fun cropToWindow(frame: Bitmap, scanWindow: ScanWindow): Bitmap {
        val rect = SafeVisionImage.computeSafeCropRect(frame.width, frame.height, scanWindow)
            ?: throw IllegalArgumentException("Invalid analysis frame ${frame.width}x${frame.height}; ML Kit input requires at least ${MIN_INPUT_IMAGE_DIMENSION}x${MIN_INPUT_IMAGE_DIMENSION}")
        if (BuildConfig.DEBUG) {
            AppLog.d(
                context,
                tag,
                "ROI crop source=${frame.width}x${frame.height} roi=${scanWindow.xPercent},${scanWindow.yPercent},${scanWindow.widthPercent},${scanWindow.heightPercent} " +
                    "crop=${rect.left},${rect.top},${rect.width}x${rect.height} fallback=${rect.usedFullFrameFallback}"
            )
        }
        return Bitmap.createBitmap(frame, rect.left, rect.top, rect.width, rect.height)
    }

    private fun cropToWindowForOcr(frame: Bitmap, scanWindow: ScanWindow): Bitmap {
        val rect = SafeVisionImage.computeSafeCropRect(frame.width, frame.height, scanWindow)
            ?: throw IllegalArgumentException("Invalid OCR analysis frame ${frame.width}x${frame.height}")
        val padX = max(2, (rect.width * OcrPreparationPolicy.ROI_PADDING_FRACTION).roundToInt())
        val padY = max(2, (rect.height * OcrPreparationPolicy.ROI_PADDING_FRACTION).roundToInt())
        val left = max(0, rect.left - padX)
        val top = max(0, rect.top - padY)
        val right = min(frame.width, rect.left + rect.width + padX)
        val bottom = min(frame.height, rect.top + rect.height + padY)
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }

    private fun scaleBitmapForOcr(input: Bitmap): Bitmap {
        val (targetW, targetH) = OcrPreparationPolicy.targetSize(input.width, input.height)
        if (targetW == input.width && targetH == input.height) return input
        return Bitmap.createScaledBitmap(input, targetW, targetH, true)
    }

    private fun extractorSampleFlagsToBufferFlags(sampleFlags: Int): Int {
        return if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
    }

    private suspend fun materializeCompilation(
        sourceUri: Uri,
        segments: List<SegmentWindow>,
        format: ExportFormat,
        output: File,
        progress: (String, Int) -> Unit
    ) {
        if (segments.isEmpty()) {
            throw IllegalStateException("No segments to assemble")
        }

        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        val muxerFormat = when (safeFormat) {
            ExportFormat.Mp4, ExportFormat.Mov -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ExportFormat.Webm -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
        extractor.setDataSource(context, sourceUri, null)
        val trackMap = mutableMapOf<Int, Int>()

        muxer = MediaMuxer(output.absolutePath, muxerFormat)
        val activeMuxer = muxer
        val trackMimeTypes = mutableMapOf<Int, String>()
        for (trackIndex in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (mime == null || (!mime.startsWith("audio/") && !mime.startsWith("video/"))) {
                continue
            }
            trackMap[trackIndex] = activeMuxer.addTrack(trackFormat)
            trackMimeTypes[trackIndex] = mime
        }

        if (trackMap.isEmpty()) {
            throw IllegalStateException("No audio/video tracks found for export")
        }

        activeMuxer.start()
        muxerStarted = true
        val totalSegments = segments.size
        val segmentBuffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
        val sampleInfo = MediaCodec.BufferInfo()
        val trackOutputOffsetUs = trackMap.keys.associateWith { 0L }.toMutableMap()
        var outputCursorUs = 0L
        val totalSourceUs = max(1L, segments.sumOf { max(1L, it.endMs - it.startMs) } * 1000L)
        val progressTrack = trackMap.keys.firstOrNull { (trackMimeTypes[it] ?: "").startsWith("video/") } ?: trackMap.keys.firstOrNull()
        var exportedUsEstimate = 0L
        var lastExportLogMs = 0L

        segments.forEachIndexed { index, segment ->
            currentCoroutineContext().ensureActive()
            progress("Exporting segment ${index + 1} of $totalSegments", 60 + (index * 35 / totalSegments))
            val segStartUs = segment.startMs * 1000L
            val segEndUs = segment.endMs * 1000L
            val segmentDurationUs = max(1L, segEndUs - segStartUs)
            var segmentMaxUs = outputCursorUs

            for (trackIndex in trackMap.keys) {
                currentCoroutineContext().ensureActive()
                var lastOutputSampleUs = outputCursorUs
                extractor.selectTrack(trackIndex)
                extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val isProgressTrack = trackIndex == progressTrack
                var segTrackMaxUs = outputCursorUs

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs == -1L || sampleTimeUs >= segEndUs) {
                        break
                    }
                    if (sampleTimeUs < segStartUs) {
                        extractor.advance()
                        continue
                    }

                    val sampleSize = extractor.readSampleData(segmentBuffer, 0)
                    if (sampleSize < 0) {
                        break
                    }

                    sampleInfo.size = sampleSize
                    val targetPtsUs = trackOutputOffsetUs.getValue(trackIndex) + sampleTimeUs - segStartUs
                    val outputPtsUs = if (targetPtsUs > lastOutputSampleUs) {
                        targetPtsUs
                    } else {
                        lastOutputSampleUs + 1L
                    }
                    sampleInfo.presentationTimeUs = outputPtsUs
                    sampleInfo.flags = extractorSampleFlagsToBufferFlags(extractor.sampleFlags)
                    sampleInfo.offset = 0
                    activeMuxer.writeSampleData(trackMap.getValue(trackIndex), segmentBuffer, sampleInfo)
                    lastOutputSampleUs = outputPtsUs
                    if (isProgressTrack) {
                        segTrackMaxUs = maxOf(segTrackMaxUs, outputPtsUs)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastExportLogMs >= 500L) {
                            val completedUs = (exportedUsEstimate + (segTrackMaxUs - outputCursorUs)).coerceAtMost(totalSourceUs)
                            val exportPercent = (60 + (completedUs * 35L / totalSourceUs).toInt()).coerceIn(60, 95)
                            val remaining = formatDurationUs(totalSourceUs - completedUs)
                            progress(
                                "Exporting segments ${index + 1}/$totalSegments (${formatDurationMs(completedUs / 1000L)} / ${formatDurationMs(totalSourceUs / 1000L)}, remaining $remaining)",
                                exportPercent
                            )
                            lastExportLogMs = now
                        }
                    }
                    extractor.advance()
                }
                segmentMaxUs = maxOf(segmentMaxUs, lastOutputSampleUs + 1L)
                if (isProgressTrack) {
                    val segmentTrackWrittenUs = (segTrackMaxUs - outputCursorUs).coerceAtLeast(0L)
                    exportedUsEstimate += segmentTrackWrittenUs
                }
                trackOutputOffsetUs[trackIndex] = segmentMaxUs
                extractor.unselectTrack(trackIndex)
            }

            outputCursorUs = maxOf(outputCursorUs + segmentDurationUs, segmentMaxUs)
            trackMap.keys.forEach { trackIndex ->
                if (trackOutputOffsetUs.getValue(trackIndex) < outputCursorUs) {
                    trackOutputOffsetUs[trackIndex] = outputCursorUs
                }
            }
            val percent = 60 + ((index + 1) * 35 / totalSegments)
            progress("Writing segment ${index + 1}/$totalSegments", percent)
        }

        progress("Muxing final output", 95)
        AppLog.i(context, tag, "[export] muxing final output thread=${Thread.currentThread().name}")
        activeMuxer.stop()
        muxerStarted = false
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
                .onFailure { AppLog.w(context, tag, "Muxer stop cleanup failed", it) }
            runCatching { muxer?.release() }
                .onFailure { AppLog.w(context, tag, "Muxer cleanup failed", it) }
            runCatching { extractor.release() }
                .onFailure { AppLog.w(context, tag, "Export extractor cleanup failed", it) }
        }
    }

    private fun validateExportOutput(output: File): VerifiedCompilationOutput {
        if (!output.exists() || !output.isFile || !output.canRead() || output.length() <= 0L) {
            throw IllegalStateException("Exported compilation was not written successfully")
        }
        output.inputStream().use { input ->
            if (input.read() < 0) throw IllegalStateException("Exported compilation cannot be opened")
        }
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var durationMs = 0L
        try {
            retriever.setDataSource(output.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                throw IllegalStateException("Exported compilation has no readable duration")
            }
            extractor.setDataSource(output.absolutePath)
            val hasVideo = (0 until extractor.trackCount).any { trackIndex ->
                extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (!hasVideo) {
                throw IllegalStateException("Exported compilation has no video track")
            }
        } finally {
            runCatching { retriever.release() }
                .onFailure { AppLog.w(context, tag, "Verification retriever cleanup failed", it) }
            runCatching { extractor.release() }
                .onFailure { AppLog.w(context, tag, "Verification extractor cleanup failed", it) }
        }
        val readableUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updateprovider",
            output
        )
        context.contentResolver.openInputStream(readableUri).use { input ->
            if (input == null || input.read() < 0) {
                throw IllegalStateException("Exported compilation URI cannot be opened")
            }
        }
        return VerifiedCompilationOutput(
            file = output,
            uri = readableUri.toString(),
            sizeBytes = output.length(),
            durationMs = durationMs
        )
    }

    private fun alignSegmentsToVideoSyncSamples(
        sourceUri: Uri,
        segments: List<SegmentWindow>
    ): List<SegmentWindow> {
        if (segments.isEmpty()) return segments

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, sourceUri, null)
            val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { trackIndex ->
                extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return segments

            extractor.selectTrack(videoTrackIndex)
            val aligned = segments.map { segment ->
                val requestedStartUs = segment.startMs * 1000L
                extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val syncSampleUs = extractor.sampleTime
                val alignedStartMs = if (syncSampleUs >= 0L && syncSampleUs <= requestedStartUs) {
                    syncSampleUs / 1000L
                } else {
                    segment.startMs
                }
                SegmentWindow(alignedStartMs, segment.endMs)
            }.sortedBy { it.startMs }
            mergeOverlapping(aligned)
        } finally {
            runCatching { extractor.release() }
                .onFailure { AppLog.w(context, tag, "Sync-sample extractor cleanup failed", it) }
        }
    }

    private fun mergeOverlapping(input: List<SegmentWindow>): List<SegmentWindow> {
        return mergeSegmentWindows(input, 0L)
    }

    private fun mergeWithGap(input: List<SegmentWindow>, maxGapMs: Long): List<SegmentWindow> {
        return mergeSegmentWindows(input, maxGapMs)
    }

    private fun formatDurationMs(totalMs: Long): String {
        val totalSec = max(0L, totalMs) / 1000
        val sec = totalSec % 60
        val min = totalSec / 60
        val ms = max(0L, totalMs) % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, ms)
    }

    private fun formatDurationUs(totalUs: Long): String {
        return formatDurationMs(totalUs / 1000L)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val frac = ms % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, frac)
    }
}

data class ScanTimingSummary(
    var decodeMs: Long = 0L,
    var cropMs: Long = 0L,
    var preprocessMs: Long = 0L,
    var ocrMs: Long = 0L,
    var mergeMs: Long = 0L
) {
    fun totalMs(): Long = decodeMs + cropMs + preprocessMs + ocrMs + mergeMs
}

data class ScanFindResult(
    val segments: List<SegmentWindow>,
    val timing: ScanTimingSummary,
    val transitionMarks: List<TransitionMark>,
    val metrics: ScanMetrics,
    val videoDurationMs: Long,
    val candidateCount: Int = 0,
    val candidateTimestampsMs: List<Long> = emptyList(),
    val scanBudgetReached: Boolean = false,
    val visualFallbackUsed: Boolean = false,
    val confirmedTransitionCount: Int = 0,
    val provisionalTransitionCount: Int = 0,
    val rejectedTransitionCount: Int = 0,
    val completedCheckpointCount: Int = 0,
    val recursiveProbeCount: Int = 0,
    val semanticLeafCount: Int = 0,
    val previewClassification: CompilationPreviewClassification = CompilationPreviewClassification.NONE
)

data class VerifiedCompilationOutput(
    val file: File,
    val uri: String,
    val sizeBytes: Long,
    val durationMs: Long
)

private data class ScanFindReport(
    val sourceUri: String,
    val profileLabel: String,
    val scannerMode: ScanMode,
    val frameProvider: String,
    val frameProviderFallbackReason: String?,
    val checkpointIntervalMs: Long,
    val scanSpeedMultiple: Float,
    val experimentalDownscaleSize: Int,
    val videoDurationMs: Long,
    val wallClockScanMs: Long,
    val candidateWindows: Int,
    val coarseSampleCount: Int,
    val scanWindow: ScanWindow,
    val frameStepMs: Long,
    val candidatesFound: Int,
    val mode: String,
    val durationMs: Long,
    val transitionsFound: Int,
    val acceptedTransitions: Int,
    val rejectedCandidates: Int,
    val fallbackUsed: Boolean,
    val failureReason: String?,
    val timing: ScanTimingSummary,
    val metrics: ScanMetrics,
    val transitionMarks: List<TransitionMark>,
    val candidates: List<TransitionCandidateWindow>,
    val checkpointTimeline: List<CheckpointTimelineEntry>,
    val recursiveStateEvidence: List<StateTimelineEvidenceEntry>,
    val plannedSegments: List<SegmentWindow>,
    val candidateConfirmations: List<CandidateConfirmationEvidence>,
    val transitionPlanPoints: List<PlannedTransitionPoint>
)

private data class CandidateConfirmationEvidence(
    val candidateIndex: Int,
    val candidateTimestampMs: Long,
    val candidateStartMonotonicMs: Long,
    val globalConfirmationStartMonotonicMs: Long,
    val globalDeadlineMonotonicMs: Long,
    val localDeadlineMonotonicMs: Long,
    val localBudgetMs: Long,
    val remainingGlobalBudgetMs: Long,
    val elapsedCandidateMs: Long,
    val elapsedGlobalMs: Long,
    val currentWork: String,
    val plannedWorkUnits: Int,
    val completedWorkUnits: Int,
    val timeoutSource: String,
    val exceptionType: String,
    val cancellationCause: String,
    val partialEvidenceCollected: Boolean,
    val innerTimeoutCount: Int,
    val innerFailureCount: Int,
    val beforeNumber: Int?,
    val afterNumber: Int?,
    val disposition: String,
    val reason: String
)

private data class NumberDetectionResult(
    val value: Int?,
    val cropMs: Long,
    val preprocessMs: Long,
    val ocrMs: Long,
    val confidence: Float = 0f,
    val rawText: String = "",
    val branch: String = "raw",
    val status: OcrCandidateStatus = if (value == null) OcrCandidateStatus.NO_TRANSITION else OcrCandidateStatus.CONFIRMED_TRANSITION,
    val mlKitValue: Int? = value,
    val mlKitStructure: MlKitRecognitionEvidence? = null,
    val topology: GlyphTopologyEvidence? = null
)

private enum class OcrCandidateStatus {
    CONFIRMED_TRANSITION,
    NO_TRANSITION,
    NO_TEXT,
    INVALID_INTEGER,
    AMBIGUOUS_TOPOLOGY,
    OCR_UNAVAILABLE,
    OCR_TIMEOUT,
    FRAME_TIMEOUT,
    INVALID_FRAME
}

private data class TransitionCandidateWindow(
    val startMs: Long,
    val endMs: Long,
    val seedMs: Long,
    val peakScore: Float,
    val reason: CandidateReason,
    val sampleCount: Int,
    val fromNumber: Int? = null,
    val toNumber: Int? = null,
    val fromStateStable: Boolean = false,
    val toStateStable: Boolean = false
)

private data class CheckpointTimelineEntry(
    val timeMs: Long,
    val signature: RoiSignature,
    val state: StableNumberState
)

private data class StateTimelineEvidenceEntry(
    val timeMs: Long,
    val source: String,
    val state: StableNumberState
)

private enum class CandidateReason { InitialProbe, VisualChange, PeriodicProbe }

data class TransitionMark(
    val eventBoundaryMs: Long,
    val fromNumber: Int?,
    val toNumber: Int,
    val confidence: Float,
    val requestedCutStartMs: Long,
    val requestedCutEndMs: Long,
    val candidateReason: String,
    val candidatePeakScore: Float,
    val evidence: List<String>
)

private data class TimedDetection(
    val value: Int?,
    val calls: Int,
    val timeMs: Long?,
    val timeoutCount: Int = 0,
    val failureCount: Int = 0
)

private data class NumberDetectionEvidence(
    val value: Int?,
    val decodedTimestampMs: Long?,
    val status: String,
    val mlKitValue: Int? = value,
    val mlKitConfidence: Float? = null,
    val rawText: String = "",
    val preprocessingBranch: String = "",
    val mlKitStructure: MlKitRecognitionEvidence? = null,
    val topology: GlyphTopologyEvidence? = null,
    val cropMs: Long = 0L,
    val preprocessMs: Long = 0L,
    val ocrMs: Long = 0L,
    val timeoutCount: Int = 0,
    val failureCount: Int = 0
)

private data class OcrAdjudication(
    val value: Int?,
    val status: OcrCandidateStatus,
    val topology: GlyphTopologyEvidence?
)

private data class TimelineStateDetection(
    val state: StableNumberState,
    val calls: Int,
    val timeoutCount: Int = 0,
    val failureCount: Int = 0
)

private data class CheckpointCandidateInvestigation(
    val candidates: List<TransitionCandidateWindow>,
    val calls: Int,
    val probes: Int,
    val stateEvidence: List<StateTimelineEvidenceEntry>
)

private interface FrameProvider : AutoCloseable {
    suspend fun frameAt(timeMs: Long, targetWidthPx: Int = 0): DecodedFrame?
    suspend fun forEachFrameInWindow(
        startMs: Long,
        endMs: Long,
        sampleEveryMs: Long,
        targetWidthPx: Int = 0,
        onFrame: suspend (DecodedFrame) -> FrameIterationDecision
    )
    suspend fun decodeWindow(
        startMs: Long,
        endMs: Long,
        sampleEveryMs: Long,
        targetWidthPx: Int = 0
    ): List<DecodedFrame> {
        val frames = ArrayList<DecodedFrame>()
        forEachFrameInWindow(startMs, endMs, sampleEveryMs, targetWidthPx) { frame ->
            frames.add(frame)
            FrameIterationDecision.CONTINUE
        }
        return frames
    }
}

private data class DecodedFrame(
    val timeMs: Long,
    val bitmap: Bitmap,
    val sourceTimeUs: Long = timeMs * 1000L
)

data class ScanMetrics(
    val scannerVersion: String,
    val wallClockMs: Long,
    val baselineSamplesEstimate: Int,
    val visualSamples: Int,
    val ocrCalls: Int,
    val ocrReductionPercent: Int,
    val throughputVideoToWall: Float,
    val skippedStableMs: Long,
    val acceptedTransitions: Int,
    val rejectedCandidates: Int,
    val candidateTimeouts: Int = 0
) {
    companion object {
        fun empty(): ScanMetrics = ScanMetrics(
            scannerVersion = "v3-stable-state-topology-ocr",
            wallClockMs = 0L,
            baselineSamplesEstimate = 0,
            visualSamples = 0,
            ocrCalls = 0,
            ocrReductionPercent = 0,
            throughputVideoToWall = 0f,
            skippedStableMs = 0L,
            acceptedTransitions = 0,
            rejectedCandidates = 0,
            candidateTimeouts = 0
        )
    }
}

private data class ChangeMapConfig(
    val sampleStepMs: Long,
    val signatureScaleWidthPx: Int,
    val candidatePadMs: Long,
    val candidateMergeGapMs: Long,
    val changeThreshold: Float,
    val periodicProbeMs: Long,
    val neighborhoodStepMs: Long,
    val refineWindowMs: Long,
    val refineIterations: Int,
    val dedupeMs: Long,
    val prefirstProbeMs: Long,
    val maxVisualSamples: Int,
    val totalScanBudgetMs: Long,
    val maxCandidateWindows: Int,
    val ocrFrameWidthPx: Int,
    val stableMaxStepMs: Long,
    val stableRampSamples: Int,
    val denseRefineStepMs: Long,
    val baselineStepMs: Long
)

private data class RoiSignature(
    val samples: IntArray,
    val average: Int,
    val contrast: Float,
    val edgeEnergy: Float,
    val occupancy: Float,
    val hash: Long
)

private data class TimedResult<T>(
    val value: T,
    val elapsedMs: Long
)

private data class FrameProviderSelection(
    val provider: FrameProvider,
    val providerLabel: String,
    val fallbackReason: String? = null
)

data class SegmentWindow(val startMs: Long, val endMs: Long)
data class ScanWindow(val xPercent: Float, val yPercent: Float, val widthPercent: Float, val heightPercent: Float)
internal data class ScanProfile(
    val label: String,
    val frameStepMs: Long,
    val mode: ScanMode,
    val scannerProfileId: String? = null
)

internal fun compilationScanProfiles(): Array<ScanProfile> = arrayOf(
    ScanProfile("Prototype Fast PTS (30s)", 30_000L, ScanMode.StableCheckpoint, "FAST"),
    ScanProfile("Monotonic Turbo PTS (3m adaptive, persistent 1→N)", 180_000L, ScanMode.StableCheckpoint, "MONOTONIC_3_MIN"),
    ScanProfile("Experimental Quick Mode (5m adaptive + parallel hardware)", 300_000L, ScanMode.StableCheckpoint, "QUICK_5_MIN"),
    ScanProfile("Prototype Balanced PTS (10s)", 10_000L, ScanMode.StableCheckpoint, "BALANCED"),
    ScanProfile("Prototype Precise PTS (3s)", 3_000L, ScanMode.StableCheckpoint, "PRECISE"),
    ScanProfile("Legacy Experimental (125ms)", 125L, ScanMode.Experimental)
)

internal fun transitionSummariesFromClipPlan(clipPlanJson: String): List<String> {
    if (clipPlanJson.isBlank()) return emptyList()
    return runCatching {
        val summaries = JSONObject(clipPlanJson).optJSONArray("transitionSummaries")
            ?: return@runCatching emptyList()
        (0 until summaries.length()).mapNotNull { index ->
            when (val item = summaries.opt(index)) {
                is String -> item.trim().takeIf(String::isNotEmpty)
                is JSONObject -> formatTransitionSummary(item)
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

private fun formatTransitionSummary(summary: JSONObject): String? {
    val explicitLabel = summary.optString("summary", "").trim()
        .ifBlank { summary.optString("label", "").trim() }
    val fromKeyPresent = summary.has("fromNumber") || summary.has("from")
    val fromNumber = summary.optionalInt("fromNumber") ?: summary.optionalInt("from")
    val toNumber = summary.optionalInt("toNumber") ?: summary.optionalInt("to")
    val transitionLabel = explicitLabel.ifBlank {
        when {
            toNumber == null -> ""
            fromNumber != null -> "$fromNumber -> $toNumber"
            fromKeyPresent -> "none -> $toNumber"
            else -> "to $toNumber"
        }
    }
    val eventBoundaryMs = summary.optionalLong("eventBoundaryMs")
        ?: summary.optionalLong("actualFramePtsMs")
        ?: summary.optionalLong("timestampMs")
    val parts = ArrayList<String>(3)
    transitionLabel.takeIf(String::isNotBlank)?.let(parts::add)
    eventBoundaryMs?.let { parts += formatTransitionTimestampMs(it) }
    summary.optionalDouble("confidence")?.let { confidence ->
        val percent = if (confidence <= 1.0) confidence * 100.0 else confidence
        parts += "${percent.coerceIn(0.0, 100.0).roundToInt()}%"
    }
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString("  |  ")
}

private fun JSONObject.optionalInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optString(key).toIntOrNull()

private fun JSONObject.optionalLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optString(key).toLongOrNull()

private fun JSONObject.optionalDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optString(key).toDoubleOrNull()

private fun formatTransitionTimestampMs(timeMs: Long): String {
    val safe = timeMs.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}

enum class ScanMode { StableCheckpoint, Experimental }
private enum class RoiTouchMode { NONE, MOVE, RESIZE }

enum class TransitionStyle(val label: String, val edgePaddingMs: Long, val mergeGapMs: Long) {
    Instant("Instant cuts", 0L, 0L),
    Gradual("Gradual transitions", 3_000L, 2_000L)
}

enum class ExportQuality(val label: String, val preset: String, val crf: Int, val extraVideoArgs: List<String>) {
    Low("Low (faster)", "ultrafast", 32, listOf()),
    Medium("Medium", "medium", 23, listOf("-movflags", "+faststart")),
    High("High (slower)", "slow", 18, listOf("-movflags", "+faststart"))
}

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val videoCodec: String,
    val audioCodec: String,
    val audioBitrate: String,
    val extraArgs: List<String>
) {
    Mp4("MP4 (H.264)", "mp4", "video/mp4", "libx264", "aac", "160k", listOf("-pix_fmt", "yuv420p")),
    Webm("WEBM (VP9)", "webm", "video/webm", "libvpx-vp9", "libopus", "128k", listOf()),
    Mov("MOV (ProRes)", "mov", "video/quicktime", "prores_ks", "pcm_s16le", "256k", listOf("-profile:v", "3"))
}
