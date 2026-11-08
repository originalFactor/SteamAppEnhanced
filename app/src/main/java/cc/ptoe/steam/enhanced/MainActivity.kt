package cc.ptoe.steam.enhanced

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import cc.ptoe.steam.enhanced.app.SteamAppState
import cc.ptoe.steam.enhanced.ui.SteamEnhancedApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val app = ViewModelProvider(this)[SteamAppState::class.java]
        setContent {
            SteamEnhancedApp(app)
        }
    }
}
