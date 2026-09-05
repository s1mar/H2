package com.kin.familyhealth.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.SosOutlined
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kin.familyhealth.vitals.DashboardUiState
import com.kin.familyhealth.vitals.VitalsLoadState
import com.kin.familyhealth.vitals.VitalsViewModel
import com.kin.familyhealth.vitals.model.Vitals
import java.util.concurrent.TimeUnit

/**
 * AGENT-VITALS: dashboard route composable.
 *
 * The commander wires this into the NavGraph's `dashboard` route, supplying
 * a [VitalsViewModel.Factory] built with the real [com.kin.familyhealth.core.VitalsSync]
 * implementation from the sync package.
 *
 * Public signature the commander calls:
 * `EntryScreen(onOpenSettings: () -> Unit, onReachIn: () -> Unit, factory: VitalsViewModel.Factory)`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    onOpenSettings: () -> Unit,
    onReachIn: () -> Unit,
    factory: VitalsViewModel.Factory,
) {
    val viewModel: VitalsViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Kin",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        DashboardContent(
            uiState = uiState,
            onReachIn = onReachIn,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun DashboardContent(
    uiState: DashboardUiState,
    onReachIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ReachInButton(onClick = onReachIn) }
        item { PartnerSection(uiState) }
        item { MySection(uiState) }
    }
}

@Composable
private fun ReachInButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(Icons.Filled.SosOutlined, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(0.dp))
        Text(
            "  Reach in",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PartnerSection(uiState: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Their vitals",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        when (uiState.partnerState) {
            VitalsLoadState.LOADING -> LoadingCard("Checking on them…")
            VitalsLoadState.NO_PARTNER -> MessageCard(
                "Not paired yet",
                "Once you're paired, you'll see their latest vitals here.",
            )
            VitalsLoadState.NO_PERMISSION -> MessageCard(
                "Waiting for data",
                "Their phone hasn't shared vitals yet.",
            )
            VitalsLoadState.ERROR -> MessageCard(
                "Couldn't load their vitals",
                "We'll keep trying in the background.",
            )
            VitalsLoadState.LOADED -> {
                val vitals = uiState.partnerVitals
                if (vitals == null) {
                    MessageCard("Waiting for data", "Their phone hasn't shared vitals yet.")
                } else {
                    VitalsCardGrid(vitals)
                    LastUpdatedAndBattery(vitals)
                }
            }
        }
    }
}

@Composable
private fun MySection(uiState: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "How you're doing",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        when (uiState.myState) {
            VitalsLoadState.LOADING -> LoadingCard("Reading your watch data…")
            VitalsLoadState.NO_PERMISSION -> MessageCard(
                "Health Connect permission needed",
                "Grant Health Connect access in Settings to share your vitals.",
            )
            VitalsLoadState.NO_PARTNER -> MessageCard(
                "No data yet",
                "We'll read your vitals once your watch has synced.",
            )
            VitalsLoadState.ERROR -> MessageCard(
                "Couldn't read your vitals",
                "We'll try again shortly.",
            )
            VitalsLoadState.LOADED -> {
                val vitals = uiState.myVitals
                if (vitals == null) {
                    MessageCard("No data yet", "We'll read your vitals once your watch has synced.")
                } else {
                    MySummaryRow(vitals)
                }
            }
        }
    }
}

@Composable
private fun VitalsCardGrid(vitals: Vitals) {
    val cards = buildList {
        vitals.heartRateBpm?.let { add(VitalCardData(Icons.Filled.Favorite, "Heart rate", "$it bpm")) }
        vitals.restingHrBpm?.let { add(VitalCardData(Icons.Filled.MonitorHeart, "Resting HR", "$it bpm")) }
        vitals.steps?.let { add(VitalCardData(Icons.Filled.DirectionsWalk, "Steps today", "$it")) }
        vitals.sleepMinutes?.let {
            val hours = it / 60
            val mins = it % 60
            add(VitalCardData(Icons.Filled.Bedtime, "Sleep last night", "${hours}h ${mins}m"))
        }
        vitals.spo2Percent?.let { add(VitalCardData(Icons.Filled.Air, "SpO2", "${it.toInt()}%")) }
    }

    if (cards.isEmpty()) {
        MessageCard("No readings yet", "Their watch hasn't reported any vitals recently.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.height((((cards.size + 1) / 2) * 110).dp),
    ) {
        items(cards) { card -> VitalCard(card) }
    }
}

private data class VitalCardData(val icon: ImageVector, val label: String, val value: String)

@Composable
private fun VitalCard(data: VitalCardData) {
    Card(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                data.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(data.value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                data.label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastUpdatedAndBattery(vitals: Vitals) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Updated ${relativeTime(vitals.timestampEpochMs)}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vitals.batteryPct?.let { battery ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    " $battery%",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MySummaryRow(vitals: Vitals) {
    Card(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val parts = buildList {
                vitals.heartRateBpm?.let { add("Heart rate $it bpm") }
                vitals.steps?.let { add("$it steps") }
                vitals.sleepMinutes?.let { add("${it / 60}h ${it % 60}m sleep") }
                vitals.spo2Percent?.let { add("SpO2 ${it.toInt()}%") }
            }
            Text(
                if (parts.isEmpty()) "No readings yet" else parts.joinToString(" · "),
                fontSize = 16.sp,
            )
            Text(
                "Shared ${relativeTime(vitals.timestampEpochMs)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(message, fontSize = 16.sp)
        }
    }
}

@Composable
private fun MessageCard(title: String, body: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    if (diffMs < 0) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
