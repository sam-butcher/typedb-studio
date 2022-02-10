/*
 * Copyright (C) 2021 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.studio.view.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vaticle.typedb.common.collection.Either
import com.vaticle.typedb.studio.state.common.Property
import com.vaticle.typedb.studio.view.editor.InputTarget.Companion.prefixSpaces
import com.vaticle.typedb.studio.view.editor.InputTarget.Cursor
import com.vaticle.typedb.studio.view.editor.InputTarget.Selection
import com.vaticle.typedb.studio.view.editor.TextChange.Deletion
import com.vaticle.typedb.studio.view.editor.TextChange.Insertion
import com.vaticle.typedb.studio.view.editor.TextChange.ReplayType
import com.vaticle.typedb.studio.view.highlighter.SyntaxHighlighter
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface TextProcessor {

    val version: Int
    val clipboard: ClipboardManager

    fun replaceCurrentFound(text: String)
    fun replaceAllFound(text: String)
    fun insertText(toString: String): Boolean
    fun insertNewLine()
    fun deleteSelection()
    fun indentTab()
    fun outdentTab()
    fun cut()
    fun copy()
    fun paste()
    fun undo()
    fun redo()

    class Writable(
        private val content: SnapshotStateList<AnnotatedString>,
        private val fileType: Property.FileType,
        private val rendering: TextRendering,
        private val finder: TextFinder,
        private val target: InputTarget,
        override val clipboard: ClipboardManager,
    ) : TextProcessor {

        @OptIn(ExperimentalTime::class)
        companion object {
            private const val TAB_SIZE = 4
            private const val UNDO_LIMIT = 1_000
            internal val CHANGE_BATCH_DELAY = Duration.milliseconds(400)
        }

        override var version by mutableStateOf(0)
        private var undoStack: ArrayDeque<TextChange> = ArrayDeque()
        private var redoStack: ArrayDeque<TextChange> = ArrayDeque()
        private var changeQueue: BlockingQueue<TextChange> = LinkedBlockingQueue()
        private var changeCount: AtomicInteger = AtomicInteger(0)
        private val coroutineScope = CoroutineScope(EmptyCoroutineContext)

        override fun cut() {
            if (target.selection == null) return
            copy()
            deleteSelection()
        }

        override fun copy() {
            if (target.selection == null) return
            clipboard.setText(target.selectedText())
        }

        override fun paste() {
            clipboard.getText()?.let { if (it.text.isNotEmpty()) insertText(it.text) }
        }

        override fun replaceCurrentFound(text: String) {
            if (!finder.hasMatches) return
            val oldPosition = finder.position
            if (finder.findCurrent() != target.selection) target.updateSelection(
                finder.findCurrent(),
                mayScroll = false
            )
            insertText(text, recomputeFinder = true)
            finder.trySetPosition(oldPosition)
            target.updateSelection(finder.findCurrent())
        }

        override fun replaceAllFound(text: String) {
            if (!finder.hasMatches) return
            var next: Selection?
            target.updateCursor(Cursor(0, 0), isSelecting = false, mayScroll = false)
            while (finder.recomputeNextMatch(target.cursor).let { next = it; next != null }) {
                target.updateSelection(next)
                insertText(text, recomputeFinder = false)
            }
            finder.mayRecomputeAllMatches()
        }

        private fun indent(strings: List<AnnotatedString>, spaces: Int): List<AnnotatedString> {
            return strings.map {
                if (spaces > 0) AnnotatedString(" ".repeat(spaces)) + it
                else if (spaces < 0) it.subSequence(spaces.coerceAtMost(prefixSpaces(it)), it.length)
                else it
            }
        }

        private fun deletionOperation(): Deletion {
            assert(target.selection != null)
            return Deletion(target.selection!!.min, target.selectedTextLines(), target.selection)
        }

        override fun deleteSelection() {
            if (target.selection == null) return
            applyOriginal(TextChange(deletionOperation()))
        }

        override fun outdentTab() {
            val oldSelection = target.selection
            val oldCursor = target.cursor
            val newSelection = oldSelection?.let { target.expandSelection(it) }
                ?: target.expandSelection(oldCursor.toSelection())
            target.updateSelection(newSelection)
            val oldTextLines = target.selectedTextLines()
            val newTextLines = indent(oldTextLines, -TAB_SIZE)
            val firstLineShift = newTextLines.first().length - oldTextLines.first().length
            val lastLineShift = newTextLines.last().length - oldTextLines.last().length
            val newPosition: Either<Cursor, Selection> = oldSelection?.let {
                val startCursorShift = if (it.isForward) firstLineShift else lastLineShift
                val endCursorShift = if (it.isForward) lastLineShift else firstLineShift
                Either.second(target.shiftSelection(it, startCursorShift, endCursorShift))
            } ?: Either.first(Cursor(oldCursor.row, (oldCursor.col + firstLineShift).coerceAtLeast(0)))
            insertText(newTextLines, newPosition)
        }

        override fun indentTab() {
            val selection = target.selection
            val cursor = target.cursor
            if (selection == null) insertText(" ".repeat(TAB_SIZE - prefixSpaces(content[cursor.row]) % TAB_SIZE))
            else {
                val newSelection = target.shiftSelection(selection, TAB_SIZE, TAB_SIZE)
                target.updateSelection(target.expandSelection(selection))
                insertText(indent(target.selectedTextLines(), TAB_SIZE), Either.second(newSelection))
            }
        }

        override fun insertNewLine() {
            val line = content[target.cursor.row]
            val tabs = floor(prefixSpaces(line).toDouble() / TAB_SIZE).toInt()
            insertText("\n" + " ".repeat(TAB_SIZE * tabs))
        }

        private fun asAnnotatedLines(text: String): List<AnnotatedString> {
            return if (text.isEmpty()) listOf() else text.split("\n").map { AnnotatedString(it) }
        }

        override fun insertText(text: String): Boolean {
            insertText(asAnnotatedLines(text), newPosition = null)
            return true
        }

        private fun insertText(text: String, recomputeFinder: Boolean) {
            insertText(asAnnotatedLines(text), newPosition = null, recomputeFinder)
        }

        private fun insertText(
            strings: List<AnnotatedString>,
            newPosition: Either<Cursor, Selection>?,
            recomputeFinder: Boolean = true
        ) {
            val operations = mutableListOf<TextChange.Operation>()
            if (target.selection != null) operations.add(deletionOperation())
            if (strings.isNotEmpty()) operations.add(Insertion(target.selection?.min ?: target.cursor, strings))
            applyOriginal(TextChange(operations), newPosition, recomputeFinder)
        }

        override fun undo() {
            drainAndBatchOriginalChanges()
            if (undoStack.isNotEmpty()) applyReplay(undoStack.removeLast(), ReplayType.UNDO)
        }

        override fun redo() {
            if (redoStack.isNotEmpty()) applyReplay(redoStack.removeLast(), ReplayType.REDO)
        }

        private fun applyOriginal(
            change: TextChange, newPosition: Either<Cursor, Selection>? = null, recomputeFinder: Boolean = true
        ) {
            assert(newPosition == null || !recomputeFinder)
            applyChange(change, recomputeFinder)
            if (newPosition != null) when {
                newPosition.isFirst -> target.updateCursor(newPosition.first(), false)
                newPosition.isSecond -> target.updateSelection(newPosition.second())
            }
            queueChangeAndReannotation(change)
        }

        private fun applyReplay(change: TextChange, replayType: ReplayType) {
            applyChange(change)
            val newTarget = change.target()
            when {
                newTarget.isFirst -> target.updateCursor(newTarget.first(), false)
                newTarget.isSecond -> target.updateSelection(newTarget.second())
            }
            when (replayType) {
                ReplayType.UNDO -> redoStack.addLast(change.invert())
                ReplayType.REDO -> undoStack.addLast(change.invert())
            }
        }

        private fun applyChange(change: TextChange, recomputeFinder: Boolean = true) {
            change.operations.forEach {
                when (it) {
                    is Deletion -> applyDeletion(it)
                    is Insertion -> applyInsertion(it)
                }
            }
            version++
            target.resetTextWidth()
            if (recomputeFinder) finder.mayRecomputeAllMatches()
        }

        private fun applyDeletion(deletion: Deletion) {
            val start = deletion.selection().min
            val end = deletion.selection().max
            val prefix = content[start.row].subSequence(0, start.col)
            val suffix = content[end.row].subSequence(end.col, content[end.row].length)
            content[start.row] = prefix + suffix
            if (end.row > start.row) {
                rendering.removeRange(start.row + 1, end.row + 1)
                content.removeRange(start.row + 1, end.row + 1)
            }
            target.updateCursor(deletion.selection().min, false)
        }

        private fun applyInsertion(insertion: Insertion) {
            val cursor = insertion.cursor
            val prefix = content[cursor.row].subSequence(0, cursor.col)
            val suffix = content[cursor.row].subSequence(cursor.col, content[cursor.row].length)
            val texts = insertion.text.toMutableList()
            texts[0] = prefix + texts[0]
            texts[texts.size - 1] = texts[texts.size - 1] + suffix

            content[cursor.row] = texts[0]
            if (texts.size > 1) {
                content.addAll(cursor.row + 1, texts.subList(1, texts.size))
                rendering.addNew(cursor.row + 1, texts.size - 1)
            }
            target.updateCursor(insertion.selection().max, false)
        }

        @OptIn(ExperimentalTime::class)
        private fun queueChangeAndReannotation(change: TextChange) {
            redoStack.clear()
            changeQueue.put(change)
            changeCount.incrementAndGet()
            coroutineScope.launch {
                delay(CHANGE_BATCH_DELAY)
                if (changeCount.decrementAndGet() == 0) {
                    val changes = drainAndBatchOriginalChanges()
                    changes?.let { reannotate(it.lines()) }
                }
            }
        }

        @Synchronized
        private fun drainAndBatchOriginalChanges(): TextChange? {
            var batchedChanges: TextChange? = null
            if (changeQueue.isNotEmpty()) {
                val changes = mutableListOf<TextChange>()
                changeQueue.drainTo(changes)
                batchedChanges = TextChange.merge(changes)
                undoStack.addLast(batchedChanges.invert())
                while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
            }
            return batchedChanges
        }

        private fun reannotate(lines: IntRange) {
            lines.forEach { content[it] = SyntaxHighlighter.highlight(content[it].text, fileType) }
        }
    }
}
