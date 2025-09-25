package org.zzssg.llmchatapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun ChatScreen(isMockMode: Boolean = false) {
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var userInput by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }

    // Use LazyListState
    val scrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        // Messages list
        LazyColumn(
            state = scrollState,
            modifier = Modifier.weight(1f)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("Ask anything...") },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                enabled = !isTyping // Disable input while AI is thinking
            )

            IconButton(
                onClick = {
                    if (userInput.isBlank()) return@IconButton

                    // Add user message
                    val userMessage = Message(text = userInput, sender = "user")
                    messages = messages + userMessage
                    val currentInput = userInput
                    userInput = ""
                    isTyping = true
                    
                    // Generate AI response
                    scope.launch {
                        try {
                            val aiResponseText = generateAIResponse(currentInput, isMockMode)
                            val aiMessage = Message(text = aiResponseText, sender = "ai")
                            messages = messages + aiMessage
                        } catch (e: Exception) {
                            val errorMessage = Message(
                                text = "Error: ${e.message ?: e.javaClass.simpleName}. Please try again or check if the model is properly loaded.",
                                sender = "ai"
                            )
                            messages = messages + errorMessage
                        } finally {
                            isTyping = false
                        }
                    }
                },
                enabled = !isTyping // Disable button while AI is thinking
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send"
                )
            }
        }

        // Typing indicator
        if (isTyping) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Simple text indicator instead of Lottie animation
                Text("Thinking...", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            }
        }
    }
}

suspend fun generateAIResponse(prompt: String, isMockMode: Boolean): String = withContext(Dispatchers.IO) {
    // If we're in mock mode, return a mock response immediately
    if (isMockMode) {
        delay(1000) // Simulate some processing time
        return@withContext "This is a mock response to your prompt: \"$prompt\". In a real implementation with a loaded model, you would get an actual AI response here."
    }
    
    // Check if model is actually loaded
    if (!LlamaWrapper.nativeIsModelLoaded()) {
        throw Exception("Model is not loaded. Please load a valid GGUF model file or use mock mode.")
    }
    
    // Otherwise, use the actual LLM
    try {
        // Simple implementation that waits for the callback
        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                // Create callback object
                val callback = object : LlamaWrapper.LlamaCallback {
                    override fun onResult(result: String) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onError(error: String) {
                        if (continuation.isActive) {
                            // Provide more detailed error messages
                            val detailedError = when {
                                error.contains("not initialized", ignoreCase = true) || 
                                error.contains("not loaded", ignoreCase = true) -> {
                                    "Model not properly initialized. Please reload the GGUF model file."
                                }
                                error.contains("tokenization", ignoreCase = true) -> {
                                    "Failed to tokenize input. Please try with different input text."
                                }
                                error.contains("memory", ignoreCase = true) || 
                                error.contains("allocation", ignoreCase = true) -> {
                                    "Memory allocation error. The model might be too large for this device."
                                }
                                error.contains("decode", ignoreCase = true) -> {
                                    "Failed to process prompt. The model might be corrupted."
                                }
                                else -> {
                                    "Error during response generation: $error"
                                }
                            }
                            continuation.resumeWithException(Exception(detailedError))
                        }
                    }

                    override fun onProgress(progress: String) {
                        // We could update a progress indicator here if needed
                    }
                }

                // Call the native function
                LlamaWrapper.nativeGenerate(prompt, 128, callback)
            } catch (e: Throwable) {
                if (continuation.isActive) {
                    val detailedError = when {
                        e is OutOfMemoryError -> {
                            "Out of memory error. The model might be too large for this device."
                        }
                        e is SecurityException -> {
                            "Security error accessing model resources."
                        }
                        else -> {
                            "Unexpected error during generation: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                    continuation.resumeWithException(Exception(detailedError))
                }
            }
        }
    } catch (e: Exception) {
        // If all else fails, re-throw with a meaningful message
        throw Exception("Failed to generate response: ${e.message ?: e.javaClass.simpleName}")
    }
}