package com.xld.pdfreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xld.pdfreader.ui.list.PdfListScreen
import com.xld.pdfreader.ui.reader.PdfReaderScreen

object Routes {
    const val LIST = "list"
    const val READER = "reader/{docId}"
    fun reader(docId: String) = "reader/$docId"
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            PdfListScreen(
                onOpenDocument = { docId -> navController.navigate(Routes.reader(docId)) }
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("docId") { type = NavType.StringType })
        ) { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            PdfReaderScreen(
                docId = docId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
