package com.ayakix.pocketfm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayakix.pocketfm.radio.RtlTcpProtocol

/**
 * 選局画面。周波数の表示・変更、再生の開始/停止、音量とゲインの調整。
 *
 * 受信の開始は SDR ドライバのインテント往復を挟む必要があるため、
 * このコンポーザブルは [onRequestPlay] を呼ぶだけで、実際の起動は
 * Activity 側のランチャーが行う。
 */
@Composable
fun RadioScreen(
    viewModel: RadioViewModel,
    onRequestPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frequencyHz by viewModel.frequencyHz.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val gain by viewModel.gainTenthsDb.collectAsStateWithLifecycle()

    // safeDrawingPadding: targetSdk 35 の Android 15 では edge-to-edge が
    // 強制されるため、自分でシステムバーとカットアウトの分を避ける必要がある。
    // これが無いと画面下端の受信開始/停止ボタンがナビゲーションバーに隠れる。
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        // 操作系は縦に伸びるので、はみ出したらスクロールさせる。ボタンは
        // このスクロール領域の外に置き、画面下端に必ず見えるようにする。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FrequencyDisplay(
                frequencyHz = frequencyHz,
                isPlaying = isPlaying,
                onStep = viewModel::step,
            )

            DialSlider(
                frequencyHz = frequencyHz,
                onTune = viewModel::tune,
            )

            PresetRow(
                frequencyHz = frequencyHz,
                onTune = viewModel::tune,
            )

            Spacer(Modifier.height(4.dp))

            SliderRow(
                label = "音量",
                valueLabel = "${(volume * 100).toInt()}%",
                value = volume,
                onValueChange = viewModel::setVolume,
            )

            SliderRow(
                label = "チューナゲイン",
                valueLabel = "%.1f dB".format(gain / 10.0),
                value = gain.toFloat() / com.ayakix.pocketfm.TunerState.MAX_GAIN_TENTHS_DB,
                onValueChange = {
                    viewModel.setGain(
                        (it * com.ayakix.pocketfm.TunerState.MAX_GAIN_TENTHS_DB).toInt(),
                    )
                },
            )

            Text(
                // 上げすぎると前段が飽和して逆に歪む。強い局ほど下げる。
                text = "ノイズが多いときはゲインを上げ、音が割れるときは下げてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        PlayButton(
            isPlaying = isPlaying,
            onPlay = onRequestPlay,
            onStop = viewModel::stopPlayback,
        )
    }
}

@Composable
private fun FrequencyDisplay(
    frequencyHz: Int,
    isPlaying: Boolean,
    onStep: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "1 チャンネル下げる") { onStep(-1) }
                Text(
                    text = frequencyHz.toMhzLabel(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(200.dp),
                )
                StepButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "1 チャンネル上げる") { onStep(1) }
            }
            Text(
                text = if (isPlaying) "MHz · 受信中" else "MHz · 停止中",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun DialSlider(frequencyHz: Int, onTune: (Int) -> Unit) {
    Column {
        Slider(
            value = frequencyHz.toBandFraction(),
            onValueChange = { onTune(it.toBandFrequencyHz()) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                RtlTcpProtocol.FM_BAND_MIN_HZ.toMhzLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                RtlTcpProtocol.FM_BAND_MAX_HZ.toMhzLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetRow(frequencyHz: Int, onTune: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PRESETS.forEach { (name, hz) ->
            FilterChip(
                selected = frequencyHz == hz,
                onClick = { onTune(hz) },
                label = { Text("$name ${hz.toMhzLabel()}") },
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange)
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    FilledTonalButton(
        onClick = if (isPlaying) onStop else onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(if (isPlaying) "停止" else "受信開始", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 東京周辺のプリセット。ワイド FM (AM 局の FM 補完中継局) を含む。
 * 地域が違えば当然合わないので、いずれ利用者が編集できるようにしたい。
 */
private val PRESETS = listOf(
    "TBS" to 90_500_000,
    "文化" to 91_600_000,
    "ニッポン" to 93_000_000,
    "InterFM" to 89_700_000,
    "TOKYO FM" to 80_000_000,
    "J-WAVE" to 81_300_000,
    "NHK-FM" to 82_500_000,
    "bayfm" to 78_000_000,
    "NACK5" to 79_500_000,
    "FMヨコハマ" to 84_700_000,
)
