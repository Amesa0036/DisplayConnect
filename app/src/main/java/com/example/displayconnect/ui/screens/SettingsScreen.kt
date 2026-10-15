package com.example.displayconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.displayconnect.R
import com.example.displayconnect.ui.components.StatsCard
import com.example.displayconnect.ui.navigation.SettingsTopBar
import com.example.displayconnect.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val language by viewModel.language.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(onNavigateBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            SectionCard(title = stringResource(R.string.setting_language)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = language == "es", onClick = { viewModel.selectLanguage("es") }, label = { Text("Español") })
                    FilterChip(selected = language == "en", onClick = { viewModel.selectLanguage("en") }, label = { Text("English") })
                    FilterChip(selected = language == "pt-BR", onClick = { viewModel.selectLanguage("pt-BR") }, label = { Text("Português") })
                }
            }

            SectionCard(title = stringResource(R.string.setting_nav_hz, settings.navUpdateHz)) {
                Slider(
                    value = settings.navUpdateHz.toFloat(),
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(navUpdateHz = value.toInt()) }
                    },
                    valueRange = 1f..5f,
                    steps = 3
                )
                Text(
                    "Cuántas veces por segundo se manda la posición a la placa. Se aplica al iniciar la navegación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = stringResource(R.string.setting_map_scale, settings.mapScaleMeters.toInt())) {
                Slider(
                    value = settings.mapScaleMeters.toFloat(),
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(mapScaleMeters = value.toDouble()) }
                    },
                    valueRange = 150f..800f,
                    steps = 12
                )
                Text(
                    text = stringResource(R.string.setting_map_scale_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatsCard(stats = stats.copy(resolution = settings.resolutionLabel))
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}
