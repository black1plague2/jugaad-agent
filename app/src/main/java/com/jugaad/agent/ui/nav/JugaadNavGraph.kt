package com.jugaad.agent.ui.nav

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jugaad.agent.ui.assetdetail.AssetDetailScreen
import com.jugaad.agent.ui.assets.AssetListScreen
import com.jugaad.agent.ui.baseline.BaselineScreen
import com.jugaad.agent.ui.checklist.ChecklistScreen
import com.jugaad.agent.ui.createasset.CreateAssetScreen
import com.jugaad.agent.ui.diagnose.DiagnoseScreen
import com.jugaad.agent.ui.history.HistoryScreen
import com.jugaad.agent.ui.result.ResultScreen

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
@Composable
fun JugaadNavGraph(nav: NavHostController) {

    NavHost(navController = nav, startDestination = Routes.ASSETS) {

        composable(Routes.ASSETS) {
            AssetListScreen(
                onOpenAsset = { id -> nav.navigate(Routes.assetDetail(id)) },
                onCreate = { nav.navigate(Routes.CREATE_ASSET) },
            )
        }

        composable(Routes.CREATE_ASSET) {
            CreateAssetScreen(
                onCreated = { id ->
                    nav.navigate(Routes.assetDetail(id)) {
                        popUpTo(Routes.CREATE_ASSET) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            Routes.ASSET_DETAIL,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("assetId")!!
            AssetDetailScreen(
                assetId = id,
                onBack = { nav.popBackStack() },
                onCaptureBaseline = { nav.navigate(Routes.baseline(id)) },
                onDiagnose = { nav.navigate(Routes.diagnose(id)) },
                onHistory = { nav.navigate(Routes.history(id)) },
                onChecklist = { nav.navigate(Routes.checklist(id)) },
                onDeleted = { nav.popBackStack(Routes.ASSETS, inclusive = false) },
            )
        }

        composable(
            Routes.BASELINE,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("assetId")!!
            BaselineScreen(
                assetId = id,
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            Routes.CHECKLIST,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("assetId")!!
            ChecklistScreen(
                assetId = id,
                onProceed = {
                    nav.navigate(Routes.diagnose(id)) {
                        popUpTo(Routes.checklist(id)) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            Routes.DIAGNOSE,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("assetId")!!
            DiagnoseScreen(
                assetId = id,
                onResult = { assetId, diagnosisId ->
                    nav.navigate(Routes.result(assetId, diagnosisId)) {
                        popUpTo(Routes.diagnose(id)) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            Routes.RESULT,
            arguments = listOf(
                navArgument("assetId") { type = NavType.StringType },
                navArgument("diagnosisId") { type = NavType.StringType },
            ),
        ) { entry ->
            val assetId = entry.arguments!!.getString("assetId")!!
            val diagnosisId = entry.arguments!!.getString("diagnosisId")!!
            ResultScreen(
                assetId = assetId,
                diagnosisId = diagnosisId,
                onDone = { nav.popBackStack(Routes.assetDetail(assetId), inclusive = false) },
                onHistory = {
                    nav.navigate(Routes.history(assetId)) {
                        popUpTo(Routes.assetDetail(assetId)) { inclusive = false }
                    }
                },
            )
        }

        composable(
            Routes.HISTORY,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("assetId")!!
            HistoryScreen(
                assetId = id,
                onOpen = { diagId -> nav.navigate(Routes.result(id, diagId)) },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
