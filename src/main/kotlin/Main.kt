package org.example

import java.io.BufferedWriter
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val HELP = """usage: searcher [OPTIONS] PATTERN [PATH ...]
 -A,--after-context <arg>   print the given number of following lines for each match
 -B,--before-context <arg>  print the given number of preceding lines for each match
 -c,--color                 print with colors, highlighting the matched phrase
 -C,--context <arg>         print the given number of preceding and following lines
 -h,--hidden                search hidden files and folders
    --help                  print this message
 -i,--ignore-case           search case insensitive
    --no-heading            print a single line including the filename for each match"""

// Buffered output: much faster than println for millions of lines
val out = PrintWriter(
    BufferedWriter(OutputStreamWriter(FileOutputStream(FileDescriptor.out), Charsets.UTF_8), 1 shl 16),
    false
)

// All writes to "out" and to "anyOutput" happen while holding this lock
val outLock = Any()

// Has anything been printed so far? (needed for the "--" separator between files)
var anyOutput = false

// Files bigger than this are searched one after the other and written directly
const val BIG_FILE = 16L * 1024 * 1024

fun isBinary(file: File): Boolean {
    val buffer = ByteArray(8000)
    val count = file.inputStream().use { it.read(buffer) }
    for (i in 0 until count) {
        if (buffer[i] == 0.toByte()) return true
    }
    return false
}

fun searchFile(
    file: File, regex: Regex, literal: String?, color: Boolean, noHeading: Boolean,
    before: Int, after: Int, direct: Boolean
) {
    // a file that cannot be read is skipped
    val binary = try { isBinary(file) } catch (e: Exception) { true }
    if (binary) return

    val contextOn = before > 0 || after > 0
    val sb = StringBuilder() // output of this file
    var emitted = false
    val beforeBuf = ArrayDeque<String>() // last lines that were not printed yet
    var lineNo = 0
    var lastPrinted = 0
    var afterLeft = 0 // how many context lines after a match are still to print
    var firstBlock = true

    // write what we have collected for this file to the shared output
    fun emit() {
        if (sb.isEmpty()) return
        synchronized(outLock) {
            if (!emitted) {
                if (contextOn && anyOutput) out.print("--\n")
                anyOutput = true
                emitted = true
            }
            out.append(sb)
        }
        sb.setLength(0)
    }

    fun show(n: Int, isMatch: Boolean, line: String) {
        val sep = if (isMatch) ':' else '-'
        if (noHeading) {
            sb.append(file.path)
            sb.append(sep)
        }
        sb.append(n)
        sb.append(sep)
        if (isMatch && color) {
            sb.append(regex.replace(line) { "\u001B[31m" + it.value + "\u001B[0m" })
        } else {
            sb.append(line)
        }
        sb.append('\n')
    }

    try {
        file.bufferedReader(Charsets.UTF_8, 1 shl 16).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNo++

                // plain text patterns use the fast String.contains, others use the regex engine
                val matched = if (literal != null) line.contains(literal) else regex.containsMatchIn(line)

                if (matched) {
                    val start = lineNo - beforeBuf.size
                    if (firstBlock) {
                        if (!noHeading) {
                            sb.append(file.path)
                            sb.append('\n')
                        }
                    } else if (start > lastPrinted + 1) {
                        sb.append("--\n")
                    }
                    firstBlock = false

                    var n = start
                    for (b in beforeBuf) {
                        show(n, false, b)
                        n++
                    }
                    beforeBuf.clear()

                    show(lineNo, true, line)
                    lastPrinted = lineNo
                    afterLeft = after
                } else if (afterLeft > 0) {
                    show(lineNo, false, line)
                    lastPrinted = lineNo
                    afterLeft--
                } else if (before > 0) {
                    beforeBuf.addLast(line)
                    if (beforeBuf.size > before) beforeBuf.removeFirst()
                }

                // big files run alone, so their output can be written in pieces
                if (direct && sb.length > (1 shl 16)) emit()
            }
        }
    } catch (e: Exception) {
        // file could not be read completely: keep what we have
    }
    emit()
}

fun main(args: Array<String>) {
    var ignoreCase = false
    var color = false
    var showHidden = false
    var noHeading = false
    var before = 0
    var after = 0
    val positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg == "-i" || arg == "--ignore-case") {
            ignoreCase = true
        } else if (arg == "-c" || arg == "--color") {
            color = true
        } else if (arg == "-h" || arg == "--hidden") {
            showHidden = true
        } else if (arg == "--no-heading") {
            noHeading = true
        } else if (arg == "--help") {
            println(HELP)
            return
        } else if (arg == "-A" || arg == "--after-context" ||
            arg == "-B" || arg == "--before-context" ||
            arg == "-C" || arg == "--context"
        ) {
            val number = args.getOrNull(i + 1)?.toIntOrNull()
            if (number == null || number < 0) {
                System.err.println("searcher: $arg needs a number")
                return
            }
            i++
            if (arg == "-A" || arg == "--after-context") after = number
            else if (arg == "-B" || arg == "--before-context") before = number
            else {
                before = number
                after = number
            }
        } else {
            positional.add(arg)
        }
        i++
    }

    if (positional.isEmpty()) {
        println(HELP)
        return
    }

    val pattern = positional[0]
    val paths = if (positional.size > 1) positional.drop(1) else listOf(".")

    // (?U) = Unicode: \w and \s behave like in ripgrep
    val regex = if (ignoreCase) Regex("(?U)" + pattern, RegexOption.IGNORE_CASE) else Regex("(?U)" + pattern)

    // pattern without regex special characters -> fast String.contains instead of the regex engine
    val specials = "\\.[]{}()*+?^\$|"
    val literal: String? = if (!ignoreCase && pattern.none { it in specials }) pattern else null

    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val bigFiles = mutableListOf<File>()

    try {
        for (p in paths) {
            val root = File(p)
            if (!root.exists()) {
                System.err.println("searcher: $p: No such file or directory")
                continue
            }
            val files = root.walkTopDown()
                .onEnter { dir ->
                    (showHidden || dir == root || !dir.name.startsWith(".")) &&
                            (dir == root || !Files.isSymbolicLink(dir.toPath()))
                }
                .filter { it.isFile && (it == root || !Files.isSymbolicLink(it.toPath())) }
                .filter { showHidden || it == root || !it.name.startsWith(".") }

            for (file in files) {
                if (file.length() > BIG_FILE) {
                    bigFiles.add(file)
                } else {
                    pool.execute {
                        searchFile(file, regex, literal, color, noHeading, before, after, false)
                    }
                }
            }
        }

        // wait until all small files are done, then do the big ones one by one
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
        for (file in bigFiles) {
            searchFile(file, regex, literal, color, noHeading, before, after, true)
        }
    } finally {
        pool.shutdown()
        out.flush()
    }
}