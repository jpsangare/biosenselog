package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.ui.BiometricDashboard
import com.example.ui.BiometricViewModel
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  /** The Compose test host activity only exists in developer builds: skip before it is launched. */
  @get:Rule(order = 0) val developerBuildOnly = TestRule { base, _ ->
    object : Statement() {
      override fun evaluate() {
        Assume.assumeTrue("needs the developer build's Compose test host", BuildConfig.DEBUG)
        base.evaluate()
      }
    }
  }

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = BiometricViewModel(application)

    composeTestRule.setContent {
      MyApplicationTheme {
        BiometricDashboard(viewModel = viewModel)
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
