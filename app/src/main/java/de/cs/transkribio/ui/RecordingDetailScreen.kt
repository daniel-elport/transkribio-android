package de.cs.transkribio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.cs.transkribio.TranscriptionState
import de.cs.transkribio.TranscriptionViewModel
import java.text.SimpleDateFormat
import java.util.*

private val SpeakerColors = listOf(
    Color(0xFF2196F3),
    Color(0xFF4CAF50),
    Color(0xFFFF9800),
    Color(0xFF9C27B0),
    Color(0xFFE91E63),
    Color(0xFF00BCD4),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    viewModel: TranscriptionViewModel,
    onNavigateBack: () -> Unit,
    onResumeRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember(uiState.currentRecordingName) { mutableStateOf(uiState.currentRecordingName) }

    // Auto-scroll when recording
    LaunchedEffect(uiState.transcriptionHistory.size) {
        if (uiState.transcriptionHistory.isNotEmpty()) {
            listState.animateScrollToItem(uiState.transcriptionHistory.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingName) {
                        BasicTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(
                                text = uiState.currentRecordingName.ifEmpty { "Recording" },
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            if (uiState.recordingDurationMs > 0) {
                                Text(
                                    text = formatDuration(uiState.recordingDurationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (isEditingName) {
                        IconButton(
                            onClick = {
                                viewModel.updateRecordingName(editedName)
                                isEditingName = false
                            }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                        IconButton(
                            onClick = {
                                editedName = uiState.currentRecordingName
                                isEditingName = false
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    } else {
                        IconButton(onClick = { isEditingName = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit name")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (!uiState.isRecording) {
                FloatingActionButton(
                    onClick = onResumeRecording,
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Resume Recording",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                RecordButton(
                    isRecording = true,
                    isEnabled = true,
                    onClick = { viewModel.stopRecording() }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Info bar
            if (uiState.speakerCount > 0 || uiState.transcriptionHistory.isNotEmpty()) {
                InfoBar(
                    segmentCount = uiState.transcriptionHistory.size,
                    speakerCount = uiState.speakerCount,
                    isRecording = uiState.isRecording,
                    isDiarizing = uiState.isDiarizing
                )
            }

            // Recording waveform when active
            if (uiState.isRecording) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CompactWaveform(
                        amplitudes = uiState.waveformAmplitudes,
                        modifier = Modifier.fillMaxWidth(0.9f),
                        barColor = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RecordingStatus(
                        isProcessing = uiState.isProcessing,
                        duration = uiState.recordingDurationMs
                    )
                }
            }

            // Transcription content
            if (uiState.transcriptionHistory.isEmpty()) {
                EmptyDetailState(
                    isRecording = uiState.isRecording,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = uiState.transcriptionHistory,
                        key = { _, segment -> segment.timestamp }
                    ) { index, segment ->
                        TranscriptionSegmentItem(
                            text = segment.text,
                            speakerId = segment.speakerId,
                            isNew = index == uiState.transcriptionHistory.size - 1 && uiState.isRecording
                        )
                    }
                }
            }

            // Diarization indicator
            if (uiState.isDiarizing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Identifying speakers...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBar(
    segmentCount: Int,
    speakerCount: Int,
    isRecording: Boolean,
    isDiarizing: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (segmentCount > 0) {
                InfoChip(
                    icon = Icons.Default.TextSnippet,
                    text = "$segmentCount segments"
                )
            }

            if (speakerCount > 0) {
                InfoChip(
                    icon = Icons.Default.People,
                    text = "$speakerCount speaker${if (speakerCount > 1) "s" else ""}",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Red, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "REC",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun RecordingStatus(
    isProcessing: Boolean,
    duration: Long
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (isProcessing) MaterialTheme.colorScheme.tertiary else Color.Red,
                    CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isProcessing) "Processing..." else formatDuration(duration),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyDetailState(
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isRecording) "Listening..." else "Tap the microphone to continue recording",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
private fun TranscriptionSegmentItem(
    text: String,
    speakerId: Int,
    isNew: Boolean
) {
    val speakerColor = if (speakerId >= 0) {
        SpeakerColors[speakerId % SpeakerColors.size]
    } else {
        null
    }

    AnimatedVisibility(
        visible = true,
        enter = if (isNew) fadeIn() + slideInVertically { it / 2 } else fadeIn()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Speaker indicator
                if (speakerColor != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp, end = 12.dp)
                            .size(8.dp)
                            .background(speakerColor, CircleShape)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (speakerId >= 0) {
                        Text(
                            text = "Speaker ${speakerId + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = speakerColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        containerColor = MaterialTheme.colorScheme.error,
        shape = CircleShape
    ) {
        Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = "Stop recording",
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onError
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = durationMs / (1000 * 60 * 60)

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}
