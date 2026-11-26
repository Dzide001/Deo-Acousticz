package com.droidacoustic.pro.ui.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.droidacoustic.pro.scene.SceneViewModel

/** Surface the assumptions alongside numerical results, including an unavailable total. */
@Composable
internal fun SpeakerDataStatus(vm: SceneViewModel) {
    val recovery = vm.assetRecoveryProblems.collectAsState().value
    val speakers = vm.speakers.collectAsState().value
    val registry = vm.clfRegistry.collectAsState().value
    val sources = vm.clfSourceStatus.collectAsState().value
    val presets = vm.speakerPresets.collectAsState().value
    val band = vm.selectedBandHz.collectAsState().value
    val width = vm.signalBandwidthOct.collectAsState().value
    val type = vm.signalType.collectAsState().value
    val dispersion = vm.signalDispersionEnabled.collectAsState().value
    val warnings = remember(speakers, registry, sources, presets, band, width, type, dispersion, recovery) {
        vm.predictionDataWarnings()
    }
    warnings.forEach { warning ->
        Text(warning, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
