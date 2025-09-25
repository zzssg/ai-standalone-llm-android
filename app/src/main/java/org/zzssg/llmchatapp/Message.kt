package org.zzssg.llmchatapp

data class Message(
    val text: String,
    val sender: String // "user" or "ai"
)
