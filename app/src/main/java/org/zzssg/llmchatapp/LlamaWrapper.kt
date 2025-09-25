package org.zzssg.llmchatapp

import android.util.Log

class LlamaWrapper {
    companion object {
        init {
            System.loadLibrary("llama_wrapper")
        }
        
        @JvmStatic
        external fun nativeInitModel(modelPath: String): String
        
        @JvmStatic
        external fun nativeGenerate(prompt: String, maxTokens: Int, callback: LlamaCallback)
        
        @JvmStatic
        external fun nativeFree()
        
        @JvmStatic
        external fun nativeIsModelLoaded(): Boolean
    }

    interface LlamaCallback {
        fun onResult(result: String)
        fun onError(error: String)
        fun onProgress(progress: String)
    }
}