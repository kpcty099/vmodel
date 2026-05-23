package com.dograh.voiceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dograh.voiceagent.service.*
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Permissions are checked lazily by the service and calling UI.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()

        setContent {
            // Collect the unified StateStore cleanly from our foreground service
            val uiState by AgentService.uiState.collectAsState()

            ConsoleDashboardScreen(
                state = uiState,
                onToggleAgent = { isActive ->
                    if (isActive) {
                        triggerServiceAction(AgentService.ACTION_START_AGENT)
                    } else {
                        triggerServiceAction(AgentService.ACTION_STOP_AGENT)
                    }
                },
                onTriggerCall = { phoneNumber, isCallActive ->
                    if (isCallActive) {
                        val intent = Intent(this@MainActivity, AgentService::class.java).apply {
                            action = AgentService.ACTION_PLACE_CALL
                            putExtra(AgentService.EXTRA_PHONE_NUMBER, phoneNumber)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else {
                        triggerServiceAction(AgentService.ACTION_HANGUP_CALL)
                    }
                },
                onTriggerSimulatedPush = {
                    simulateIncomingVoIpPush()
                },
                onClearMemory = {
                    // Instantiates DB memory helper and wipes embeddings
                    try {
                        val memory = CallMemory(this@MainActivity)
                        memory.writableDatabase.execSQL("DELETE FROM calls")
                        memory.writableDatabase.execSQL("DELETE FROM vec_calls")
                        memory.close()
                        Log.d("MainActivity", "Local Vector database wiped successfully")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to clear memory: ${e.message}")
                    }
                }
            )
        }
    }

    private fun checkPermissions() {
        val requiredPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun triggerServiceAction(actionName: String) {
        val intent = Intent(this, AgentService::class.java).apply {
            action = actionName
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun simulateIncomingVoIpPush() {
        Log.d("MainActivity", "Triggering local simulated FCM cold-start push")
        val broadcastIntent = Intent().apply {
            action = "com.dograh.voiceagent.DEBUG_SIMULATE_INCOMING_CALL"
            putExtra("call_id", "sim-main-001")
            putExtra("caller_number", "+919876543210")
            setClassName(packageName, "com.dograh.voiceagent.debug.IncomingCallSimulationReceiver")
        }
        sendBroadcast(broadcastIntent)
    }
}

// ==========================================
// OPERATIONAL CONSOLE COMPOSE UI MODULES
// ==========================================

@Composable
fun ConsoleDashboardScreen(
    state: AgentUiState,
    onToggleAgent: (Boolean) -> Unit,
    onTriggerCall: (String, Boolean) -> Unit,
    onTriggerSimulatedPush: () -> Unit,
    onClearMemory: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf("+919876543210") }
    var isAgentActive by remember { mutableStateOf(false) }
    var isCallActive by remember { mutableStateOf(false) }

    // Sync switch variables when service modifies states
    isCallActive = state.callState != CallState.HANGUP && state.sipState != SipState.DISCONNECTED
    isAgentActive = state.sipState != SipState.DISCONNECTED

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E676),
            secondary = Color(0xFF00E5FF),
            background = Color(0xFF0C0E14),
            surface = Color(0xFF151922)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Panel
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "D O G R A H",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676),
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "VOICE CONSOLE - TELECOM NOC",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF5A697A)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1B222E), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "OFFLINE RAG",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }

                // Section 1: System Health Strip
                HealthGrid(state = state)

                // Section 2: Call state Timeline
                Text(
                    text = "CALL SEQUENCE PROGRESSION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5A697A),
                    modifier = Modifier.align(Alignment.Start)
                )
                CallTimeline(currentState = state.callState)

                // Section 3: Amplitude Waveform
                WaveformVisualizer(vadState = state.vadState)

                // Section 4: Live Transcript panel
                Text(
                    text = "REAL-TIME TELEMETRY LOGS & CONVERSATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5A697A),
                    modifier = Modifier.align(Alignment.Start)
                )
                TranscriptFeed(transcript = state.transcript, modifier = Modifier.weight(1f))

                // Section 5: Trigger Controls
                ControlConsole(
                    phoneNumber = phoneNumber,
                    onPhoneChange = { phoneNumber = it },
                    isAgentActive = isAgentActive,
                    isCallActive = isCallActive,
                    onToggleAgent = {
                        isAgentActive = !isAgentActive
                        onToggleAgent(isAgentActive)
                    },
                    onTriggerCall = {
                        isCallActive = !isCallActive
                        onTriggerCall(phoneNumber, isCallActive)
                    },
                    onTriggerSimulatedPush = onTriggerSimulatedPush,
                    onClearMemory = onClearMemory
                )
            }
        }
    }
}

@Composable
fun HealthGrid(state: AgentUiState) {
    val columns = listOf(
        Triple("SIP STATUS", if (state.sipState == SipState.REGISTERED) "REGISTERED" else if (state.sipState == SipState.REGISTERING) "REGISTERING" else "IDLE", if (state.sipState == SipState.REGISTERED) Color(0xFF00E676) else if (state.sipState == SipState.REGISTERING) Color(0xFFFFB300) else Color(0xFFFF5252)),
        Triple("ASR ENGINE", "GGML-INT8", Color(0xFF00E5FF)),
        Triple("TTS PIPER", "RAW-16KHZ", Color(0xFF00E5FF)),
        Triple("VAD ENGINE", if (state.vadState == VadState.USER_SPEAKING) "USER TALKING" else if (state.vadState == VadState.AGENT_SPEAKING) "AGENT TALKING" else "LISTENING", if (state.vadState != VadState.SILENT) Color(0xFF00E676) else Color(0xFF5A697A)),
        Triple("FCM WAKE", "ENABLED", Color(0xFF00E676)),
        Triple("RAG CACHE", "${state.metrics.memoryUsageMb} MB", Color(0xFF00E5FF))
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 0..2) {
                HealthBlock(title = columns[i].first, value = columns[i].second, accentColor = columns[i].third, modifier = Modifier.weight(1f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 3..5) {
                HealthBlock(title = columns[i].first, value = columns[i].second, accentColor = columns[i].third, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun HealthBlock(title: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5A697A))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(6.dp).background(accentColor, shape = CircleShape))
                Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
fun CallTimeline(currentState: CallState) {
    val steps = listOf(
        CallState.GREETING to "GREET",
        CallState.CONSENT to "CONSENT",
        CallState.DIALOGUE to "TALK",
        CallState.ESCALATION to "XFER",
        CallState.HANGUP to "END"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131722), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (state, label) ->
            val isActive = state == currentState
            val isPassed = steps.indexOfFirst { it.first == currentState } > index || currentState == CallState.HANGUP

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(
                            color = when {
                                isActive -> Color(0xFF00E676)
                                isPassed -> Color(0xFF00E676).copy(alpha = 0.4f)
                                else -> Color(0xFF1E222D)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPassed && !isActive) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = Color.Black
                        )
                    } else {
                        Text(
                            text = (index + 1).toString(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) Color.Black else Color.Gray
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.White else Color(0xFF5A697A)
                )
            }

            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (isPassed) Color(0xFF00E676).copy(alpha = 0.4f) else Color(0xFF1E222D))
                        .offset(y = (-8).dp)
                )
            }
        }
    }
}

@Composable
fun WaveformVisualizer(vadState: VadState) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Select wave stats based on simulated or actual VAD states
    val (waveColor, amplitude, strokeWidth) = when (vadState) {
        VadState.USER_SPEAKING -> Triple(Color(0xFF00E676), 40f, 4f)
        VadState.AGENT_SPEAKING -> Triple(Color(0xFF00E5FF), 25f, 3.5f)
        VadState.RECONNECTING -> Triple(Color(0xFFFFB300), 12f, 2.5f)
        VadState.SILENT -> Triple(Color(0xFF37474F), 6f, 2f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF0C0E14), shape = RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF131722), shape = RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val path = Path()

            path.moveTo(0f, height / 2f)
            val points = 80
            for (i in 0..points) {
                val x = (i.toFloat() / points) * width
                val angle = (i.toFloat() / points) * 3f * Math.PI.toFloat() + phase
                val y = height / 2f + sin(angle) * amplitude
                
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = waveColor,
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

@Composable
fun TranscriptFeed(transcript: List<TranscriptEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Automatically scroll to the latest bubble on append
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF131722), shape = RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1E222D), shape = RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        if (transcript.isEmpty()) {
            Text(
                text = "Console Idle. Outbound calling sequence triggers will register live text here.",
                fontSize = 11.sp,
                color = Color(0xFF5A697A),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(transcript) { entry ->
                    TranscriptBubble(entry = entry)
                }
            }
        }
    }
}

@Composable
fun TranscriptBubble(entry: TranscriptEntry) {
    val isUser = entry.speaker == "User"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = "${entry.speaker.uppercase()} - ${entry.language}",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5A697A),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Box(
            modifier = Modifier
                .background(
                    color = if (isUser) Color(0xFF1F2E26) else Color(0xFF1D2833),
                    shape = RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 8.dp,
                        bottomStart = if (isUser) 8.dp else 0.dp,
                        bottomEnd = if (isUser) 0.dp else 8.dp
                    )
                )
                .border(
                    width = 1.dp,
                    color = if (isUser) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF00E5FF).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 8.dp,
                        bottomStart = if (isUser) 8.dp else 0.dp,
                        bottomEnd = if (isUser) 0.dp else 8.dp
                    )
                )
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = entry.text, fontSize = 12.sp, color = Color.White)
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(9.dp),
                        tint = Color(0xFF5A697A)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "latency: ${entry.latencyMs}ms",
                        fontSize = 8.sp,
                        color = Color(0xFF5A697A)
                    )
                }
            }
        }
    }
}

@Composable
fun ControlConsole(
    phoneNumber: String,
    onPhoneChange: (String) -> Unit,
    isAgentActive: Boolean,
    isCallActive: Boolean,
    onToggleAgent: () -> Unit,
    onTriggerCall: () -> Unit,
    onTriggerSimulatedPush: () -> Unit,
    onClearMemory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Outlined prefilled phone destination
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = onPhoneChange,
                label = { Text("Tel Destination") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF1E222D),
                    focusedLabelColor = Color(0xFF00E5FF),
                    unfocusedLabelColor = Color(0xFF5A697A)
                )
            )

            // Direct Call Trigger button
            Button(
                onClick = onTriggerCall,
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCallActive) Color(0xFFFF5252) else Color(0xFF00E676)
                )
            ) {
                Icon(
                    imageVector = if (isCallActive) Icons.Default.CallEnd else Icons.Default.Call,
                    contentDescription = null,
                    tint = Color.Black
                )
            }
        }

        // Telecom Diagnostics row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Toggle active loop
            Button(
                onClick = onToggleAgent,
                modifier = Modifier.weight(1.2f).height(40.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAgentActive) Color(0xFF1A1A24) else Color(0xFF131722)
                ),
                border = BorderStroke(1.dp, Color(0xFF1E222D))
            ) {
                Text(
                    text = if (isAgentActive) "DEACTIVATE" else "ACTIVATE AGENT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAgentActive) Color(0xFFFF5252) else Color(0xFF00E676)
                )
            }

            // Simulate incoming push
            Button(
                onClick = onTriggerSimulatedPush,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF131722)),
                border = BorderStroke(1.dp, Color(0xFF1E222D))
            ) {
                Text(
                    text = "SIM INCOMING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Wipe DB Memory
            Button(
                onClick = onClearMemory,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF131722)),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, Color(0xFF1E222D))
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear DB Memory",
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFFF5252)
                )
            }
        }
    }
}
