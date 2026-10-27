package com.xld.pdfreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xld.pdfreader.ui.navigation.AppNavGraph
import com.xld.pdfreader.ui.theme.PDFReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PDFReaderTheme {
                AppNavGraph()
            }
        }
    }
}
