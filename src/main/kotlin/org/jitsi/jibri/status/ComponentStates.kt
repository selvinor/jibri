package org.jitsi.jibri.status

sealed class ComponentState {
    object StartingUp : ComponentState() {
        override fun toString(): String = "Starting up"
    }
    object Running : ComponentState() {
        override fun toString(): String = "Running"
    }
    class Error(val errorScope: ErrorScope, val detail: String) : ComponentState() {
        override fun toString(): String = "Error: $errorScope $detail"
    }
    object Finished : ComponentState() {
        override fun toString(): String = "Finished"
    }
}

enum class ErrorScope {
    SESSION,
    SYSTEM
}