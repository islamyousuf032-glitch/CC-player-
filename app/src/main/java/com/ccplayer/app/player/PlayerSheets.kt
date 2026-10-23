package com.ccplayer.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.core.media.export.ExportProfiles
import com.ccplayer.core.media.export.VideoCodec
import com.ccplayer.media.android.codec.CodecSupport
import com.ccplayer.media.android.codec.DeviceCodecCapabilities
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeSheet(
    grade: ColorGrade,
    onGradeChange: (ColorGrade) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Basic color grade", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Linear light • same matrix in preview and export",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onGradeChange(ColorGrade.IDENTITY) }) { Text("Reset") }
            }
            Spacer(Modifier.height(16.dp))
            GradeSlider(
                title = "Exposure",
                value = grade.exposureStops,
                range = -4f..4f,
                display = String.format(Locale.ROOT, "%+.2f EV", grade.exposureStops),
                onChange = { onGradeChange(grade.copy(exposureStops = it)) },
            )
            GradeSlider(
                title = "Contrast",
                value = grade.contrast,
                range = -1f..1f,
                display = String.format(Locale.ROOT, "%+.2f", grade.contrast),
                onChange = { onGradeChange(grade.copy(contrast = it)) },
            )
            GradeSlider(
                title = "Saturation",
                value = grade.saturation,
                range = 0f..2f,
                display = String.format(Locale.ROOT, "%.2f", grade.saturation),
                onChange = { onGradeChange(grade.copy(saturation = it)) },
            )
            GradeSlider(
                title = "Temperature (draft)",
                value = grade.temperature,
                range = -1f..1f,
                display = String.format(Locale.ROOT, "%+.2f", grade.temperature),
                onChange = { onGradeChange(grade.copy(temperature = it)) },
            )
            GradeSlider(
                title = "Tint (draft)",
                value = grade.tint,
                range = -1f..1f,
                display = String.format(Locale.ROOT, "%+.2f", grade.tint),
                onChange = { onGradeChange(grade.copy(tint = it)) },
            )
            Text(
                "Camera-specific input transforms, ACES/OCIO, LUTs, curves and scopes belong to the professional color stage; these controls do not claim to replace them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun GradeSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(display, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    selectedCodec: VideoCodec,
    isCodecEnabled: (VideoCodec) -> Boolean,
    onCodecSelected: (VideoCodec) -> Unit,
    onDismiss: () -> Unit,
    onChooseDestination: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Export graded video", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Single clip • source dimensions • device encoder • MP4",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            ExportProfiles.current.forEach { profile ->
                val enabled = isCodecEnabled(profile.codec)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedCodec == profile.codec,
                        onClick = { onCodecSelected(profile.codec) },
                        enabled = enabled,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(profile.codec.displayName, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (enabled) "Available on this device" else "No compatible encoder found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = false, onClick = {}, enabled = false)
                Column {
                    Text("ProRes 422 HQ / 4444", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Native FFmpeg export stage • not falsely routed through Media3",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onChooseDestination,
                enabled = isCodecEnabled(selectedCodec),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("Choose destination")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun CodecReportDialog(state: PlayerUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actual device codecs") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Queried from MediaCodecList. An entry here does not guarantee every resolution, profile or frame rate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val report = state.codecReport
                if (report == null) {
                    Text("Scanning device…")
                } else {
                    DeviceCodecCapabilities.commonVideoMimeTypes.forEach { mime ->
                        CodecSupportRow(report.support(mime))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CodecSupportRow(support: CodecSupport) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(support.mimeType, style = MaterialTheme.typography.titleSmall)
        Text(
            "Decode ${yesNo(support.canDecode)} (${hardwareText(support.hasHardwareDecoder)})  •  Encode ${yesNo(support.canEncode)} (${hardwareText(support.hasHardwareEncoder)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (support.components.isNotEmpty()) {
            Text(
                support.components.take(3).joinToString("\n") { component ->
                    "${if (component.isEncoder) "Enc" else "Dec"}: ${component.name}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
private fun hardwareText(value: Boolean): String = if (value) "hardware" else "software/none"
