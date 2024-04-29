package com.matnx.gpt

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.matnx.gpt.ui.theme.GPTTheme
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val API_KEY_PREFS_NAME = "api_key_prefs"
private const val API_KEY_PREF_KEY = "api_key"

class MainActivity : ComponentActivity() {

    private var text by mutableStateOf("")
    private var returnText by mutableStateOf("")
    private lateinit var sharedPreferences: SharedPreferences
    private var apiText by mutableStateOf("")

    private val chatMessages = mutableListOf(chatMessage {
        role = ChatRole.System
        content = "You are a helpful assistant on an Android Phone, and can control it using Android Intents."
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
        apiText = sharedPreferences.getString(API_KEY_PREF_KEY, "") ?: ""

        setContent {
            GPTTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // API Text Field
                        OutlinedTextField(
                            value = apiText,
                            onValueChange = { newApiText ->
                                apiText = newApiText
                                sharedPreferences.edit().putString(API_KEY_PREF_KEY, newApiText).apply()
                            },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // User Input Text Field
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Your Message") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Send Button
                        Button(
                            onClick = {
                                GlobalScope.launch {
                                    chatMessages.add(
                                        ChatMessage(
                                            role = ChatRole.User,
                                            content = text
                                        )
                                    )
                                    returnText = chatRequest(this@MainActivity, chatMessages, apiText)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(text = "Send")
                        }

                        // Display Return Text
                        Text(
                            text = returnText,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

}

suspend fun chatRequest(context: Context, chatMessages : MutableList<ChatMessage>, api : String) : String {
        val openAI = OpenAI(api)
        val modelId = ModelId("gpt-3.5-turbo-1106")
        val request = chatCompletionRequest {
            model = modelId
            messages = chatMessages
            tools {
                function(
                    name = "androidIntent",
                    description = "Execute an Android Intent",
                ) {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            put(
                                "description",
                                "The action to execute, for example \"android.intent.action.DIAL\""
                            )
                        }
                        putJsonObject("extras") {
                            put("type", "array")
                            put(
                                "description",
                                "The extras to execute, for example \"tel:1234-5678\""
                            )
                            putJsonObject("items") {
                                put("type", "string")
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("location")
                    }
                }
            }
            toolChoice = ToolChoice.Auto // or ToolChoice.function("currentWeather")
        }
        val response = openAI.chatCompletion(request)
        val message = response.choices.first().message
        chatMessages.add(message)
        for (toolCall in message.toolCalls.orEmpty()) {
            require(toolCall is ToolCall.Function) { "Tool call is not a function" }
            val functionResponse = toolCall.execute(context)
            chatMessages.append(toolCall, functionResponse)
        }

        return message.content.orEmpty()
}

private fun ToolCall.Function.execute(context: Context): String {
    val functionToCall = availableFunctions[function.name] ?: error("Function ${function.name} not found")
    val functionArgs = function.argumentsAsJson()
    return functionToCall(context, functionArgs)
}

/**
 * Appends a function call and response to a list of chat messages.
 */
private fun MutableList<ChatMessage>.append(toolCall: ToolCall.Function, functionResponse: String) {
    val message = ChatMessage(
        role = ChatRole.Tool,
        toolCallId = toolCall.id,
        name = toolCall.function.name,
        content = functionResponse
    )
    add(message)
}
/**
 * A map that associates function names with their corresponding functions.
 */
private val availableFunctions = mapOf("androidIntent" to ::callIntent)

private fun callIntent(context : Context, args: JsonObject): String {
    val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Action not provided."

    // Create intent with the specified action
    val intent = Intent(action)

    // Extract extras from arguments
    val extrasArray = args["extras"]?.jsonArray
    extrasArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { extra ->
        // Split the extra into key-value pair
        val (key, value) = extra.split(":")
        // Add extra to the intent
        intent.putExtra(key, value)
    }

    // Attempt to start the activity
    return try {
        context.startActivity(intent)
        "Intent executed successfully."
    } catch (e: Exception) {
        "Failed to execute intent: ${e.message}"
    }
}


@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GPTTheme {
        Greeting("Android")
    }
}