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

package com.vaticle.typedb.studio.view.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIconDefaults
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaticle.typedb.studio.state.State
import com.vaticle.typedb.studio.state.page.Editable
import com.vaticle.typedb.studio.view.common.component.Form.IconButton
import com.vaticle.typedb.studio.view.common.component.Form.Text
import com.vaticle.typedb.studio.view.common.component.Icon
import com.vaticle.typedb.studio.view.common.component.Separator
import com.vaticle.typedb.studio.view.common.theme.Theme

object PageArea {

    const val ID = "PAGE_AREA"
    val MIN_WIDTH = 100.dp
    private val TAB_SPACING = 8.dp
    private val TAB_HEIGHT = 28.dp
    private val TAB_UNDERLINE_HEIGHT = 2.dp
    private val ICON_SIZE = 10.sp

    internal class AreaState {
        val cachedPages: MutableMap<Editable, Page> = mutableMapOf()
    }

    @Composable
    fun Area() {
        val state = remember { AreaState() }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(TAB_HEIGHT), horizontalArrangement = Arrangement.Start) {
                State.page.openedPages.forEach { Tab(state, state.cachedPages.getOrPut(it) { Page.of(it) }) }
            }
            Separator.Horizontal()
            Row(Modifier.fillMaxWidth()) {
                State.page.selectedPage?.let { state.cachedPages[it]?.Layout() }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Tab(areaState: AreaState, page: Page) {
        val isSelected = State.page.isSelected(page.data)
        val bgColor = if (isSelected) Theme.colors.primary else Theme.colors.background
        val tabHeight = if (isSelected) TAB_HEIGHT - TAB_UNDERLINE_HEIGHT else TAB_HEIGHT

        Column(modifier = Modifier.width(intrinsicSize = IntrinsicSize.Min)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(tabHeight)
                    .background(color = bgColor)
                    .pointerHoverIcon(PointerIconDefaults.Hand)
                    .clickable { State.page.selectedPage = page.data }
            ) {
                Spacer(modifier = Modifier.width(TAB_SPACING))
                Icon.Render(icon = page.icon.code, size = ICON_SIZE, color = page.icon.color())
                Spacer(modifier = Modifier.width(TAB_SPACING))
                Text(value = page.label)
                Spacer(modifier = Modifier.width(TAB_SPACING))
                IconButton(
                    icon = Icon.Code.XMARK,
                    onClick = { State.page.close(page.data); areaState.cachedPages.remove(page.data) },
                    modifier = Modifier.size(TAB_HEIGHT),
                    bgColor = Color.Transparent,
                    rounded = false,
                )
            }
            if (isSelected) Separator.Horizontal(TAB_UNDERLINE_HEIGHT, Theme.colors.secondary)
        }
        Separator.Vertical()
    }
}