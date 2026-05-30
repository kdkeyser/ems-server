package io.konektis.ems.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.konektis.ems.EmsApplication
import io.konektis.ems.ui.theme.EmsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmsApplication
        setContent {
            EmsTheme {
                EmsNavHost(app)
            }
        }
    }
}
