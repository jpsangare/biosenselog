package com.example

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assert
import androidx.core.os.LocaleListCompat
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import com.example.data.BiometricRecord
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.example.data.BiometricDatabase
import com.example.ui.BiometricViewModel
import org.junit.After
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System / UI tests: the real app on a device or emulator, driven like a user.
 * Developer build (simulator on), so no finger or body is needed.
 * Run with: tests/run-tests.ps1 ui   (an emulator or phone must be connected)
 *
 * The simulator's live readings (20 updates per second) would keep the screen permanently busy,
 * so they are frozen for these tests. Looping animations are cancelled by the Compose test framework.
 */
@RunWith(AndroidJUnit4::class)
class DashboardUiTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun text(id: Int) = context.getString(id)

    @Before fun failFast() {
        // A stuck screen fails the test in 20 s instead of Espresso's default of minutes.
        IdlingPolicies.setMasterPolicyTimeout(20, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(20, TimeUnit.SECONDS)
    }

    @Before fun freshInstall() {
        // Same state as a first launch: no accepted notice, no measurements.
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        BiometricDatabase.resetForTests(context)
    }

    @After fun close() {
        scenario?.close()
        // Back to the phone's language so other tests see the expected texts.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java).also {
            // Same ViewModel instance the screen uses (scoped to the activity).
            it.onActivity { activity ->
                ViewModelProvider(activity)[BiometricViewModel::class.java].freezeSimulationForTests()
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Waits until [matcher] is in the UI (in a dialog, or scrolled into view in the dashboard). */
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMs) {
            exists(matcher) ||
                runCatching { compose.onNodeWithTag("dashboard_list").performScrollToNode(matcher) }.isSuccess
        }
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) fail("Not found: $matcher")
        runCatching { compose.onNodeWithTag("dashboard_list").performScrollToNode(matcher) }
        return compose.onNode(matcher)
    }

    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher).performClick()
        compose.waitForIdle()
    }

    private fun acceptWelcome() = click(hasTestTag("accept_disclaimer_btn"))

    @Test fun welcomeNoticeMustBeAcceptedAndIsShownOnlyOnce() {
        launch()
        waitFor(hasText(text(R.string.privacy_title))).assertIsDisplayed()
        waitFor(hasText(text(R.string.free_title))).assertIsDisplayed()

        acceptWelcome()
        check(!exists(hasTestTag("accept_disclaimer_btn"))) { "notice still shown after accepting" }

        scenario?.close()
        launch()
        check(!exists(hasTestTag("accept_disclaimer_btn"))) { "notice shown again after restart" }
    }

    @Test fun tremorTabHasNoQuickSaveAndOtherTabsDo() {
        launch()
        acceptWelcome()

        click(hasTestTag("tab_tremor"))
        waitFor(hasTestTag("start_measurement_btn")).assertIsDisplayed()
        check(!exists(hasTestTag("save_now_btn"))) { "tremor must not offer a quick save" }
        check(!exists(hasTestTag("duration_60s_chip"))) { "tremor has a fixed duration" }

        click(hasTestTag("tab_ppg"))
        waitFor(hasTestTag("save_now_btn")).assertIsDisplayed()
        waitFor(hasTestTag("duration_60s_chip")).assertIsDisplayed()
    }

    @Test fun completeMeasurementAppearsInHistoryAndCanBeCleared() {
        assumeTrue("needs the simulator (developer build)", BuildConfig.DEBUG)
        launch()
        acceptWelcome()
        waitFor(hasText(text(R.string.empty_history))).assertIsDisplayed()

        // Quick 10 s pulse measurement in simulator mode.
        click(hasTestTag("start_measurement_btn"))
        waitFor(hasText(text(R.string.last_measurement)), timeoutMs = 25_000).assertIsDisplayed()

        // Clearing asks for confirmation first; cancelling keeps the data.
        click(hasTestTag("clear_history_btn"))
        click(hasTestTag("cancel_clear_history_btn"))
        waitFor(hasText(text(R.string.last_measurement))).assertIsDisplayed()

        click(hasTestTag("clear_history_btn"))
        click(hasTestTag("confirm_clear_history_btn"))
        waitFor(hasText(text(R.string.empty_history))).assertIsDisplayed()
    }

    @Test fun exportShowsBothFormatsCopiesAndSharesTheReport() {
        runBlocking {
            val dao = BiometricDatabase.getDatabase(context).biometricDao()
            dao.insertRecord(BiometricRecord(type = "PPG", value = 72.0, secondaryValue = 2.4, status = "NORMAL",
                timestamp = 1_000, rmssdMs = 42.0, respiratoryRate = 14.0))
            dao.insertRecord(BiometricRecord(type = "TREMOR", value = 0.065, secondaryValue = 8.7, status = "INFO", timestamp = 2_000))
        }
        launch()
        acceptWelcome()

        // CSV preview first
        click(hasTestTag("export_history_btn"))
        val preview = waitFor(hasTestTag("export_preview"))
        preview.assert(hasText("ID,Timestamp", substring = true))
        preview.assert(hasText("Hand Tremor", substring = true))

        // Summary report
        click(hasTestTag("export_format_report_chip"))
        compose.onNodeWithTag("export_preview").assert(hasText("BIOMETRIC REPORT", substring = true))

        // Copy puts the report on the clipboard
        click(hasTestTag("copy_export_btn"))
        var clipboardText: CharSequence? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardText = clipboard.primaryClip?.getItemAt(0)?.text
        }
        check(clipboardText?.contains("BIOMETRIC REPORT") == true) { "clipboard: $clipboardText" }

        // Share hands the report to Android's share sheet (intercepted, not really opened)
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            click(hasTestTag("share_export_btn"))
            intended(
                allOf(
                    hasAction(Intent.ACTION_CHOOSER),
                    hasExtra(
                        `is`(Intent.EXTRA_INTENT),
                        allOf(hasAction(Intent.ACTION_SEND), hasExtra(Intent.EXTRA_TEXT, containsString("BIOMETRIC REPORT")))
                    )
                )
            )
        } finally {
            Intents.release()
        }

        click(hasTestTag("close_export_dialog_btn"))
        check(!exists(hasTestTag("export_preview"))) { "export dialog still open" }
    }

    @Test fun languageCanBeChangedInTheAppAndIsRemembered() {
        launch()
        acceptWelcome()

        click(hasTestTag("language_btn"))
        click(hasTestTag("language_option_es"))

        // The screen is recreated in Spanish ("Historial biométrico" = history title).
        waitFor(hasText("Historial biométrico")).assertIsDisplayed()

        // Still Spanish after the app is closed and reopened.
        scenario?.close()
        launch()
        waitFor(hasText("Historial biométrico")).assertIsDisplayed()

        // "System default" goes back to the phone's language.
        click(hasTestTag("language_btn"))
        click(hasTestTag("language_option_system"))
        waitFor(hasText(text(R.string.measurement_history))).assertIsDisplayed()
    }

    @Test fun footerShowsRatePrivacyAndMedicalNotices() {
        launch()
        acceptWelcome()
        waitFor(hasTestTag("rate_app_btn")).assertIsDisplayed()
        waitFor(hasText(text(R.string.footer_privacy))).assertIsDisplayed()
        waitFor(hasText(text(R.string.disclaimer_footer))).assertIsDisplayed()
    }
}
