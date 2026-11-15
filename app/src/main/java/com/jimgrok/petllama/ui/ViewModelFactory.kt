package com.jimgrok.petllama.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Minimal factory so screens can create a ViewModel without Hilt/DI.
 */
val viewmodelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ServersViewModel::class.java) -> ServersViewModel() as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
