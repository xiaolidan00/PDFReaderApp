package com.xld.pdfreader

import android.app.Application
import android.content.Context
import com.xld.pdfreader.data.PdfRepository

/**
 * 手动依赖注入容器，Application 持有单实例。
 */
class AppContainer(context: Context) {
    val repository: PdfRepository by lazy { PdfRepository(context) }
}

class PDFApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as PDFApplication).container
