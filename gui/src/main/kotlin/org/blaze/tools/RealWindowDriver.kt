package org.blaze.tools

import java.awt.event.InputEvent
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/**
 * A driver for the *real* window, for the verification the round-4 rules demand: a Compose test
 * scene never creates the `DecoratedWindow`'s title bar, never asks the window manager for a native
 * popup surface and never delivers an OS input event, so a title-bar menu can be broken there and
 * pass here. This tool does the opposite — it moves the real pointer, presses real keys and captures
 * the real screen through [Robot] against the live display, with nothing in the app instrumented.
 *
 * Run it while the app is up, e.g.
 *
 * ```
 * ./gradlew :gui:uiProbe -Pprobe="shot build/ui-screenshots/probe.png"
 * ./gradlew :gui:uiProbe -Pprobe="click 1234 56"
 * ./gradlew :gui:uiProbe -Pprobe="key Escape"
 * ```
 *
 * It is deliberately read/write-only through the OS: whatever it observes, a user observes too.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { usage }
    val robot = Robot()

    when (val command = args[0]) {
        "cursor" -> println("[probe] cursor=${MouseInfo.getPointerInfo().location}")

        "move" -> {
            val (x, y) = point(args)
            robot.mouseMove(x, y)
            settle()
            println("[probe] moved to $x,$y cursor=${MouseInfo.getPointerInfo().location}")
        }

        "click" -> {
            val (x, y) = point(args)
            robot.mouseMove(x, y)
            settle()
            pressRelease(robot, InputEvent.BUTTON1_DOWN_MASK)
            println("[probe] primary click at $x,$y (cursor=${MouseInfo.getPointerInfo().location})")
        }

        "rclick" -> {
            val (x, y) = point(args)
            robot.mouseMove(x, y)
            settle()
            pressRelease(robot, InputEvent.BUTTON3_DOWN_MASK)
            println("[probe] secondary click at $x,$y (cursor=${MouseInfo.getPointerInfo().location})")
        }

        "double" -> {
            val (x, y) = point(args)
            robot.mouseMove(x, y)
            settle()
            pressRelease(robot, InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(60)
            pressRelease(robot, InputEvent.BUTTON1_DOWN_MASK)
            println("[probe] double click at $x,$y")
        }

        "key" -> {
            val code = keyCode(args[1])
            robot.keyPress(code)
            Thread.sleep(PressHoldMs)
            robot.keyRelease(code)
            println("[probe] key ${args[1]}")
        }

        "keys" -> {
            val modifier = keyCode(args[1])
            val code = keyCode(args[2])
            robot.keyPress(modifier)
            robot.keyPress(code)
            Thread.sleep(PressHoldMs)
            robot.keyRelease(code)
            robot.keyRelease(modifier)
            println("[probe] key ${args[1]}+${args[2]}")
        }

        "shot" -> {
            require(args.size >= 2) { usage }
            // No region: the whole screen, so a popup that escapes the app window is still captured.
            val bounds =
                if (args.size >= 6) {
                    Rectangle(args[2].toInt(), args[3].toInt(), args[4].toInt(), args[5].toInt())
                } else {
                    Rectangle(Toolkit.getDefaultToolkit().screenSize)
                }
            val file = File(args[1]).apply { parentFile?.mkdirs() }
            ImageIO.write(robot.createScreenCapture(bounds), "png", file)
            println("[probe] wrote ${file.path} ${bounds.width}x${bounds.height} at ${bounds.x},${bounds.y}")
        }

        "wait" -> {
            Thread.sleep(args[1].toLong())
            println("[probe] waited ${args[1]}ms")
        }

        else -> error("unknown command '$command'\n$usage")
    }
}

private const val usage =
    "usage: uiProbe cursor | move x y | click x y | rclick x y | double x y | key NAME | " +
        "keys MOD NAME | shot file [x y w h] | wait ms"

private const val PressHoldMs = 80L

/**
 * How long to let the window system settle between moving the pointer and acting: the title bar
 * hit-tests the native drag region on the way in, and a click delivered in the same millisecond as
 * the move is not the click a user makes.
 */
private const val SettleMs = 250L

private fun settle() = Thread.sleep(SettleMs)

private fun point(args: Array<String>): Pair<Int, Int> {
    require(args.size >= 3) { usage }
    return args[1].toInt() to args[2].toInt()
}

private fun pressRelease(robot: Robot, buttonMask: Int) {
    robot.mousePress(buttonMask)
    Thread.sleep(PressHoldMs)
    robot.mouseRelease(buttonMask)
}

/** `VK_ESCAPE`, `ESCAPE` and `escape` all name the same key. */
private fun keyCode(name: String): Int {
    val field = name.uppercase().let { if (it.startsWith("VK_")) it else "VK_$it" }
    return try {
        KeyEvent::class.java.getField(field).getInt(null)
    } catch (missing: NoSuchFieldException) {
        error("no such key '$name': $missing")
    }
}
