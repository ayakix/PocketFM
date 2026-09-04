package com.ayakix.pocketfm.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * アナログチューナのダイヤルを意識した固定パレット。暗い機器色の面に、
 * 目盛りと指針を思わせるアンバーを載せる。壁紙由来の Dynamic Color だと
 * 「ダイヤルの指針」の色が端末ごとに変わって意味が薄れるため、あえて固定色。
 */

// 共通アクセント
val DialAmber = Color(0xFFFFB86B)      // 指針・プライマリ
val DialAmberDim = Color(0xFF5A3B18)   // プライマリコンテナ (ダーク)
val DialTeal = Color(0xFF6BD5C4)       // セカンダリ (受信状態など)
val DialTealDim = Color(0xFF1C4A44)

// ダーク面
val NightBackground = Color(0xFF141019)
val NightSurface = Color(0xFF1C1822)
val NightSurfaceHigh = Color(0xFF272130)
val NightOutline = Color(0xFF564D5F)
val NightOnSurface = Color(0xFFEBE2E6)
val NightOnSurfaceVariant = Color(0xFFA9A0AC)

// ライト面 (同系統の色相で統一)
val DayPrimary = Color(0xFF8A5100)
val DayPrimaryContainer = Color(0xFFFFDCBE)
val DaySecondary = Color(0xFF00695C)
val DaySecondaryContainer = Color(0xFFB2DFD8)
val DayBackground = Color(0xFFFCF8FA)
val DaySurface = Color(0xFFFCF8FA)
val DaySurfaceHigh = Color(0xFFF1EAEE)
val DayOutline = Color(0xFF7C7480)
val DayOnSurface = Color(0xFF1D1A20)
val DayOnSurfaceVariant = Color(0xFF4A454C)
