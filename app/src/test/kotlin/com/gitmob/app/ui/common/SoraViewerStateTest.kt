package com.gitmob.app.ui.common

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class SoraViewerStateTest {
    @Test fun saverCapturesLivePositionWithoutSerializingTheDocument() {
        val state = SoraViewerState("blob-a")
        val current = SourceReadingPosition(700, 12, .5f, 45f, 701, 3, 705, 9)
        state.readPosition = { current }
        val saver = SoraViewerState.saver("blob-a")
        val saved = with(saver) { SaverScope { true }.save(state) }!!
        assertEquals(9, (saved as List<*>).size)
        assertEquals(current, saver.restore(saved)!!.position)
        assertEquals(SourceReadingPosition(), SoraViewerState.saver("blob-b").restore(saved)!!.position)
    }
}
