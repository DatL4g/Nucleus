package com.example.samplecmp

import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication

fun main() =
    nucleusApplication {
        DecoratedWindow(onCloseRequest = ::exitApplication, title = "Sample CMP") {
            App()
        }
    }
