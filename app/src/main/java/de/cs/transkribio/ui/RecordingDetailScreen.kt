package de.cs.transkribio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.cs.transkribio.TranscriptionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    viewModel: TranscriptionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember(uiState.currentRecordingName) { mutableStateOf(uiState.currentRecordingName) }

    // Handle back navigation - stop recording if active
    val handleBack = {
        if (uiState.isRecording) {
            viewModel.stopRecording()
        }
        onNavigateBack()
    }

    // Handle system back button
    BackHandler(onBack = handleBack)

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
                    IconButton(onClick = handleBack) {
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
            when {
                uiState.isInitializing || uiState.isStartingRecording -> {
                    // Show loading indicator while initializing or starting
                    FloatingActionButton(
                        onClick = { },
                        modifier = Modifier.size(80.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                    }
                }
                uiState.isRecording -> {
                    RecordButton(
                        onClick = { viewModel.stopRecording() }
                    )
                }
                uiState.isProcessing -> {
                    // Show processing indicator after stopping but still processing
                    FloatingActionButton(
                        onClick = { },
                        modifier = Modifier.size(80.dp),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onTertiary,
                            strokeWidth = 3.dp
                        )
                    }
                }
                else -> {
                    FloatingActionButton(
                        onClick = { viewModel.resumeCurrentRecording() },
                        modifier = Modifier.size(80.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Start Recording",
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Info bar - always show when recording, processing, or have segments
            AnimatedVisibility(
                visible = uiState.transcriptionHistory.isNotEmpty() || uiState.isRecording || uiState.isProcessing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                InfoBar(
                    segmentCount = uiState.transcriptionHistory.size,
                    isRecording = uiState.isRecording,
                    isProcessing = uiState.isProcessing
                )
            }

            // Recording waveform - only when actively recording
            AnimatedVisibility(
                visible = uiState.isRecording,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
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
                    RecordingStatus(duration = uiState.recordingDurationMs)
                }
            }

            // Transcription content
            if (uiState.transcriptionHistory.isEmpty()) {
                EmptyDetailState(
                    isRecording = uiState.isRecording,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Group segments into paragraphs for better readability
                val paragraphs = remember(uiState.transcriptionHistory) {
                    groupIntoParagraphs(uiState.transcriptionHistory.map { it.text })
                }

                // Update scroll target based on paragraph count
                LaunchedEffect(paragraphs.size) {
                    if (paragraphs.isNotEmpty()) {
                        listState.animateScrollToItem(paragraphs.size - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = paragraphs,
                        key = { index, _ -> index }
                    ) { index, paragraphText ->
                        TranscriptionParagraph(
                            text = paragraphText,
                            isLatest = index == paragraphs.size - 1 && uiState.isRecording
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
    isRecording: Boolean,
    isProcessing: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val processingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "processingAlpha"
    )

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$segmentCount segments",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = isRecording || isProcessing,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRecording) {
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
                    } else if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(processingAlpha)
                                .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PROCESSING",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alpha(processingAlpha)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStatus(duration: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color.Red, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(duration),
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
private fun TranscriptionParagraph(
    text: String,
    isLatest: Boolean
) {
    AnimatedVisibility(
        visible = true,
        enter = if (isLatest) fadeIn() + slideInVertically { it / 2 } else fadeIn()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 28.sp,
                    letterSpacing = 0.15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/**
 * Groups transcription segments into paragraphs for better readability.
 * Creates a new paragraph every ~4 sentences or at natural topic breaks.
 */
private fun groupIntoParagraphs(segments: List<String>, maxSentencesPerParagraph: Int = 4): List<String> {
    if (segments.isEmpty()) return emptyList()
    if (segments.size == 1) return segments

    val paragraphStarters = listOf(
        "Erstens", "Zweitens", "Drittens", "Viertens",
        "Zunächst", "Dann", "Danach", "Schließlich", "Abschließend",
        "Außerdem", "Darüber hinaus", "Des Weiteren", "Ferner",
        "Jedoch", "Allerdings", "Dennoch", "Trotzdem",
        "Also", "Zusammenfassend", "Insgesamt", "Letztendlich",
        "Einerseits", "Andererseits",
        "Zum einen", "Zum anderen",
        "Im Gegensatz", "Im Vergleich",
        "Beispielsweise", "Zum Beispiel",
        "Das bedeutet", "Das heißt",
        "Wichtig ist", "Interessant ist", "Bemerkenswert ist"
    )

    val sentenceEndPattern = Regex("""[.!?]+\s*""")
    val paragraphs = mutableListOf<StringBuilder>()
    var currentParagraph = StringBuilder()
    var sentenceCount = 0

    for (segment in segments) {
        val startsNewParagraph = paragraphStarters.any {
            segment.startsWith(it, ignoreCase = true)
        }

        if (startsNewParagraph && currentParagraph.isNotEmpty()) {
            paragraphs.add(currentParagraph)
            currentParagraph = StringBuilder()
            sentenceCount = 0
        }

        if (currentParagraph.isNotEmpty()) {
            currentParagraph.append(" ")
        }
        currentParagraph.append(segment)

        // Count sentences in this segment
        sentenceCount += sentenceEndPattern.findAll(segment).count().coerceAtLeast(1)

        // Start new paragraph after max sentences
        if (sentenceCount >= maxSentencesPerParagraph) {
            paragraphs.add(currentParagraph)
            currentParagraph = StringBuilder()
            sentenceCount = 0
        }
    }

    if (currentParagraph.isNotEmpty()) {
        paragraphs.add(currentParagraph)
    }

    return paragraphs.map { it.toString().trim() }
}

@Composable
private fun RecordButton(
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(80.dp),
        containerColor = MaterialTheme.colorScheme.error,
        shape = CircleShape
    ) {
        Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = "Stop recording",
            modifier = Modifier.size(36.dp),
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
