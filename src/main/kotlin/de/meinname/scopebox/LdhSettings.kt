package de.meinname.scopebox

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "LdhSettings",
    storages = [Storage("ldh-digital-cosentials.xml")]
)
class LdhSettings : PersistentStateComponent<LdhSettings.State> {

    data class State(
        // General features
        var scopeBoxEnabled: Boolean = true,
        var functionHighlightingEnabled: Boolean = true,
        var copyGutterIconEnabled: Boolean = true,
        var codeNotesEnabled: Boolean = true,

        // Memory Check
        var memoryCheckEnabled: Boolean = true,
        var memorySeverity: String = "WARNING",
        var checkLeaks: Boolean = true,
        var checkDoubleFree: Boolean = true,
        var checkUseAfterFree: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        val instance: LdhSettings
            get() = ApplicationManager.getApplication().getService(LdhSettings::class.java)
                ?: LdhSettings()
    }
}
