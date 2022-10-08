package com.zsu

import info.debatty.java.stringsimilarity.NGram
import info.debatty.java.stringsimilarity.NormalizedLevenshtein
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeSmart
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

public fun main(args: Array<String>) {
    var file: File? = null
    var git: File? = null
    var rate = 0.7
    for ((i, arg) in args.withIndex()) when (arg) {
        "-file" -> file = File(args[i + 1])
        "-git" -> git = File(args[i + 1])
        "-rate" -> rate = args[i + 1].toDoubleOrNull() ?: rate
    }
    if (file == null) throw Exception("No file input.")
    val visitor = UselessCodeVisitor(git, rate)
    analyze(file, visitor)
    val result = visitor.result
    result.forEach {
        it.apply {
            println(
                "${fileFqn}$containingExtra#$lineNumber: $uselessRate\n" +
                        "  comment: $commentText\n" +
                        "  declaration: $inputName\n" +
                        gitExtra
            )
        }
    }
    val authorCount = hashMapOf<String, Int>()
    for (data in result) {
        val gitUser = data.gitExtra.trim().substringAfter(' ')
        val currentCount = authorCount[gitUser] ?: 0
        authorCount[gitUser] = currentCount + 1
    }
    val sorted = authorCount.toSortedMap { o1, o2 -> (authorCount[o2] ?: 0) - (authorCount[o1] ?: 0) }
    if (sorted.isNotEmpty()) {
        val str = sorted.map { "${it.key}: ${it.value}" }
            .joinToString("\n", prefix = "top useless comment user:\n")
        println(str)
    }
}

private fun File.toKtFile(project: MockProject): KtFile? {
    val virtualFile = StandardFileSystems.local().findFileByPath(absolutePath) ?: return null
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
    return psiFile as? KtFile
}

private fun createIdeaProject(): MockProject {
    val disposable = Disposer.newDisposable()
    val compilerConfiguration = CompilerConfiguration()
    compilerConfiguration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    try {
        return KotlinCoreEnvironment.createForProduction(
            disposable,
            compilerConfiguration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ).project as MockProject
    } finally {
        disposable.dispose()
    }
}

private fun analyze(file: File, processor: KtVisitorVoid) {
    println("init files...")
    val kotlinFiles = mutableListOf<File>()
    fun File.addToFiles() {
        if (name.endsWith(".kt")) {
            kotlinFiles.add(this)
        }
    }
    if (file.isDirectory) file.walkTopDown().forEach { childFile ->
        childFile.addToFiles()
    } else file.addToFiles()
    println("count: ${kotlinFiles.size}, start analyze files")
    val start = System.currentTimeMillis()
    kotlinFiles.chunked(100).blockingAsync { singleChunk ->
        val project = createIdeaProject()
        for (kotlinFile in singleChunk) {
            kotlinFile.toKtFile(project)?.accept(processor)
        }
    }
    println("end analyze files, cost: ${(System.currentTimeMillis() - start) / 1000}s")
}

private class UselessCodeVisitor(gitDir: File?, private val rate: Double) : KtTreeVisitorVoid() {
    private val git = gitDir?.let { Git(it) }
    private val blameMap = ConcurrentHashMap<String, List<String>>()
    private fun String.isMeaningless(): Boolean {
        return isBlank()
    }

    val result: MutableList<Data> = Collections.synchronizedList(arrayListOf())

    private val VirtualFile.ioFile: File
        get() = VfsUtilCore.virtualToIoFile(this)

    class Data(
        val fileFqn: String,
        val lineNumber: Int,
        val containingExtra: String,
        val gitExtra: String,
        val inputName: String,
        val commentText: String,
        val uselessRate: Double,
    )

    private fun scanComment(input: KtNamedDeclaration, element: PsiComment): List<Data> {
        val inputName = input.name ?: return result
        val realText = element.text.lines().joinToString(" ") {
            it.trim().removePrefix("//")
                .removePrefix("/**")
                .removePrefix("/*")
                .removeSuffix("*/")
                .removePrefix("*").trim()
        }.trim()
        val useLessRate = if (realText.isMeaningless()) 1.0 else SameRate.calculate(inputName, realText)
        if (useLessRate > rate) {
            val fileFqn = input.containingKtFile.packageFqName.asString()
            val lineNumber = element.lineNumber
            val containing = (if (input !is KtClassOrObject) input.containingClassOrObject else input)?.name ?: ""
            val containingExtra = if (containing.isEmpty()) "" else ".$containing"
            val gitExtra = if (git != null) {
                val file = input.containingKtFile.virtualFile?.ioFile
                if (file == null) "" else run {
                    val path = file.absolutePath
                    val blameCache = blameMap[path] ?: runCatching {
                        git?.callBlame(file)
                    }.getOrNull()
                    if (blameCache != null) {
                        blameMap[path] = blameCache
                        if (lineNumber >= 0) {
                            return@run "  author: ${blameCache[lineNumber]}\n"
                        }
                    }
                    ""
                }
            } else ""
            result += Data(
                fileFqn, lineNumber, containingExtra, gitExtra,
                inputName, element.text.replace('\n', ' '), useLessRate
            )

        }
        return result
    }

    override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
        super.visitNamedDeclaration(declaration)
        val comment = declaration.children.firstOrNull { it is PsiComment } as? PsiComment
        scanComment(declaration, comment ?: return)
    }
}

private object SameRate {
    private fun String.normalize(): String {
        if (isBlank()) return ""
        val decapitalized = decapitalizeSmart().trim()
        val words = decapitalized.split(' ')
        val resultWords = arrayListOf<String>()
        if (words.size > 1) {
            for (word in words) {
                resultWords += word.normalize()
            }
        } else {
            var last = 0
            for ((index, char) in decapitalized.withIndex()) {
                if (char.isUpperCase()) {
                    resultWords += decapitalized.substring(last, index).decapitalizeSmart()
                    last = index
                }
            }
            resultWords += decapitalized.substring(last, decapitalized.length).decapitalizeSmart()
        }
        return resultWords.joinToString(" ")
    }

    private inline val String.asciiRate get() = count { it.code in 0..127 } / length.toDouble()
    fun calculate(short: String, long: String, isNormalized: Boolean = false): Double {
        var shortN = short
        var longN = long
        if (!isNormalized) {
            shortN = short.normalize()
            longN = long.normalize()
        }
        if (shortN.length > longN.length) return calculate(longN, shortN)
        if (shortN.length < 3) return 0.0
        // only calculate ascii
        if (shortN.asciiRate < 0.6 || long.asciiRate < 0.6) return 0.0
        val distanceLevenshtein = NormalizedLevenshtein().distance(shortN, longN)
        val distanceNGram = NGram().distance(shortN, longN)
        return 1 - minOf(distanceNGram, distanceLevenshtein)
    }
}

private class Git(private val workDir: File) {
    fun callBlame(blameFile: File): List<String> {
        val filePath = blameFile.absolutePath
        val blameRawRes = callAny(
            "git", "blame", "--line-porcelain", filePath,
        ).lines()
        val exceptSize = blameRawRes.size / 10
        val authors = ArrayList<String>(exceptSize)
        val emails = ArrayList<String>(exceptSize)
        for (rawBlame in blameRawRes) when {
            rawBlame.startsWith("author ") -> {
                authors += rawBlame.substringAfter("author").trim()
            }
            rawBlame.startsWith("author-mail ") -> {
                emails += rawBlame.substringAfter('<').substringBefore('>')
            }
        }
        require(authors.size == emails.size) { "blame run fail!" }
        val blameResult = ArrayList<String>(authors.size)
        for (i in authors.indices) {
            blameResult += "${authors[i]}<${emails[i]}>"
        }
        return blameResult
    }

    private fun callAny(vararg cmd: String, waitSeconds: Long? = 30L): String {
        val process = ProcessBuilder(*cmd)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .directory(workDir)
            .start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        if (waitSeconds == null) process.waitFor()
        else process.waitFor(waitSeconds, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            process.errorStream.bufferedReader().use {
                throw Exception(it.readText())
            }
        }
        return result
    }
}

private val PsiElement.lineNumber: Int inline get() = containingFile?.viewProvider?.document?.getLineNumber(startOffset) ?: -1

private suspend inline fun <T, R> Collection<T>.mapAsync(
    scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.Default,
    crossinline eachAction: suspend (T) -> R
): List<Deferred<R>> = map {
    scope.async(dispatcher) {
        eachAction(it)
    }
}

private inline fun <T, R> Collection<T>.blockingAsync(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    crossinline eachAction: suspend (T) -> R
): List<R> = runBlocking(dispatcher) {
    mapAsync(this, dispatcher, eachAction).awaitAll()
}
