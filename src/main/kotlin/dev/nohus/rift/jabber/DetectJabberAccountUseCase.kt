package dev.nohus.rift.jabber

import dev.nohus.rift.utils.OperatingSystem
import dev.nohus.rift.utils.osdirectories.OperatingSystemDirectories
import org.koin.core.annotation.Single
import java.io.File

@Single
class DetectJabberAccountUseCase(
    private val operatingSystem: OperatingSystem,
    private val operatingSystemDirectories: OperatingSystemDirectories,
) {

    data class ImportedJabberAccount(
        val jidLocalPart: String,
        val password: String,
    )

    operator fun invoke(): ImportedJabberAccount? {
        val file = when (operatingSystem) {
            OperatingSystem.Linux -> File(operatingSystemDirectories.getUserDirectory(), ".purple/accounts.xml")
            OperatingSystem.Windows -> File(operatingSystemDirectories.getUserDirectory(), "AppData/Roaming/.purple/accounts.xml")
            OperatingSystem.MacOs -> return null // Stored in Apple keystore
        }
        if (!file.canRead()) return null
        val text = file.readText()
        val regex = """<account>.*</account>""".toRegex(setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        regex.findAll(text).forEach { match ->
            val accountBlock = match.value
            val name = """<name>.*</name>""".toRegex().find(accountBlock)?.value?.substringAfter("<name>")?.substringBeforeLast("</name>")
            val password = """<password>.*</password>""".toRegex().find(accountBlock)?.value?.substringAfter("<password>")?.substringBeforeLast("</password>")
            if (name != null && password != null) {
                if ("goonfleet.com" in name) {
                    return ImportedJabberAccount(
                        jidLocalPart = name.substringBeforeLast("@"),
                        password = password,
                    )
                }
            }
        }
        return null
    }
}