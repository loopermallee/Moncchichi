package com.teleprompter
import expo.modules.splashscreen.SplashScreenManager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import expo.modules.ReactActivityDelegateWrapper
import io.texne.g1.basis.service.G1DisplayService
import io.texne.g1.basis.service.protocol.IG1DisplayService

class MainActivity : ReactActivity() {
  private var displayService: IG1DisplayService? = null
  private var isServiceBound: Boolean = false

  private val displayServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      displayService = IG1DisplayService.Stub.asInterface(binder)
      isServiceBound = true
      Log.d(TAG, "Display service connected")
      displayService?.apply {
        setScrollSpeed(1.0f)
        displayText("Hello world")
      }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      displayService = null
      isServiceBound = false
      Log.d(TAG, "Display service disconnected")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Set the theme to AppTheme BEFORE onCreate to support
    // coloring the background, status bar, and navigation bar.
    // This is required for expo-splash-screen.
    // setTheme(R.style.AppTheme);
    // @generated begin expo-splashscreen - expo prebuild (DO NOT MODIFY) sync-f3ff59a738c56c9a6119210cb55f0b613eb8b6af
    SplashScreenManager.registerOnActivity(this)
    // @generated end expo-splashscreen
    super.onCreate(null)

    Intent(this, G1DisplayService::class.java).also {
      bindService(it, displayServiceConnection, Context.BIND_AUTO_CREATE)
    }
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "main"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate {
    return ReactActivityDelegateWrapper(
          this,
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
          object : DefaultReactActivityDelegate(
              this,
              mainComponentName,
              fabricEnabled
          ){})
  }

  /**
    * Align the back button behavior with Android S
    * where moving root activities to background instead of finishing activities.
    * @see <a href="https://developer.android.com/reference/android/app/Activity#onBackPressed()">onBackPressed</a>
    */
  override fun invokeDefaultOnBackPressed() {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
          if (!moveTaskToBack(false)) {
              // For non-root activities, use the default implementation to finish them.
              super.invokeDefaultOnBackPressed()
          }
          return
      }

      // Use the default back button implementation on Android S
      // because it's doing more than [Activity.moveTaskToBack] in fact.
      super.invokeDefaultOnBackPressed()
  }

  override fun onDestroy() {
      if (isServiceBound) {
          try {
              displayService?.stopDisplay()
              unbindService(displayServiceConnection)
          } catch (throwable: IllegalArgumentException) {
              Log.w(TAG, "Attempted to unbind display service that was not bound", throwable)
          }
          isServiceBound = false
      }
      displayService = null
      super.onDestroy()
  }

  companion object {
      private const val TAG = "MainActivity"
  }
}
