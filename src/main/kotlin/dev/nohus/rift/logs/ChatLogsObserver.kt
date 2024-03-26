package dev.nohus.rift.logs

import dev.nohus.rift.logs.DirectoryObserver.DirectoryObserverEvent.FileEvent
import dev.nohus.rift.logs.DirectoryObserver.DirectoryObserverEvent.OverflowEvent
import dev.nohus.rift.logs.DirectoryObserver.FileEventType.Created
import dev.nohus.rift.logs.DirectoryObserver.FileEventType.Deleted
import dev.nohus.rift.logs.DirectoryObserver.FileEventType.Modified
import dev.nohus.rift.logs.parse.ChannelChatMessage
import dev.nohus.rift.logs.parse.ChatLogFileMetadata
import dev.nohus.rift.logs.parse.ChatLogFileParser
import dev.nohus.rift.logs.parse.ChatMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Single
class ChatLogsObserver(
    private val directoryObserver: DirectoryObserver,
    private val matchChatLogFilenameUseCase: MatchChatLogFilenameUseCase,
    private val logFileParser: ChatLogFileParser,
) {
    private val logFiles = mutableListOf<ChatLogFile>()
    private var activeLogFiles: Map<String, ChatLogFileMetadata> = emptyMap() // String is the filename
    private var onMessageCallback: ((ChannelChatMessage) -> Unit)? = null
    private val handledMessagesSet = mutableSetOf<ChatMessage>()
    private val handledMessagesList = mutableSetOf<ChatMessage>()

    suspend fun observe(
        directory: File,
        onMessage: (ChannelChatMessage) -> Unit,
    ) {
        logFiles.clear()
        activeLogFiles = emptyMap()
        onMessageCallback = onMessage

        logger.info { "Observing chat logs: $directory" }
        reloadLogFiles(directory)
        directoryObserver.observe(directory) { event ->
            when (event) {
                is FileEvent -> {
                    val logFile = matchChatLogFilenameUseCase(event.file)
                    if (logFile != null) {
                        when (event.type) {
                            Created -> {
                                logFiles += logFile
                                updateActiveLogFiles()
                            }
                            Deleted -> {
                                val file = logFiles.find { it.file.name == logFile.file.name }
                                if (file != null) logFiles -= file
                                updateActiveLogFiles()
                            }
                            Modified -> {
                                activeLogFiles[logFile.file.name]?.let { metadata ->
                                    readLogFile(logFile, metadata) // TODO: Optimise, we don't need to reread the file in full
                                }
                            }
                        }
                    }
                }
                OverflowEvent -> reloadLogFiles(directory)
            }
        }
        logger.info { "Stopped observing" }
    }

    fun stop() {
        directoryObserver.stop()
    }

    private fun reloadLogFiles(directory: File) {
        val logFiles = directory.listFiles()?.mapNotNull { file ->
            matchChatLogFilenameUseCase(file)
        } ?: emptyList()
        this.logFiles.clear()
        this.logFiles.addAll(logFiles)
        updateActiveLogFiles()
    }

    private fun updateActiveLogFiles() {
        try {
            val currentActiveLogFiles = logFiles
                .filter { it.file.exists() }
                .groupBy { it.characterId }
                .flatMap { (characterId, playerLogFiles) ->
                    playerLogFiles
                        .groupBy { it.channelName }
                        .mapNotNull { (channelName, playerChannelLogFiles) ->
                            // Take the latest file for this player / channel combination
                            val logFile = playerChannelLogFiles.maxBy { it.lastModified }
                            val existingMetadata = activeLogFiles[logFile.file.name]
                            val metadata = existingMetadata ?: logFileParser.parseHeader(characterId, logFile.file)
                            if (metadata != null) {
                                logFile to metadata
                            } else {
                                logger.error { "Could not parse metadata for $logFile" }
                                null
                            }
                        }
                }

            val newActiveLogFiles = currentActiveLogFiles.filter { it.first.file.name !in activeLogFiles.keys }
            activeLogFiles = currentActiveLogFiles.associate { (logFile, metadata) -> logFile.file.name to metadata }

            newActiveLogFiles.forEach { (logFile, metadata) ->
                readLogFile(logFile, metadata)
            }
        } catch (e: IOException) {
            logger.error(e) { "Could not update active chat log files" }
        }
    }

    private fun readLogFile(logFile: ChatLogFile, metadata: ChatLogFileMetadata) {
        try {
            val newMessages = logFileParser.parse(logFile.file)
                .filter { it !in handledMessagesSet }
            if (newMessages.isEmpty()) return
            newMessages.forEach { handleNewMessage(it, metadata) }
        } catch (e: IOException) {
            logger.error(e) { "Could not read chat log file" }
        }
    }

    private fun handleNewMessage(message: ChatMessage, metadata: ChatLogFileMetadata) {
        val now = Instant.now()
        val recentMessages = handledMessagesList.reversed().takeWhile { handledMessage ->
            val age = Duration.between(handledMessage.timestamp, now)
            age < Duration.ofSeconds(2)
        }
        val isDuplicated = recentMessages.any { it.author == message.author && it.message == message.message }
        handledMessagesSet += message
        handledMessagesList += message
        if (!isDuplicated) {
            onMessageCallback?.invoke(ChannelChatMessage(message, metadata))
        }
    }
}