package io.konektis.ems.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.konektis.ems.EmsApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmsApplication
        setContent {
            MaterialTheme {
                EmsNavHost(app)
            }
        }
    }
}
