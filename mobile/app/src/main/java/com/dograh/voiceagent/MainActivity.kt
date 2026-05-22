package com.dograh.voiceagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dograh.voiceagent.service.AgentService

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()

        setContent {
            var phoneNumber by remember { mutableStateOf("+919876543210") }
            var isAgentActive by remember { mutableStateOf(false) }
            var isCallActive by remember { mutableStateOf(false) }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF0F0C20), Color(0xFF15102A))
                            )
                        )
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "D O G R A H",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF03DAC6),
                        letterSpacing = 4.sp
                    )

                    Text(
                        text = "Autonomous On-Device Voice Agent\nfor Indian Telecom Markets",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Status Panel Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1C38))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "System Status",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Agent Loop Running:", color = Color.LightGray)
                                Text(
                                    text = if (isAgentActive) "Active" else "Inactive",
                                    color = if (isAgentActive) Color(0xFF03DAC6) else Color.Red,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Active Call Session:", color = Color.LightGray)
                                Text(
                                    text = if (isCallActive) "In Call" else "Idle",
                                    color = if (isCallActive) Color(0xFF03DAC6) else Color.Gray,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Number Input Field
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Indian Destination Number") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF03DAC6),
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color(0xFF03DAC6)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Controls
                    Button(
                        onClick = {
                            isAgentActive = !isAgentActive
                            if (isAgentActive) {
                                triggerServiceAction(AgentService.ACTION_START_AGENT)
                            } else {
                                triggerServiceAction(AgentService.ACTION_STOP_AGENT)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAgentActive) Color.Red else Color(0xFF6200EE)
                        )
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Agent Toggle")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAgentActive) "Deactivate Voice Agent" else "Activate Voice Agent",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            isCallActive = !isCallActive
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCallActive) Color.Red else Color(0xFF03DAC6)
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Call, contentDescription = "Call Controls")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCallActive) "Disconnect Current Call" else "Initiate Outbound Call",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "Regulatory Warning:Plays mandatory consent notifications and active recording beep intervals.",
                        fontSize = 11.sp,
                        color = Color.DarkGray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
}
