package com.techwin.statushd

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoStateReducerTest {

    @Test
    fun testCancelRequestedFromProcessing() {
        val initialState = PhotoUiState.Processing(50)
        val nextState = PhotoStateReducer.onCancelRequested(initialState)
        assertEquals(PhotoUiState.Canceling, nextState)
    }

    @Test
    fun testCancelRequestedFromSaving() {
        val initialState = PhotoUiState.Saving
        val nextState = PhotoStateReducer.onCancelRequested(initialState)
        assertEquals(PhotoUiState.Canceling, nextState)
    }

    @Test
    fun testCancelRequestedFromIdleReturnsSame() {
        val initialState = PhotoUiState.Idle
        val nextState = PhotoStateReducer.onCancelRequested(initialState)
        assertEquals(PhotoUiState.Idle, nextState)
    }

    @Test
    fun testCancelCompletedWithNullSelectedState() {
        val nextState = PhotoStateReducer.onCancelCompleted(null)
        assertEquals(PhotoUiState.Idle, nextState)
    }
}
