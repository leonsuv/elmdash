package com.elmdash.app.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elmdash.app.CorsaDashRuntime
import com.elmdash.core.telemetry.ConnectionState
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        CorsaDashRuntime.ensureInitialized(applicationContext)

        setContent {
            CorsaEvTheme {
                CompanionAppShell()
            }
        }
    }
}

private data class NavTab(
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionAppShell() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var isFullscreenEditor by remember { mutableStateOf(false) }
    val snapshot by CorsaDashRuntime.repository.snapshot.collectAsState()

    val tabs = remember {
        listOf(
            NavTab("Dash", EvIcons.Dashboard),
            NavTab("Trips", EvIcons.Route),
            NavTab("Diagnose", EvIcons.MonitorHeart),
            NavTab("Adapter", EvIcons.Bluetooth)
        )
    }

    Scaffold(
        containerColor = EvColors.Background,
        topBar = {
            if (!isFullscreenEditor) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = EvColors.Surface,
                        titleContentColor = EvColors.TextPrimary
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Corsa-e Dash",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            val (dotColor, label) = when (snapshot.connectionState) {
                                ConnectionState.LIVE -> EvColors.Emerald to String.format(Locale.US, "LIVE %.0f Hz", snapshot.pollRateHz)
                                ConnectionState.CONNECTING -> EvColors.Cyan to "CONNECTING"
                                ConnectionState.RECONNECTING -> EvColors.Amber to "RECONNECT"
                                ConnectionState.ERROR -> EvColors.Red to "ERROR"
                                ConnectionState.DISCONNECTED -> EvColors.TextSecondary to "OFFLINE"
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(dotColor.copy(alpha = 0.16f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Text(
                                    text = label,
                                    color = dotColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isFullscreenEditor) {
                NavigationBar(
                    containerColor = EvColors.Surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEachIndexed { idx, tab ->
                        NavigationBarItem(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(23.dp)
                                )
                            },
                            label = { Text(tab.title, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = EvColors.CyanLight,
                                selectedTextColor = EvColors.CyanLight,
                                indicatorColor = EvColors.Cyan.copy(alpha = 0.16f),
                                unselectedIconColor = EvColors.TextSecondary,
                                unselectedTextColor = EvColors.TextSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardBuilderTab(
                    isFullscreen = isFullscreenEditor,
                    onFullscreenChange = { isFullscreenEditor = it }
                )
                1 -> TripsTab()
                2 -> DiagnosticsTab()
                3 -> ConnectionTab()
            }
        }
    }
}
