package com.teleprompter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainViewModelTest {

    @Test
    fun `showSnackbar updates snackbarMessage`() {
        val viewModel = MainViewModel()

        viewModel.showSnackbar("Display service unavailable")

        assertEquals("Display service unavailable", viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `onSnackbarShown clears snackbarMessage`() {
        val viewModel = MainViewModel()
        viewModel.showSnackbar("Failed to start display")

        viewModel.onSnackbarShown()

        assertNull(viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `onSnackbarShown keeps state when snackbar already cleared`() {
        val viewModel = MainViewModel()

        viewModel.onSnackbarShown()

        assertNull(viewModel.uiState.value.snackbarMessage)
    }
}
