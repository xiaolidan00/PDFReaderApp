package com.xld.pdfreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xld.pdfreader.ui.navigation.AppNavGraph
import com.xld.pdfreader.ui.theme.PDFReaderTheme
import com.xld.pdfreader.ui.update.UpdateHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PDFReaderTheme {
                AppNavGraph()
                // 应用内升级弹窗，覆盖在任意页面之上
                UpdateHost()
            }
        }
    }
}
