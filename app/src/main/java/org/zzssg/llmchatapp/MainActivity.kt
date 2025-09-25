package org.zzssg.llmchatapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    // Use mutableStateOf to ensure Compose recomposes when this changes
    private var isModelInitializedState = mutableStateOf(false)
    private var isMockModeState = mutableStateOf(false)
    private var isModelLoadingState = mutableStateOf(false)
    private var modelPath: String? = null
    
    // Register the file picker activity
    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                isModelLoadingState.value = true
                try {
                    // Get the real path of the selected file
                    val modelPath = withContext(Dispatchers.IO) {
                        getRealPathFromURI(uri)
                    }
                    
                    if (modelPath == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: Could not access selected file. Please try a different file location.", Toast.LENGTH_LONG).show()
                            isModelLoadingState.value = false
                        }
                        return@launch
                    }
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Initializing model from: $modelPath", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Initialize the model in background thread
                    val result = withContext(Dispatchers.IO) {
                        try {
                            LlamaWrapper.nativeInitModel(modelPath)
                        } catch (e: Throwable) {
                            // Catch any native crashes
                            "Error: Native crash occurred during model initialization - ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (result == "Success") {
                            isModelInitializedState.value = true
                            isMockModeState.value = false
                            this@MainActivity.modelPath = modelPath
                            Toast.makeText(this@MainActivity, "Model loaded successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            // Handle specific error cases
                            val errorMessage = when {
                                result.contains("Failed to load model", ignoreCase = true) -> {
                                    "Failed to load the GGUF model file. Please ensure it's a valid GGUF format and accessible."
                                }
                                result.contains("Failed to create context", ignoreCase = true) -> {
                                    "Failed to create model context. The model might be corrupted or incompatible."
                                }
                                result.contains("Native crash", ignoreCase = true) -> {
                                    "Native crash occurred during model initialization. The model might be incompatible or corrupted."
                                }
                                result.contains("Error", ignoreCase = true) -> {
                                    "Error initializing model: $result"
                                }
                                else -> {
                                    "Unexpected error while initializing model: $result"
                                }
                            }
                            Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                            // Reset state on failure
                            isModelInitializedState.value = false
                            isMockModeState.value = false
                            this@MainActivity.modelPath = null
                        }
                        isModelLoadingState.value = false
                    }
                } catch (e: SecurityException) {
                    withContext(Dispatchers.Main) {
                        val errorMessage = "Permission denied accessing the selected file. Please check file permissions."
                        Toast.makeText(this@MainActivity, "Security Error: $errorMessage\nDetails: ${e.message}", Toast.LENGTH_LONG).show()
                        e.printStackTrace()
                        // Reset state on failure
                        isModelInitializedState.value = false
                        isMockModeState.value = false
                        this@MainActivity.modelPath = null
                        isModelLoadingState.value = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val errorMessage = "Unexpected error while loading the model."
                        Toast.makeText(this@MainActivity, "$errorMessage\nDetails: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        e.printStackTrace()
                        // Reset state on failure
                        isModelInitializedState.value = false
                        isMockModeState.value = false
                        this@MainActivity.modelPath = null
                        isModelLoadingState.value = false
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        val errorMessage = "Critical error occurred during model loading."
                        Toast.makeText(this@MainActivity, "$errorMessage\nDetails: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        e.printStackTrace()
                        // Reset state on failure
                        isModelInitializedState.value = false
                        isMockModeState.value = false
                        this@MainActivity.modelPath = null
                        isModelLoadingState.value = false
                    }
                }
            }
        }
    }
    
    // Helper function to get real path from URI
    private fun getRealPathFromURI(uri: android.net.Uri): String? {
        // For direct file paths
        if (uri.scheme == "file") {
            return uri.path
        }
        
        // For content URIs, we need to copy to a temporary location
        return copyContentUriToTempFile(uri)
    }
    
    // Helper function to copy content URI to temporary file if needed
    private fun copyContentUriToTempFile(uri: android.net.Uri): String? {
        try {
            val fileName = getFileName(uri) ?: "temp_model.gguf"
            val tempFile = File(cacheDir, fileName)
            
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    return null
                }
                
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            return tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    // Helper function to get file name from URI
    private fun getFileName(uri: android.net.Uri): String? {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ChatAppTheme {
                // Use the state value in the Composable
                if (isModelInitializedState.value) {
                    // Main chat screen with loaded model
                    ChatScreen(isMockMode = isMockModeState.value)
                } else {
                    // Show model selection screen
                    ModelSelectionScreen(
                        onPickModel = {
                            if (!isModelLoadingState.value) {
                                pickModelLauncher.launch("*/*")
                            }
                        },
                        onUseMock = {
                            // Use mock mode without LLM
                            isModelInitializedState.value = true
                            isMockModeState.value = true
                            modelPath = null
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            // Run native cleanup in background thread
            lifecycleScope.launch(Dispatchers.IO) {
                LlamaWrapper.nativeFree()
            }
        } catch (e: Exception) {
            // Silently handle cleanup errors
            e.printStackTrace()
        }
        super.onDestroy()
    }
}