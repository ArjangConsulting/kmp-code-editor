package com.arjangconsulting.codeeditor.verification

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VerificationContent(::runCompositionProbe) }
    }
    private fun runCompositionProbe(onResult: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        handler.postDelayed({
            val connection = window.decorView.findFocus()?.onCreateInputConnection(EditorInfo())
            if (connection == null) {
                onResult("FAIL: focus the editor first")
                return@postDelayed
            }
            connection.setComposingText("に", 1)
            handler.postDelayed({
                val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                val firstText = extracted?.text.toString()
                connection.setComposingText("にほ", 1)
                handler.postDelayed({
                    val second = connection.getExtractedText(ExtractedTextRequest(), 0)
                    val secondText = second?.text.toString()
                    connection.commitText("日本", 1)
                    handler.postDelayed({
                        onResult("native InputConnection; stages=$firstText,$secondText; committed=日本")
                    }, 200)
                }, 200)
            }, 200)
        }, 200)
    }

}
