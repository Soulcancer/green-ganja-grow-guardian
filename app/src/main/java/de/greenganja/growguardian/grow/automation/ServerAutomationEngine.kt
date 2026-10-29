package de.greenganja.growguardian.grow.automation

/**
 * Regeln laufen auf dem Server (Cloud Function `serverMonitor`, alle 5 Minuten über die Tuya Cloud),
 * nicht als Tuya-Szenen. Die App merkt sich dafür nur die Kennung [SERVER] in `sceneIds`; der Server führt alle
 * Regeln mit dieser Kennung aus. Pausieren und Löschen übernimmt der Server ebenfalls (enabled, Stufe).
 *
 * Gegenüber [de.greenganja.growguardian.tuya.TuyaAutomationSync]: keine ungeprüfte Szenen-Schnittstelle, dafür
 * bis zu 5 Minuten Verzögerung und ein Tuya-Cloud-Projekt mit API-Kontingent.
 */
class ServerAutomationEngine : AutomationEngine {
    override suspend fun apply(automation: Automation, active: Boolean): List<String> = listOf(SERVER)
    override suspend fun setEnabled(sceneIds: List<String>, enabled: Boolean) = Unit
    override suspend fun remove(sceneIds: List<String>) = Unit
    override suspend fun cleanup(knownSceneIds: Set<String>): Int = 0

    companion object {
        const val SERVER = "server"
    }
}
