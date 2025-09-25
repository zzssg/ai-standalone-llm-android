package org.zzssg.llmchatapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModelSelectionScreen(
    onPickModel: () -> Unit,
    onUseMock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "LLM Chat App",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        Text(
            text = "Please select an option to continue:",
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        Button(
            onClick = onPickModel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(60.dp)
        ) {
            Text(
                text = "Select GGUF Model File",
                fontSize = 16.sp
            )
        }
        
        Button(
            onClick = onUseMock,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = "Use Mock Mode (No LLM)",
                fontSize = 16.sp
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Note: Select a GGUF model file from your device storage to enable real LLM functionality. " +
                   "The model file will be copied to app storage for processing.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}