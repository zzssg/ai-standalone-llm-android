package org.zzssg.llmchatapp;

import android.content.Context;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

/**
 * Instrumented test for LlamaWrapper, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class LlamaWrapperTest {
    
    private static final String TAG = "LlamaWrapperTest";
    private Context appContext;
    
    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Log.d(TAG, "Setting up test environment");
    }
    
    @Test
    public void testPackageExists() {
        Log.d(TAG, "Running testPackageExists");
        // Simple test to verify the app context
        assertEquals("org.zzssg.llmchatapp", appContext.getPackageName());
        Log.d(TAG, "testPackageExists completed successfully");
    }
    
    /**
     * Test the LlamaWrapper.nativeInitModel method with the Gemma GGUF model.
     * 
     * This test:
     * 1. Copies the GGUF model from assets to app storage
     * 2. Calls LlamaWrapper.nativeInitModel with the model path
     * 3. Verifies the model loads successfully
     * 4. Checks that LlamaWrapper.nativeIsModelLoaded returns true
     * 5. Cleans up resources
     * 
     * To run this test:
     * 1. Connect an Android device or start an emulator
     * 2. Run: ./gradlew connectedDebugAndroidTest
     */
    @Test
    public void testNativeInitModel_withGemmaModel() throws Exception {
        Log.d(TAG, "Running testNativeInitModel_withGemmaModel");
        // Copy the GGUF model from assets to app storage
        String modelPath = copyModelFromAssets("models/gemma-3-270m-q4_k_s.gguf");
        Log.d(TAG, "Model copied to: " + modelPath);
        
        // Initialize the model
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel(modelPath);
        Log.d(TAG, "Model initialization result: " + result);
        
        // Verify success
        assertEquals("Model initialization should succeed", "Success", result);
        
        // Verify model is loaded
        boolean isLoaded = wrapper.nativeIsModelLoaded();
        Log.d(TAG, "Model loaded status: " + isLoaded);
        assertTrue("Model should be loaded after initialization", isLoaded);
        
        // Cleanup
        wrapper.nativeFree();
        new File(modelPath).delete();
        Log.d(TAG, "testNativeInitModel_withGemmaModel completed successfully");
    }

    @Test
    public void testNativeGenerate_withGemmaModel() throws Exception {
        Log.d(TAG, "Running testNativeGenerate_withGemmaModel");
        // Copy the GGUF model from assets to app storage
        String modelPath = copyModelFromAssets("models/gemma-3-270m-q4_k_s.gguf");
        Log.d(TAG, "Model copied to: " + modelPath);

        // Initialize the model
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel(modelPath);
        Log.d(TAG, "Model initialization result: " + result);
        final String[] generatedResult = {""};
        final String[] generatedError = {""};
        final String[] generatedProgress = {""};
        // Flag to indicate when generation is complete
        final boolean[] generationComplete = {false};
        
        LlamaWrapper.LlamaCallback cb = new LlamaWrapper.LlamaCallback() {
            @Override
            public void onResult(@NotNull String result) {
                generatedResult[0] += result;
                Log.d(TAG, "Generation result chunk: " + result);
                // Mark generation as complete when a result is received
                generationComplete[0] = true;
            }

            @Override
            public void onError(@NotNull String error) {
                generatedError[0] += error;
                Log.e(TAG, "Generation error: " + error);
                // Mark generation as complete when an error occurs
                generationComplete[0] = true;
            }

            @Override
            public void onProgress(@NotNull String progress) {
                generatedProgress[0] += progress;
                Log.d(TAG, "Generation progress: " + progress);
            }
        };
        
        Log.d(TAG, "Starting generation with prompt: Calculate 2 plus 2");
        wrapper.nativeGenerate("Calculate 2 plus 2", 128, cb);
        
        // Wait for generation to complete (with a timeout to prevent infinite waiting)
        long startTime = System.currentTimeMillis();
        long timeout = 60000; // 60 seconds timeout (generation can take a while)
        while (!generationComplete[0] && (System.currentTimeMillis() - startTime) < timeout) {
            try {
                Thread.sleep(100); // Check every 100ms
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for generation to complete", e);
                break;
            }
        }
        
        Log.d(TAG, "Generation completed or timed out. Duration: " + (System.currentTimeMillis() - startTime) + "ms");
        
        // Verify that generation completed within the timeout
        assertTrue("Generation should complete within timeout", generationComplete[0]);
        
        // Verify success
        assertEquals("Model initialization should succeed", "Success", result);

        // Verify model is loaded
        boolean isLoaded = wrapper.nativeIsModelLoaded();
        Log.d(TAG, "Model loaded status: " + isLoaded);
        assertTrue("Model should be loaded after initialization", isLoaded);

        Log.d(TAG, "Generated error length: " + generatedError[0].length());
        Log.d(TAG, "Generated progress length: " + generatedProgress[0].length());
        Log.d(TAG, "Generated result length: " + generatedResult[0].length());
        assertEquals("Generated error should be empty", 0, generatedError[0].length());
        assertFalse("Generated progress should be not empty", generatedProgress[0].isEmpty());
        assertFalse("Generated reply should be not empty", generatedResult[0].isEmpty());

        // Cleanup
        wrapper.nativeFree();
        new File(modelPath).delete();
        Log.d(TAG, "testNativeGenerate_withGemmaModel completed successfully");
    }
    
    @Test
    public void testNativeInitModel_withValidModel_returnsSuccess() {
        Log.d(TAG, "Running testNativeInitModel_withValidModel_returnsSuccess");
        // Test with a non-existent file to verify error handling
        String nonExistentModelPath = "/data/data/org.zzssg.llmchatapp/files/test_model.gguf";
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel(nonExistentModelPath);
        Log.d(TAG, "Model initialization result with non-existent file: " + result);
        
        // We expect an error message since the file doesn't exist
        assertNotNull("Result should not be null", result);
        assertNotEquals("Result should not be 'Success'", "Success", result);
        assertTrue("Result should contain error information", result.contains("Error"));
        Log.d(TAG, "testNativeInitModel_withValidModel_returnsSuccess completed successfully");
    }
    
    @Test
    public void testNativeInitModel_withNullPath_returnsError() {
        Log.d(TAG, "Running testNativeInitModel_withNullPath_returnsError");
        // Test with null path
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel(null);
        Log.d(TAG, "Model initialization result with null path: " + result);
        
        // We expect an error message since the path is null
        assertNotNull("Result should not be null", result);
        assertNotEquals("Result should not be 'Success'", "Success", result);
        assertTrue("Result should contain error information", result.contains("Error"));
        Log.d(TAG, "testNativeInitModel_withNullPath_returnsError completed successfully");
    }
    
    @Test
    public void testNativeInitModel_withEmptyPath_returnsError() {
        Log.d(TAG, "Running testNativeInitModel_withEmptyPath_returnsError");
        // Test with empty path
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel("");
        Log.d(TAG, "Model initialization result with empty path: " + result);
        
        // We expect an error message since the path is empty
        assertNotNull("Result should not be null", result);
        assertNotEquals("Result should not be 'Success'", "Success", result);
        assertTrue("Result should contain error information", result.contains("Error"));
        Log.d(TAG, "testNativeInitModel_withEmptyPath_returnsError completed successfully");
    }
    
    @Test
    public void testNativeIsModelLoaded_returnsFalseByDefault() {
        Log.d(TAG, "Running testNativeIsModelLoaded_returnsFalseByDefault");
        // By default, no model should be loaded
        LlamaWrapper wrapper = new LlamaWrapper();
        boolean isLoaded = wrapper.nativeIsModelLoaded();
        Log.d(TAG, "Default model loaded status: " + isLoaded);
        assertFalse("Model should not be loaded by default", isLoaded);
        Log.d(TAG, "testNativeIsModelLoaded_returnsFalseByDefault completed successfully");
    }
    
    @Test
    public void testNativeGenerate_withMultipleUserMessages() throws Exception {
        Log.d(TAG, "Running testNativeGenerate_withMultipleUserMessages");
        // Copy the GGUF model from assets to app storage
        String modelPath = copyModelFromAssets("models/gemma-3-270m-q4_k_s.gguf");
        Log.d(TAG, "Model copied to: " + modelPath);

        // Initialize the model
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel(modelPath);
        Log.d(TAG, "Model initialization result: " + result);
        
        // Verify success
        assertEquals("Model initialization should succeed", "Success", result);

        // Test first message
        final String[] firstResult = {""};
        final String[] firstError = {""};
        final boolean[] firstComplete = {false};
        
        LlamaWrapper.LlamaCallback firstCallback = new LlamaWrapper.LlamaCallback() {
            @Override
            public void onResult(@NotNull String result) {
                firstResult[0] += result;
                Log.d(TAG, "First message result: " + result);
                firstComplete[0] = true;
            }

            @Override
            public void onError(@NotNull String error) {
                firstError[0] += error;
                Log.e(TAG, "First message error: " + error);
                firstComplete[0] = true;
            }

            @Override
            public void onProgress(@NotNull String progress) {
                Log.d(TAG, "First message progress: " + progress);
            }
        };
        
        Log.d(TAG, "Sending first message: Hello, how are you?");
        wrapper.nativeGenerate("Hello, how are you?", 128, firstCallback);
        
        // Wait for first message to complete
        long startTime = System.currentTimeMillis();
        long timeout = 60000; // 60 seconds timeout
        while (!firstComplete[0] && (System.currentTimeMillis() - startTime) < timeout) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for first message to complete", e);
                break;
            }
        }
        
        Log.d(TAG, "First message completed. Duration: " + (System.currentTimeMillis() - startTime) + "ms");
        
        // Verify first message completed successfully
        assertTrue("First message should complete within timeout", firstComplete[0]);
        assertEquals("First message should not have errors", 0, firstError[0].length());
        assertFalse("First message result should not be empty", firstResult[0].isEmpty());
        
        // Test second message (this is the key test for the bug fix)
        final String[] secondResult = {""};
        final String[] secondError = {""};
        final boolean[] secondComplete = {false};
        
        LlamaWrapper.LlamaCallback secondCallback = new LlamaWrapper.LlamaCallback() {
            @Override
            public void onResult(@NotNull String result) {
                secondResult[0] += result;
                Log.d(TAG, "Second message result: " + result);
                secondComplete[0] = true;
            }

            @Override
            public void onError(@NotNull String error) {
                secondError[0] += error;
                Log.e(TAG, "Second message error: " + error);
                secondComplete[0] = true;
            }

            @Override
            public void onProgress(@NotNull String progress) {
                Log.d(TAG, "Second message progress: " + progress);
            }
        };
        
        Log.d(TAG, "Sending second message: What is 2+2?");
        wrapper.nativeGenerate("What is 2+2?", 128, secondCallback);
        
        // Wait for second message to complete
        startTime = System.currentTimeMillis();
        while (!secondComplete[0] && (System.currentTimeMillis() - startTime) < timeout) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for second message to complete", e);
                break;
            }
        }
        
        Log.d(TAG, "Second message completed. Duration: " + (System.currentTimeMillis() - startTime) + "ms");
        
        // Verify second message completed successfully (this verifies the bug fix)
        assertTrue("Second message should complete within timeout", secondComplete[0]);
        assertEquals("Second message should not have errors", 0, secondError[0].length());
        assertFalse("Second message result should not be empty", secondResult[0].isEmpty());
        
        // Verify model is still loaded after both messages
        boolean isLoaded = wrapper.nativeIsModelLoaded();
        Log.d(TAG, "Model loaded status after both messages: " + isLoaded);
        assertTrue("Model should still be loaded after processing multiple messages", isLoaded);

        // Cleanup
        wrapper.nativeFree();
        new File(modelPath).delete();
        Log.d(TAG, "testNativeGenerate_withMultipleUserMessages completed successfully");
    }
    
    @Test
    public void testNativeGenerate_withVariousUserPrompts() throws Exception {
        Log.d(TAG, "Running testNativeGenerate_withVariousUserPrompts");
        // Copy the GGUF model from assets to app storage
        String modelPath = copyModelFromAssets("models/gemma-3-270m-q4_k_s.gguf");
        Log.d(TAG, "Model copied to: " + modelPath);

        // Initialize the model with parameters for better performance
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModelWithParams(
            modelPath,
            4,     // threads
            32,    // batchSize
            512,   // ctxSize
            0.7f,  // temp
            0.9f,  // topP
            40,    // topK
            0.05f, // minP
            1.1f,  // repeatPenalty
            false  // flashAttn
        );
        Log.d(TAG, "Model initialization result: " + result);
        
        // Verify success
        assertEquals("Model initialization should succeed", "Success", result);

        // Array of different prompts to test
        String[] testPrompts = {
            "Hello, how are you today?",
            "What is the capital of France?",
            "Count from 1 to 5",
            "What is 2+2?",
            "Tell me a short joke"
        };
        
        // Test each prompt
        for (int i = 0; i < testPrompts.length; i++) {
            final String prompt = testPrompts[i];
            final int promptIndex = i;
            final String[] generatedResult = {""};
            final String[] generatedError = {""};
            final boolean[] generationComplete = {false};
            
            LlamaWrapper.LlamaCallback callback = new LlamaWrapper.LlamaCallback() {
                @Override
                public void onResult(@NotNull String result) {
                    generatedResult[0] += result;
                    Log.d(TAG, "Prompt " + promptIndex + " result: " + result);
                    generationComplete[0] = true;
                }

                @Override
                public void onError(@NotNull String error) {
                    generatedError[0] += error;
                    Log.e(TAG, "Prompt " + promptIndex + " error: " + error);
                    generationComplete[0] = true;
                }

                @Override
                public void onProgress(@NotNull String progress) {
                    Log.d(TAG, "Prompt " + promptIndex + " progress: " + progress);
                }
            };
            
            Log.d(TAG, "Sending prompt " + promptIndex + ": " + prompt);
            wrapper.nativeGenerate(prompt, 64, callback);
            
            // Wait for generation to complete
            long startTime = System.currentTimeMillis();
            long timeout = 60000; // 60 seconds timeout
            while (!generationComplete[0] && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for prompt " + promptIndex + " to complete", e);
                    break;
                }
            }
            
            Log.d(TAG, "Prompt " + promptIndex + " completed. Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            
            // Verify this prompt completed successfully
            assertTrue("Prompt " + promptIndex + " should complete within timeout", generationComplete[0]);
            assertEquals("Prompt " + promptIndex + " should not have errors", 0, generatedError[0].length());
            assertFalse("Prompt " + promptIndex + " result should not be empty", generatedResult[0].isEmpty());
            
            // Small delay between prompts to ensure proper cleanup
            Thread.sleep(500);
        }
        
        // Verify model is still loaded after all prompts
        boolean isLoaded = wrapper.nativeIsModelLoaded();
        Log.d(TAG, "Model loaded status after all prompts: " + isLoaded);
        assertTrue("Model should still be loaded after processing multiple prompts", isLoaded);

        // Cleanup
        wrapper.nativeFree();
        new File(modelPath).delete();
        Log.d(TAG, "testNativeGenerate_withVariousUserPrompts completed successfully");
    }
    
    @Test
    public void testNativeGenerate_sequentialConversation() throws Exception {
        Log.d(TAG, "Running testNativeGenerate_sequentialConversation");
        // Copy the GGUF model from assets to app storage
        String modelPath = copyModelFromAssets("models/gemma-3-270m-q4_k_s.gguf");
        Log.d(TAG, "Model copied to: " + modelPath);

        // Initialize the model
        LlamaWrapper wrapper = new LlamaWrapper();
        String result = wrapper.nativeInitModel(modelPath);
        Log.d(TAG, "Model initialization result: " + result);
        
        // Verify success
        assertEquals("Model initialization should succeed", "Success", result);

        // Simulate a conversation with context building
        String[] conversationPrompts = {
            "My name is John. What should I eat for breakfast?",
            "I like pancakes. Can you give me a recipe?",
            "What if I want to make them healthier?"
        };
        
        String previousResponses = "";
        
        // Test each prompt in the conversation
        for (int i = 0; i < conversationPrompts.length; i++) {
            final String prompt = conversationPrompts[i];
            final int promptIndex = i;
            final String[] generatedResult = {""};
            final String[] generatedError = {""};
            final boolean[] generationComplete = {false};
            
            LlamaWrapper.LlamaCallback callback = new LlamaWrapper.LlamaCallback() {
                @Override
                public void onResult(@NotNull String result) {
                    generatedResult[0] += result;
                    Log.d(TAG, "Conversation turn " + promptIndex + " result: " + result);
                    generationComplete[0] = true;
                }

                @Override
                public void onError(@NotNull String error) {
                    generatedError[0] += error;
                    Log.e(TAG, "Conversation turn " + promptIndex + " error: " + error);
                    generationComplete[0] = true;
                }

                @Override
                public void onProgress(@NotNull String progress) {
                    Log.d(TAG, "Conversation turn " + promptIndex + " progress: " + progress);
                }
            };
            
            // For conversation context, we might want to include previous responses
            String contextualPrompt = (i > 0) ? "Previous conversation: " + previousResponses + "\n\nNew question: " + prompt : prompt;
            
            Log.d(TAG, "Sending conversation turn " + promptIndex + ": " + prompt);
            wrapper.nativeGenerate(contextualPrompt, 96, callback);
            
            // Wait for generation to complete
            long startTime = System.currentTimeMillis();
            long timeout = 60000; // 60 seconds timeout
            while (!generationComplete[0] && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for conversation turn " + promptIndex + " to complete", e);
                    break;
                }
            }
            
            Log.d(TAG, "Conversation turn " + promptIndex + " completed. Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            
            // Verify this turn completed successfully
            assertTrue("Conversation turn " + promptIndex + " should complete within timeout", generationComplete[0]);
            assertEquals("Conversation turn " + promptIndex + " should not have errors", 0, generatedError[0].length());
            assertFalse("Conversation turn " + promptIndex + " result should not be empty", generatedResult[0].isEmpty());
            
            // Store the response for context in next turn
            previousResponses += "User: " + prompt + " AI: " + generatedResult[0] + " ";
            
            // Small delay between conversation turns
            Thread.sleep(500);
        }
        
        // Verify model is still loaded after the conversation
        boolean isLoaded = wrapper.nativeIsModelLoaded();
        Log.d(TAG, "Model loaded status after conversation: " + isLoaded);
        assertTrue("Model should still be loaded after processing conversation", isLoaded);

        // Cleanup
        wrapper.nativeFree();
        new File(modelPath).delete();
        Log.d(TAG, "testNativeGenerate_sequentialConversation completed successfully");
    }
    
    private String copyModelFromAssets(String fileName) throws Exception {
        Log.d(TAG, "Copying model from assets: " + fileName);
        InputStream inputStream = appContext.getAssets().open(fileName);
        File outputFile = new File(appContext.getFilesDir(), "test_model.gguf");
        OutputStream outputStream = new FileOutputStream(outputFile);
        
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }
        
        outputStream.close();
        inputStream.close();
        
        Log.d(TAG, "Model copied successfully to: " + outputFile.getAbsolutePath());
        return outputFile.getAbsolutePath();
    }
}