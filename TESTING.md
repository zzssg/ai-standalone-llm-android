# Testing LlamaWrapper.nativeInitModel Method

This document explains the approach to testing the [LlamaWrapper.nativeInitModel](file:///c%3A/Users/Sergei/workspace/ai-standalone-android/app/src/main/java/org/zzssg/llmchatapp/LlamaWrapper.kt#L10-L10) method with the GGUF model, including limitations of unit testing native code.

## Technical Limitations

**Important**: The [LlamaWrapper.nativeInitModel](file:///c%3A/Users/Sergei/workspace/ai-standalone-android/app/src/main/java/org/zzssg/llmchatapp/LlamaWrapper.kt#L10-L10) method is a native JNI method that:
1. Requires the Android NDK runtime environment
2. Depends on native libraries compiled for ARM64
3. Can only be tested on actual Android devices or emulators
4. Cannot be executed in standard JVM unit tests

## Current Unit Tests

The unit tests in `app/src/test/java/org/zzssg/llmchatapp/LlamaWrapperUnitTest.java` verify:
- The [LlamaCallback](file:///c%3A/Users/Sergei/workspace/ai-standalone-android/app/src/main/java/org/zzssg/llmchatapp/LlamaWrapper.kt#L22-L26) interface structure
- Companion object accessibility
- Method signatures

Run unit tests with:
```bash
./gradlew test
```

## Testing with the GGUF Model

To actually test the [LlamaWrapper.nativeInitModel](file:///c%3A/Users/Sergei/workspace/ai-standalone-android/app/src/main/java/org/zzssg/llmchatapp/LlamaWrapper.kt#L10-L10) method with the GGUF model:

### 1. Create Instrumented Test

Create or modify `app/src/androidTest/java/org/zzssg/llmchatapp/LlamaWrapperTest.java`:

```java
@Test
public void testNativeInitModel_withGemmaModel() throws Exception {
    // Copy model from assets to app storage
    String modelPath = copyModelFromAssets("models/gemma-3-270m-q4_k_s.gguf");
    
    // Test model initialization
    String result = LlamaWrapper.Companion.nativeInitModel(modelPath);
    
    // Verify success
    assertEquals("Success", result);
    assertTrue(LlamaWrapper.Companion.nativeIsModelLoaded());
    
    // Cleanup
    LlamaWrapper.Companion.nativeFree();
    new File(modelPath).delete();
}

private String copyModelFromAssets(String fileName) throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    InputStream inputStream = context.getAssets().open(fileName);
    File outputFile = new File(context.getFilesDir(), "test_model.gguf");
    OutputStream outputStream = new FileOutputStream(outputFile);
    
    byte[] buffer = new byte[1024];
    int length;
    while ((length = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, length);
    }
    
    outputStream.close();
    inputStream.close();
    
    return outputFile.getAbsolutePath();
}
```

### 2. Run Instrumented Test

Connect an Android device or start an emulator, then run:
```bash
./gradlew connectedDebugAndroidTest
```

## GGUF Model Information

Model location: `app/assets/models/gemma-3-270m-q4_k_s.gguf`
- Size: ~244MB
- Model: Gemma 270M (quantized)
- Format: GGUF (Q4_K_S quantization)

## Expected Test Results

When running on an Android device:
- Model initialization should return "Success"
- [LlamaWrapper.nativeIsModelLoaded()](file:///c%3A/Users/Sergei/workspace/ai-standalone-android/app/src/main/java/org/zzssg/llmchatapp/LlamaWrapper.kt#L17-L17) should return true
- Memory usage will increase during testing
- Test duration: 10-30 seconds depending on device

## Alternative Testing Approaches

### 1. Mock-Based Testing (Limited Value)
```java
// This only tests Java structure, not native functionality
@Test
public void testMethodSignatures() {
    // Verify methods exist (compile-time check only)
    assertNotNull(LlamaWrapper.Companion);
    // Cannot actually call native methods
}
```

### 2. Integration Testing Framework
Consider creating a test harness that:
1. Builds a debug APK with test code
2. Installs it on a connected device
3. Runs tests automatically
4. Collects results

### 3. Continuous Integration
Set up CI with Android Emulator:
```yaml
# Example GitHub Actions workflow
- name: Run Instrumented Tests
  uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 29
    script: ./gradlew connectedDebugAndroidTest
```

## Troubleshooting

Common issues when testing with the GGUF model:
1. **Insufficient storage space** - Model requires ~300MB free space
2. **Memory limitations** - Model may require 500MB+ RAM
3. **Architecture mismatch** - Model compiled for ARM64 only
4. **File permissions** - Ensure app can read from assets and write to storage

## Conclusion

While unit tests can verify the structure and interfaces, actual testing of [LlamaWrapper.nativeInitModel](file:///c%3A/Users/Sergei/workspace/ai-standalone-android/app/src/main/java/org/zzssg/llmchatapp/LlamaWrapper.kt#L10-L10) with the GGUF model requires:
1. An Android device or emulator
2. Instrumented tests (androidTest)
3. Proper file handling for asset extraction
4. Sufficient device resources

The provided test framework in `LlamaWrapperTest.java` is ready to be uncommented and used with a connected Android device.