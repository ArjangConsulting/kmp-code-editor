package com.arjangconsulting.codeeditor.verification

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Creates the UIKit host for native editor verification. */
public fun verificationViewController(runCompositionProbe: ((String) -> Unit) -> Unit): UIViewController =
    ComposeUIViewController { VerificationContent(runCompositionProbe) }
