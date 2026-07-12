package com.example.demo.icons

/*
MIT License

Copyright (c) 2022 WorkOS

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val RadixEnterFullScreen: ImageVector
    get() {
        if (_RadixEnterFullScreen != null) return _RadixEnterFullScreen!!

        _RadixEnterFullScreen =
            ImageVector
                .Builder(
                    name = "enter-full-screen",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 15f,
                    viewportHeight = 15f,
                ).apply {
                    path(
                        fill = SolidColor(Color.Black),
                    ) {
                        moveTo(2.5f, 9f)
                        curveTo(2.77614f, 9f, 3f, 9.22386f, 3f, 9.5f)
                        verticalLineTo(12f)
                        horizontalLineTo(5.5f)
                        curveTo(5.77614f, 12f, 6f, 12.2239f, 6f, 12.5f)
                        curveTo(6f, 12.7761f, 5.77614f, 13f, 5.5f, 13f)
                        horizontalLineTo(2.5f)
                        curveTo(2.22386f, 13f, 2f, 12.7761f, 2f, 12.5f)
                        verticalLineTo(9.5f)
                        curveTo(2f, 9.22386f, 2.22386f, 9f, 2.5f, 9f)
                        close()
                        moveTo(12.5f, 9f)
                        curveTo(12.7761f, 9f, 13f, 9.22386f, 13f, 9.5f)
                        verticalLineTo(12.5f)
                        curveTo(13f, 12.7761f, 12.7761f, 13f, 12.5f, 13f)
                        horizontalLineTo(9.5f)
                        curveTo(9.22386f, 13f, 9f, 12.7761f, 9f, 12.5f)
                        curveTo(9f, 12.2239f, 9.22386f, 12f, 9.5f, 12f)
                        horizontalLineTo(12f)
                        verticalLineTo(9.5f)
                        curveTo(12f, 9.22386f, 12.2239f, 9f, 12.5f, 9f)
                        close()
                        moveTo(5.5f, 2f)
                        curveTo(5.77614f, 2f, 6f, 2.22386f, 6f, 2.5f)
                        curveTo(6f, 2.77614f, 5.77614f, 3f, 5.5f, 3f)
                        horizontalLineTo(3f)
                        verticalLineTo(5.5f)
                        curveTo(3f, 5.77614f, 2.77614f, 6f, 2.5f, 6f)
                        curveTo(2.22386f, 6f, 2f, 5.77614f, 2f, 5.5f)
                        verticalLineTo(2.5f)
                        lineTo(2.00977f, 2.39941f)
                        curveTo(2.05629f, 2.17145f, 2.25829f, 2f, 2.5f, 2f)
                        horizontalLineTo(5.5f)
                        close()
                        moveTo(12.6006f, 2.00977f)
                        curveTo(12.8286f, 2.05629f, 13f, 2.25829f, 13f, 2.5f)
                        verticalLineTo(5.5f)
                        curveTo(13f, 5.77614f, 12.7761f, 6f, 12.5f, 6f)
                        curveTo(12.2239f, 6f, 12f, 5.77614f, 12f, 5.5f)
                        verticalLineTo(3f)
                        horizontalLineTo(9.5f)
                        curveTo(9.22386f, 3f, 9f, 2.77614f, 9f, 2.5f)
                        curveTo(9f, 2.22386f, 9.22386f, 2f, 9.5f, 2f)
                        horizontalLineTo(12.5f)
                        lineTo(12.6006f, 2.00977f)
                        close()
                    }
                }.build()

        return _RadixEnterFullScreen!!
    }

private var _RadixEnterFullScreen: ImageVector? = null
