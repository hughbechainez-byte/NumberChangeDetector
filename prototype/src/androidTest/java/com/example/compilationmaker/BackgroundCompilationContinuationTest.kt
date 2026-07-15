package com.example.compilationmaker

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundCompilationContinuationTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule val intentsRule = IntentsRule()

    @Test
    fun compilationRemainsActiveAfterLeavingApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = requireNotNull(findVideoA()) { "Video A is not available in MediaStore" }
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().apply {
                    data = source
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.selectButton)).perform(click())
            onView(withId(R.id.scanSpeedPicker)).perform(scrollTo(), click())
            onData(allOf(instanceOf(String::class.java), `is`("Prototype Fast PTS (30s)"))).perform(click())
            onView(withId(R.id.processButton)).perform(scrollTo(), click())

            val store = CompilationJobStore(context)
            val started = awaitRecord(store) { it.progressPercent > 0 && !it.state.isTerminal }
            val workId = UUID.fromString(started.workId)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            SystemClock.sleep(3_000L)

            val continued = requireNotNull(WorkManager.getInstance(context).getWorkInfoById(workId).get())
            assertFalse("Compilation stopped after app left foreground: ${continued.state}", continued.state.isFinished)
            val persisted = requireNotNull(store.load())
            assertTrue("Progress was not retained after backgrounding", persisted.progressPercent >= started.progressPercent)

            WorkManager.getInstance(context).cancelWorkById(workId)
            assertNotNull(store.load())
        }
    }

    private fun awaitRecord(
        store: CompilationJobStore,
        predicate: (CompilationJobRecord) -> Boolean
    ): CompilationJobRecord {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            store.load()?.let { if (predicate(it)) return it }
            SystemClock.sleep(500L)
        }
        error("Compilation did not become active")
    }

    private fun findVideoA(): Uri? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=?",
            arrayOf("compilation_test_video_A.mp4"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
            }
        }
        return null
    }
}
