package com.fakeglucosense.simulator

import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Trend arrows are non-ASCII; force UTF-8 so terminals and redirected logs show them correctly.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    when (val parsed = SimulatorConfig.parse(args)) {
        is SimulatorConfig.ParseResult.Help -> println(SimulatorConfig.USAGE)
        is SimulatorConfig.ParseResult.Error -> {
            System.err.println("error: ${parsed.message}\n")
            System.err.println(SimulatorConfig.USAGE)
            exitProcess(2)
        }
        is SimulatorConfig.ParseResult.Ok -> runBlocking {
            SimulatorApp(parsed.config).run(this)
        }
    }
}
