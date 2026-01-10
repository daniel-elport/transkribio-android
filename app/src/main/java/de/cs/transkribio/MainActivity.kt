package de.cs.transkribio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.cs.transkribio.ui.RecordingDetailScreen
import de.cs.transkribio.ui.RecordingsListScreen
import de.cs.transkribio.ui.theme.TranskribioTheme

sealed class Screen(val route: String) {
    object RecordingsList : Screen("recordings_list")
    object RecordingDetail : Screen("recording_detail/{recordingId}") {
        fun createRoute(recordingId: Long) = "recording_detail/$recordingId"
    }
    object NewRecording : Screen("new_recording")
}

class MainActivity : ComponentActivity() {

    private val transcriptionViewModel: TranscriptionViewModel by viewModels()
    private val recordingsViewModel: RecordingsViewModel by viewModels()
    private var showPermissionRationale by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionRationale = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAudioPermission()

        setContent {
            TranskribioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.RecordingsList.route
                    ) {
                        composable(Screen.RecordingsList.route) {
                            RecordingsListScreen(
                                viewModel = recordingsViewModel,
                                onNewRecording = {
                                    transcriptionViewModel.clearTranscription()
                                    transcriptionViewModel.startNewRecording()
                                    navController.navigate(Screen.NewRecording.route)
                                },
                                onOpenRecording = { recordingId ->
                                    transcriptionViewModel.loadRecording(recordingId)
                                    navController.navigate(Screen.RecordingDetail.createRoute(recordingId))
                                },
                                onResumeRecording = { recordingId ->
                                    transcriptionViewModel.resumeRecording(recordingId)
                                    navController.navigate(Screen.RecordingDetail.createRoute(recordingId))
                                }
                            )
                        }

                        composable(
                            route = Screen.RecordingDetail.route,
                            arguments = listOf(
                                navArgument("recordingId") { type = NavType.LongType }
                            )
                        ) { backStackEntry ->
                            val recordingId = backStackEntry.arguments?.getLong("recordingId") ?: 0L
                            RecordingDetailScreen(
                                viewModel = transcriptionViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onResumeRecording = {
                                    transcriptionViewModel.resumeRecording(recordingId)
                                }
                            )
                        }

                        composable(Screen.NewRecording.route) {
                            RecordingDetailScreen(
                                viewModel = transcriptionViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onResumeRecording = {
                                    // Already recording or just created
                                }
                            )
                        }
                    }

                    if (showPermissionRationale) {
                        PermissionRationaleDialog(
                            onConfirm = {
                                showPermissionRationale = false
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onDismiss = {
                                showPermissionRationale = false
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionRationale = true
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
private fun PermissionRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Microphone Permission Required")
        },
        text = {
            Text(
                text = "This app needs microphone access to transcribe your speech. " +
                       "Please grant the permission to use the transcription feature."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
