package tn.malak.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import tn.malak.app.ui.AmlakLaunch
import tn.malak.app.ui.Brand
import tn.malak.app.ui.MalakApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Amlak)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { view -> view.remove() }
        }
        window.statusBarColor = Brand.Pearl.toArgb()
        window.navigationBarColor = Brand.Pearl.toArgb()
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            AmlakLaunch(animate = savedInstanceState == null) { launchShowing ->
                MalakApp(launchShowing = launchShowing)
            }
        }
    }
}
