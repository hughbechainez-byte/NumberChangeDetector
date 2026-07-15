package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoPickerHandoffTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule val intentsRule = IntentsRule()

    @Test
    fun openDocumentResultStartsWorkerUsingTheStagedVideoA() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = requireNotNull(findVideoA(context)) { "Video A is not available in MediaStore" }
        val result = Intent().apply {
            data = source
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, result))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.selectButton)).perform(click())
            onView(withId(R.id.processButton)).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                var record: CompilationJobRecord? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    record = CompilationJobStore(activity).load()
                    if (record?.state?.isActive == true) break
                    Thread.sleep(100)
                }
                check(record?.sourceUri == source.toString())
                check(record?.state?.isActive == true)
                WorkManager.getInstance(activity).cancelUniqueWork(CompilationJobContract.UNIQUE_WORK_NAME)
            }
        }
    }

    private fun findVideoA(context: android.content.Context): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME}=?"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf("compilation_test_video_A.mp4"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0).toString()
                )
            }
        }
        val staged = File("/sdcard/Download/compilation_test_video_A.mp4")
        if (!staged.isFile) return null
        val inserted = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "compilation_test_video_A.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CompilationMakerQA")
            }
        ) ?: return null
        return try {
            context.contentResolver.openOutputStream(inserted, "w")!!.use { output ->
                staged.inputStream().use { input -> input.copyTo(output) }
            }
            inserted
        } catch (failure: Exception) {
            context.contentResolver.delete(inserted, null, null)
            throw failure
        }
    }
}
