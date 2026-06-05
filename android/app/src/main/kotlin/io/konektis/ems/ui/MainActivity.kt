package io.konektis.ems.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.konektis.ems.EmsApplication
import io.konektis.ems.ui.theme.EmsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmsApplication
        setContent {
            EmsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    EmsNavHost(app)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as EmsApplication
        app.component.statusWsClient.reconnectNow()
        app.component.controlWsClient.reconnectNow()
    }
}
