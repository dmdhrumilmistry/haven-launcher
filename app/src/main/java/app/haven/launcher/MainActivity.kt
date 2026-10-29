package app.haven.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.haven.launcher.ui.LauncherScreen
import app.haven.launcher.ui.LauncherViewModel
import app.haven.launcher.widgets.HomeWidgets

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private lateinit var widgets: HomeWidgets

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be created before the activity starts: it registers for activity results.
        widgets = HomeWidgets(this)
        widgets.restoreState(savedInstanceState)
        // Fully transparent bars over the wallpaper; icon colours are set per state in LauncherScreen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // A launcher is the bottom of the stack: Back must never close it. Screens inside register
        // their own handlers (drawer, sheets) which take priority over this one.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent { LauncherScreen(vm, widgets) }
    }

    override fun onStart() {
        super.onStart()
        widgets.onStart()
    }

    override fun onStop() {
        super.onStop()
        widgets.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        widgets.saveState(outState)
    }

    // Widget setup screens are started by AppWidgetHost, which only supports the classic result API.
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while we're already showing: close the drawer, sheets and search.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            vm.onHomePressed()
        }
    }
}
