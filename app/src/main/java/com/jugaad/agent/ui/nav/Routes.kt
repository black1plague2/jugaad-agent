package com.jugaad.agent.ui.nav

object Routes {
    const val ASSETS = "assets"
    const val CREATE_ASSET = "create_asset"
    const val NETWORK = "network"
    const val GROUP_SETTINGS = "group_settings"

    const val ASSET_DETAIL = "asset/{assetId}"
    const val BASELINE = "baseline/{assetId}"
    const val DIAGNOSE = "diagnose/{assetId}"
    const val RESULT = "result/{assetId}/{diagnosisId}"
    const val HISTORY = "history/{assetId}"
    const val CHECKLIST = "checklist/{assetId}"

    fun assetDetail(assetId: String) = "asset/$assetId"
    fun baseline(assetId: String) = "baseline/$assetId"
    fun diagnose(assetId: String) = "diagnose/$assetId"
    fun result(assetId: String, diagnosisId: String) = "result/$assetId/$diagnosisId"
    fun history(assetId: String) = "history/$assetId"
    fun checklist(assetId: String) = "checklist/$assetId"
}
