package com.loopermallee.moncchichi.subtitles.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.subtitles.model.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubtitlesViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    val displayService = repository.displayService

    data class State(
        val glasses: G1ServiceCommon.Glasses? = null,
        val hubInstalled: Boolean = false,
        val started: Boolean = false,
        val listening: Boolean = false,
        val displayText: List<String> = listOf(),
        val queuedText: List<String> = listOf()
    )

    private val writableState = MutableStateFlow(State())
    val state = writableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect {
                writableState.value = state.value.copy(
                    glasses = it.glasses,
                    hubInstalled = it.hubInstalled,
                    started = it.started,
                    listening = it.listening
                )
            }
        }
        viewModelScope.launch {
            repository.events.collect {
                when (it) {
                    Repository.Event.RecognitionError -> {}
                    is Repository.Event.SpeechRecognized -> {
                        val cutLines = textLinesToGlassesLines(it.text)
                        val linesLeft = if(state.value.displayText.isEmpty()) {
                            queueJob?.cancel()
                            queueJob = null

                            val linesToleave = if(cutLines.size > 5) {
                                val firstLines = cutLines.take(5)
                                displayNow(firstLines)
                                cutLines.takeLast(cutLines.size - 5)
                            } else {
                                displayNow(cutLines)
                                listOf()
                            }
                            linesToleave
                        } else if(state.value.displayText.size < 5) {
                            queueJob?.cancel()
                            queueJob = null

                            val linesToAdd = 5 - state.value.displayText.size
                            val linesToLeave = if(linesToAdd >= cutLines.size) {
                                displayNow(state.value.displayText.plus(cutLines))
                                listOf()
                            } else {
                                displayNow(state.value.displayText.plus(cutLines.take(linesToAdd)))
                                cutLines.takeLast(cutLines.size-linesToAdd)
                            }
                            linesToLeave
                        } else {
                            cutLines
                        }

                        if(linesLeft.isNotEmpty()) {
                            displayQueue.addAll(linesLeft.chunked(5))
                        }
                        if(queueJob == null) {
                            queueJob = viewModelScope.launch {
                                var somethingDisplayed = false
                                do {
                                    delay(5000)
                                    if(displayQueue.isNotEmpty()) {
                                        val nextOne = displayQueue.removeAt(0)
                                        displayNow(nextOne)
                                        somethingDisplayed = true
                                    } else {
                                        somethingDisplayed = false
                                    }
                                } while (somethingDisplayed)
                                displayNow(listOf())
                                queueJob = null
                            }
                        }
                    }
                }
            }
        }
    }

    fun startRecognition() = repository.startRecognition(viewModelScope)
    fun stopRecognition() = repository.stopRecognition()

    //

    private fun displayNow(lines: List<String>) {
        viewModelScope.launch {
            writableState.value = state.value.copy(
                displayText = lines
            )
            if(lines.isNotEmpty()) {
                repository.displayText(lines)
            } else {
                repository.stopDisplaying()
            }
        }
    }

    private val displayQueue: MutableList<List<String>> = mutableListOf()
    private var queueJob: Job? = null

    private fun textLinesToGlassesLines(lines: List<String>): List<String> {
        val glassesLines = mutableListOf<String>()
        lines.forEach { line ->
            if(line.length <= 40) {
                glassesLines.add(line)
            } else {
                var accumulatedLine: String = ""
                line.split(" ").filterNot { it.trim().isEmpty() }.forEach {
                    if((accumulatedLine.length + 1 + it.length) > 40) {
                        glassesLines.add(accumulatedLine)
                        accumulatedLine = it
                    } else {
                        accumulatedLine = "$accumulatedLine $it"
                    }
                }
                if(accumulatedLine.isNotBlank()) {
                    glassesLines.add(accumulatedLine)
                }
            }
        }
        return glassesLines
    }
}